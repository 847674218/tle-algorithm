/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.cassandra.db;

import java.io.DataInputStream;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import javax.management.MBeanServer;
import javax.management.ObjectName;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.util.concurrent.RateLimiter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.apache.cassandra.concurrent.JMXEnabledThreadPoolExecutor;
import org.apache.cassandra.concurrent.NamedThreadFactory;
import org.apache.cassandra.config.DatabaseDescriptor;
import org.apache.cassandra.db.compaction.CompactionManager;
import org.apache.cassandra.db.filter.*;
import org.apache.cassandra.db.marshal.AbstractType;
import org.apache.cassandra.db.marshal.CompositeType;
import org.apache.cassandra.db.marshal.Int32Type;
import org.apache.cassandra.db.marshal.UUIDType;
import org.apache.cassandra.dht.IPartitioner;
import org.apache.cassandra.dht.Range;
import org.apache.cassandra.dht.Token;
import org.apache.cassandra.gms.ApplicationState;
import org.apache.cassandra.gms.FailureDetector;
import org.apache.cassandra.gms.Gossiper;
import org.apache.cassandra.io.sstable.Descriptor;
import org.apache.cassandra.io.sstable.SSTable;
import org.apache.cassandra.net.IAsyncCallback;
import org.apache.cassandra.net.MessageOut;
import org.apache.cassandra.net.MessagingService;
import org.apache.cassandra.service.*;
import org.apache.cassandra.thrift.*;
import org.apache.cassandra.utils.ByteBufferUtil;
import org.apache.cassandra.utils.FBUtilities;
import org.apache.cassandra.utils.UUIDGen;
import org.apache.cassandra.utils.WrappedRunnable;
import org.cliffc.high_scale_lib.NonBlockingHashSet;

/**
 * The hint schema looks like this:
 *
 * CREATE TABLE hints (
 *   target_id uuid,
 *   hint_id timeuuid,
 *   message_version int,
 *   mutation blob,
 *   PRIMARY KEY (target_id, hint_id, message_version)
 * ) WITH COMPACT STORAGE;
 *
 * Thus, for each node in the cluster we treat its uuid as the partition key; each hint is a logical row
 * (physical composite column) containing the mutation to replay and associated metadata.
 *
 * When FailureDetector signals that a node that was down is back up, we page through
 * the hinted mutations and send them over one at a time, waiting for
 * hinted_handoff_throttle_delay in between each.
 *
 * deliverHints is also exposed to JMX so it can be run manually if FD ever misses
 * its cue somehow.
 */

public class HintedHandOffManager implements HintedHandOffManagerMBean
{
    public static final String MBEAN_NAME = "org.apache.cassandra.db:type=HintedHandoffManager";
    public static final HintedHandOffManager instance = new HintedHandOffManager();

    private static final Logger logger = LoggerFactory.getLogger(HintedHandOffManager.class);
    private static final int PAGE_SIZE = 128;
    private static final int LARGE_NUMBER = 65536; // 64k nodes ought to be enough for anybody.

    private volatile boolean hintedHandOffPaused = false;

    static final CompositeType comparator = CompositeType.getInstance(Arrays.<AbstractType<?>>asList(UUIDType.instance, Int32Type.instance));

    private final NonBlockingHashSet<InetAddress> queuedDeliveries = new NonBlockingHashSet<InetAddress>();

    private final ThreadPoolExecutor executor = new JMXEnabledThreadPoolExecutor(DatabaseDescriptor.getMaxHintsThread(),
                                                                                 Integer.MAX_VALUE,
                                                                                 TimeUnit.SECONDS,
                                                                                 new LinkedBlockingQueue<Runnable>(),
                                                                                 new NamedThreadFactory("HintedHandoff", Thread.MIN_PRIORITY), "internal");

    public void start()
    {
        MBeanServer mbs = ManagementFactory.getPlatformMBeanServer();
        try
        {
            mbs.registerMBean(this, new ObjectName(MBEAN_NAME));
        }
        catch (Exception e)
        {
            throw new RuntimeException(e);
        }
        logger.debug("Created HHOM instance, registered MBean.");

        Runnable runnable = new Runnable()
        {
            public void run()
            {
                scheduleAllDeliveries();
            }
        };
        StorageService.optionalTasks.scheduleWithFixedDelay(runnable, 10, 10, TimeUnit.MINUTES);
    }

    private static void deleteHint(ByteBuffer tokenBytes, ByteBuffer columnName, long timestamp) throws IOException
    {
        RowMutation rm = new RowMutation(Table.SYSTEM_KS, tokenBytes);
        rm.delete(new QueryPath(SystemTable.HINTS_CF, null, columnName), timestamp);
        rm.applyUnsafe(); // don't bother with commitlog since we're going to flush as soon as we're done with delivery
    }

    public void deleteHintsForEndpoint(final String ipOrHostname)
    {
        try
        {
            InetAddress endpoint = InetAddress.getByName(ipOrHostname);
            deleteHintsForEndpoint(endpoint);
        }
        catch (UnknownHostException e)
        {
            logger.warn("Unable to find "+ipOrHostname+", not a hostname or ipaddr of a node?:");
            e.printStackTrace();
            throw new RuntimeException(e);
        }
    }

    public void deleteHintsForEndpoint(final InetAddress endpoint)
    {
        if (!StorageService.instance.getTokenMetadata().isMember(endpoint))
            return;
        UUID hostId = StorageService.instance.getTokenMetadata().getHostId(endpoint);
        ByteBuffer hostIdBytes = ByteBuffer.wrap(UUIDGen.decompose(hostId));
        final RowMutation rm = new RowMutation(Table.SYSTEM_KS, hostIdBytes);
        rm.delete(new QueryPath(SystemTable.HINTS_CF), System.currentTimeMillis());

        // execute asynchronously to avoid blocking caller (which may be processing gossip)
        Runnable runnable = new Runnable()
        {
            public void run()
            {
                try
                {
                    logger.info("Deleting any stored hints for " + endpoint);
                    rm.apply();
                    compact();
                }
                catch (Exception e)
                {
                    logger.warn("Could not delete hints for " + endpoint + ": " + e);
                }
            }
        };
        StorageService.optionalTasks.execute(runnable);
    }

    @VisibleForTesting
    protected Future<?> compact() throws ExecutionException, InterruptedException
    {
        final ColumnFamilyStore hintStore = Table.open(Table.SYSTEM_KS).getColumnFamilyStore(SystemTable.HINTS_CF);
        hintStore.forceBlockingFlush();
        ArrayList<Descriptor> descriptors = new ArrayList<Descriptor>();
        for (SSTable sstable : hintStore.getSSTables())
            descriptors.add(sstable.descriptor);
        return CompactionManager.instance.submitUserDefined(hintStore, descriptors, (int) (System.currentTimeMillis() / 1000));
    }

    private static boolean pagingFinished(ColumnFamily hintColumnFamily, ByteBuffer startColumn)
    {
        // done if no hints found or the start column (same as last column processed in previous iteration) is the only one
        return hintColumnFamily == null
               || (hintColumnFamily.getSortedColumns().size() == 1 && hintColumnFamily.getColumn(startColumn) != null);
    }

    private int waitForSchemaAgreement(InetAddress endpoint) throws TimeoutException
    {
        Gossiper gossiper = Gossiper.instance;
        int waited = 0;
        // first, wait for schema to be gossiped.
        while (gossiper.getEndpointStateForEndpoint(endpoint).getApplicationState(ApplicationState.SCHEMA) == null)
        {
            try
            {
                Thread.sleep(1000);
            }
            catch (InterruptedException e)
            {
                throw new AssertionError(e);
            }
            waited += 1000;
            if (waited > 2 * StorageService.RING_DELAY)
                throw new TimeoutException("Didin't receive gossiped schema from " + endpoint + " in " + 2 * StorageService.RING_DELAY + "ms");
        }
        waited = 0;
        // then wait for the correct schema version.
        // usually we use DD.getDefsVersion, which checks the local schema uuid as stored in the system table.
        // here we check the one in gossip instead; this serves as a canary to warn us if we introduce a bug that
        // causes the two to diverge (see CASSANDRA-2946)
        while (!gossiper.getEndpointStateForEndpoint(endpoint).getApplicationState(ApplicationState.SCHEMA).value.equals(
                gossiper.getEndpointStateForEndpoint(FBUtilities.getBroadcastAddress()).getApplicationState(ApplicationState.SCHEMA).value))
        {
            try
            {
                Thread.sleep(1000);
            }
            catch (InterruptedException e)
            {
                throw new AssertionError(e);
            }
            waited += 1000;
            if (waited > 2 * StorageService.RING_DELAY)
                throw new TimeoutException("Could not reach schema agreement with " + endpoint + " in " + 2 * StorageService.RING_DELAY + "ms");
        }
        logger.debug("schema for {} matches local schema", endpoint);
        return waited;
    }

    private void deliverHintsToEndpoint(InetAddress endpoint) throws IOException, DigestMismatchException, InvalidRequestException, InterruptedException
    {
        try
        {
            deliverHintsToEndpointInternal(endpoint);
        }
        finally
        {
            queuedDeliveries.remove(endpoint);
        }
    }

    private void deliverHintsToEndpointInternal(InetAddress endpoint) throws IOException, DigestMismatchException, InvalidRequestException, InterruptedException
    {
        ColumnFamilyStore hintStore = Table.open(Table.SYSTEM_KS).getColumnFamilyStore(SystemTable.HINTS_CF);
        if (hintStore.isEmpty())
            return; // nothing to do, don't confuse users by logging a no-op handoff

        // check if hints delivery has been paused
        if (hintedHandOffPaused)
        {
            logger.debug("Hints delivery process is paused, aborting");
            return;
        }

        logger.debug("Checking remote({}) schema before delivering hints", endpoint);
        try
        {
            waitForSchemaAgreement(endpoint);
        }
        catch (TimeoutException e)
        {
            return;
        }

        if (!FailureDetector.instance.isAlive(endpoint))
        {
            logger.debug("Endpoint {} died before hint delivery, aborting", endpoint);
            return;
        }

        // 1. Get the key of the endpoint we need to handoff
        // 2. For each column, deserialize the mutation and send it to the endpoint
        // 3. Delete the subcolumn if the write was successful
        // 4. Force a flush
        // 5. Do major compaction to clean up all deletes etc.

        // find the hints for the node using its token.
        UUID hostId = Gossiper.instance.getHostId(endpoint);
        logger.info("Started hinted handoff for host: {} with IP: {}", hostId, endpoint);
        final ByteBuffer hostIdBytes = ByteBuffer.wrap(UUIDGen.decompose(hostId));
        DecoratedKey epkey =  StorageService.getPartitioner().decorateKey(hostIdBytes);

        final AtomicInteger rowsReplayed = new AtomicInteger(0);
        ByteBuffer startColumn = ByteBufferUtil.EMPTY_BYTE_BUFFER;

        int pageSize = PAGE_SIZE;
        // read less columns (mutations) per page if they are very large
        if (hintStore.getMeanColumns() > 0)
        {
            int averageColumnSize = (int) (hintStore.getMeanRowSize() / hintStore.getMeanColumns());
            pageSize = Math.min(PAGE_SIZE, DatabaseDescriptor.getInMemoryCompactionLimit() / averageColumnSize);
            pageSize = Math.max(2, pageSize); // page size of 1 does not allow actual paging b/c of >= behavior on startColumn
            logger.debug("average hinted-row column size is {}; using pageSize of {}", averageColumnSize, pageSize);
        }

        // rate limit is in bytes per second. Uses Double.MAX_VALUE if disabled (set to 0 in cassandra.yaml).
        int throttleInKB = DatabaseDescriptor.getHintedHandoffThrottleInKB();
        RateLimiter rateLimiter = RateLimiter.create(throttleInKB == 0 ? Double.MAX_VALUE : throttleInKB * 1024);

        while (true)
        {
            // check if hints delivery has been paused during the process
            if (hintedHandOffPaused)
            {
                logger.debug("Hints delivery process is paused, aborting");
                break;
            }

            QueryFilter filter = QueryFilter.getSliceFilter(epkey, new QueryPath(SystemTable.HINTS_CF), startColumn, ByteBufferUtil.EMPTY_BYTE_BUFFER, false, pageSize);
            ColumnFamily hintsPage = ColumnFamilyStore.removeDeleted(hintStore.getColumnFamily(filter), (int)(System.currentTimeMillis() / 1000));
            if (pagingFinished(hintsPage, startColumn))
            {
                if (ByteBufferUtil.EMPTY_BYTE_BUFFER.equals(startColumn))
                {
                    // we've started from the beginning and could not find anything (only maybe tombstones)
                    break;
                }
                else
                {
                    // restart query from the first column until we read an empty row;
                    // that will tell us everything was delivered successfully with no timeouts
                    startColumn = ByteBufferUtil.EMPTY_BYTE_BUFFER;
                    continue;
                }

            }

            for (final IColumn hint : hintsPage.getSortedColumns())
            {
                // Skip tombstones:
                // if we iterate quickly enough, it's possible that we could request a new page in the same millisecond
                // in which the local deletion timestamp was generated on the last column in the old page, in which
                // case the hint will have no columns (since it's deleted) but will still be included in the resultset
                // since (even with gcgs=0) it's still a "relevant" tombstone.
                if (!hint.isLive())
                    continue;

                if (hintedHandOffPaused)
                {
                    logger.debug("Hints delivery process is paused, aborting");
                    break;
                }
                startColumn = hint.name();

                ByteBuffer[] components = comparator.split(hint.name());
                int version = Int32Type.instance.compose(components[1]);
                DataInputStream in = new DataInputStream(ByteBufferUtil.inputStream(hint.value()));
                RowMutation rm;
                try
                {
                    rm = RowMutation.serializer.deserialize(in, version);
                }
                catch (UnknownColumnFamilyException e)
                {
                    logger.debug("Skipping delivery of hint for deleted columnfamily", e);
                    deleteHint(hostIdBytes, hint.name(), hint.maxTimestamp());
                    continue;
                }

                MessageOut<RowMutation> message = rm.createMessage();
                rateLimiter.acquire(message.serializedSize(MessagingService.current_version));
                WrappedRunnable callback = new WrappedRunnable()
                {
                    public void runMayThrow() throws IOException
                    {
                        rowsReplayed.incrementAndGet();
                        deleteHint(hostIdBytes, hint.name(), hint.maxTimestamp());
                    }
                };
                IAsyncCallback responseHandler = new WriteResponseHandler(endpoint, WriteType.UNLOGGED_BATCH, callback);
                MessagingService.instance().sendRR(message, endpoint, responseHandler);
            }

            // check if node is still alive and we should continue delivery process
            if (!FailureDetector.instance.isAlive(endpoint))
            {
                logger.debug("Endpoint {} died during hint delivery, aborting", endpoint);
                return;
            }
        }

        if (rowsReplayed.get() > 0)
        {
            try
            {
                compact().get();
            }
            catch (Exception e)
            {
                throw new RuntimeException(e);
            }
        }

        logger.info(String.format("Finished hinted handoff of %s rows to endpoint %s", rowsReplayed, endpoint));
        if (hintedHandOffPaused)
        {
            logger.info("Hints delivery process is paused, not delivering further hints");
        }
    }

    /**
     * Attempt delivery to any node for which we have hints.  Necessary since we can generate hints even for
     * nodes which are never officially down/failed.
     */
    private void scheduleAllDeliveries()
    {
        if (logger.isDebugEnabled())
          logger.debug("Started scheduleAllDeliveries");

        ColumnFamilyStore hintStore = Table.open(Table.SYSTEM_KS).getColumnFamilyStore(SystemTable.HINTS_CF);
        IPartitioner p = StorageService.getPartitioner();
        RowPosition minPos = p.getMinimumToken().minKeyBound();
        Range<RowPosition> range = new Range<RowPosition>(minPos, minPos, p);
        IDiskAtomFilter filter = new NamesQueryFilter(ImmutableSortedSet.<ByteBuffer>of());
        List<Row> rows = hintStore.getRangeSlice(null, range, Integer.MAX_VALUE, filter, null);
        for (Row row : rows)
        {
            UUID hostId = UUIDGen.getUUID(row.key.key);
            InetAddress target = StorageService.instance.getTokenMetadata().getEndpointForHostId(hostId);
            // token may have since been removed (in which case we have just read back a tombstone)
            if (target != null)
                scheduleHintDelivery(target);
        }

        if (logger.isDebugEnabled())
          logger.debug("Finished scheduleAllDeliveries");
    }

    /*
     * This method is used to deliver hints to a particular endpoint.
     * When we learn that some endpoint is back up we deliver the data
     * to him via an event driven mechanism.
    */
    public void scheduleHintDelivery(final InetAddress to)
    {
        // We should not deliver hints to the same host in 2 different threads
        if (queuedDeliveries.contains(to) || !queuedDeliveries.add(to))
            return;
        logger.debug("Scheduling delivery of Hints to {}", to);
        Runnable r = new WrappedRunnable()
        {
            public void runMayThrow() throws Exception
            {
                deliverHintsToEndpoint(to);
            }
        };
        executor.execute(r);
    }

    public void scheduleHintDelivery(String to) throws UnknownHostException
    {
        scheduleHintDelivery(InetAddress.getByName(to));
    }

    public void pauseHintsDelivery(boolean b) {
        hintedHandOffPaused = b;
    }

    public List<String> listEndpointsPendingHints()
    {
        Token.TokenFactory tokenFactory = StorageService.getPartitioner().getTokenFactory();

        // Extract the keys as strings to be reported.
        LinkedList<String> result = new LinkedList<String>();
        for (Row row : getHintsSlice(1))
        {
            if (row.cf != null) //ignore removed rows
                result.addFirst(tokenFactory.toString(row.key.token));
        }
        return result;
    }

    public Map<String, Integer> countPendingHints()
    {
        Token.TokenFactory tokenFactory = StorageService.getPartitioner().getTokenFactory();

        Map<String, Integer> result = new HashMap<String, Integer>();
        for (Row row : getHintsSlice(Integer.MAX_VALUE))
        {
            if (row.cf == null) // ignore removed rows
                continue;

            int count = row.cf.getColumnCount();
            if (count > 0)
                result.put(tokenFactory.toString(row.key.token), count);
        }
        return result;
    }

    private List<Row> getHintsSlice(int columnCount)
    {
        // ColumnParent for HintsCF...
        ColumnParent parent = new ColumnParent(SystemTable.HINTS_CF);

        // Get count # of columns...
        SliceQueryFilter predicate = new SliceQueryFilter(ByteBufferUtil.EMPTY_BYTE_BUFFER,
                                                          ByteBufferUtil.EMPTY_BYTE_BUFFER,
                                                          false,
                                                          columnCount);

        // From keys "" to ""...
        IPartitioner<?> partitioner = StorageService.getPartitioner();
        RowPosition minPos = partitioner.getMinimumToken().minKeyBound();
        Range<RowPosition> range = new Range<RowPosition>(minPos, minPos);

        // Get a bunch of rows!
        List<Row> rows;
        try
        {
            rows = StorageProxy.getRangeSlice(new RangeSliceCommand(Table.SYSTEM_KS, parent, predicate, range, null, LARGE_NUMBER), ConsistencyLevel.ONE);
        }
        catch (Exception e)
        {
            logger.info("HintsCF getEPPendingHints timed out.");
            throw new RuntimeException(e);
        }
        return rows;
    }

}
