/**
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

package org.apache.cassandra.service;

import java.io.File;
import java.io.IOError;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.util.*;
import java.util.Map.Entry;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import javax.management.MBeanServer;
import javax.management.Notification;
import javax.management.NotificationBroadcasterSupport;
import javax.management.ObjectName;

import com.google.common.base.Supplier;
import com.google.common.collect.*;

import org.apache.cassandra.metrics.ClientRequestMetrics;

import org.apache.log4j.Level;
import org.apache.commons.lang.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.apache.cassandra.concurrent.DebuggableScheduledThreadPoolExecutor;
import org.apache.cassandra.concurrent.Stage;
import org.apache.cassandra.concurrent.StageManager;
import org.apache.cassandra.config.*;
import org.apache.cassandra.db.*;
import org.apache.cassandra.db.Table;
import org.apache.cassandra.db.commitlog.CommitLog;
import org.apache.cassandra.dht.*;
import org.apache.cassandra.dht.Range;
import org.apache.cassandra.gms.*;
import org.apache.cassandra.io.sstable.SSTableDeletingTask;
import org.apache.cassandra.io.sstable.SSTableLoader;
import org.apache.cassandra.io.util.FileUtils;
import org.apache.cassandra.locator.AbstractReplicationStrategy;
import org.apache.cassandra.locator.DynamicEndpointSnitch;
import org.apache.cassandra.locator.IEndpointSnitch;
import org.apache.cassandra.locator.TokenMetadata;
import org.apache.cassandra.net.IAsyncResult;
import org.apache.cassandra.net.Message;
import org.apache.cassandra.net.MessagingService;
import org.apache.cassandra.net.ResponseVerbHandler;
import org.apache.cassandra.service.AntiEntropyService.TreeRequestVerbHandler;
import org.apache.cassandra.streaming.*;
import org.apache.cassandra.thrift.*;
import org.apache.cassandra.utils.FBUtilities;
import org.apache.cassandra.utils.NodeId;
import org.apache.cassandra.utils.Pair;
import org.apache.cassandra.utils.OutputHandler;
import org.apache.cassandra.utils.WrappedRunnable;

/**
 * This abstraction contains the token/identifier of this node
 * on the identifier space. This token gets gossiped around.
 * This class will also maintain histograms of the load information
 * of other nodes in the cluster.
 */
public class StorageService extends NotificationBroadcasterSupport implements IEndpointStateChangeSubscriber, StorageServiceMBean
{
    private static Logger logger_ = LoggerFactory.getLogger(StorageService.class);

    public static final int RING_DELAY = getRingDelay(); // delay after which we assume ring has stablized

    /* JMX notification serial number counter */
    private final AtomicLong notificationSerialNumber = new AtomicLong();

    /* All verb handler identifiers */
    public enum Verb
    {
        MUTATION,
        BINARY, // Deprecated
        READ_REPAIR,
        READ,
        REQUEST_RESPONSE, // client-initiated reads and writes
        STREAM_INITIATE, // Deprecated
        STREAM_INITIATE_DONE, // Deprecated
        STREAM_REPLY,
        STREAM_REQUEST,
        RANGE_SLICE,
        BOOTSTRAP_TOKEN,
        TREE_REQUEST,
        TREE_RESPONSE,
        JOIN, // Deprecated
        GOSSIP_DIGEST_SYN,
        GOSSIP_DIGEST_ACK,
        GOSSIP_DIGEST_ACK2,
        DEFINITIONS_ANNOUNCE, // Deprecated
        DEFINITIONS_UPDATE,
        TRUNCATE,
        SCHEMA_CHECK,
        INDEX_SCAN, // Deprecated
        REPLICATION_FINISHED,
        INTERNAL_RESPONSE, // responses to internal calls
        COUNTER_MUTATION,
        STREAMING_REPAIR_REQUEST,
        STREAMING_REPAIR_RESPONSE,
        SNAPSHOT, // Similar to nt snapshot
        MIGRATION_REQUEST,
        GOSSIP_SHUTDOWN,
        // use as padding for backwards compatability where a previous version needs to validate a verb from the future.
        UNUSED_1,
        UNUSED_2,
        UNUSED_3,
        ;
        // remember to add new verbs at the end, since we serialize by ordinal
    }
    public static final Verb[] VERBS = Verb.values();

    public static final EnumMap<StorageService.Verb, Stage> verbStages = new EnumMap<StorageService.Verb, Stage>(StorageService.Verb.class)
    {{
        put(Verb.MUTATION, Stage.MUTATION);
        put(Verb.BINARY, Stage.MUTATION);
        put(Verb.READ_REPAIR, Stage.MUTATION);
        put(Verb.TRUNCATE, Stage.MUTATION);
        put(Verb.READ, Stage.READ);
        put(Verb.REQUEST_RESPONSE, Stage.REQUEST_RESPONSE);
        put(Verb.STREAM_REPLY, Stage.MISC); // TODO does this really belong on misc? I've just copied old behavior here
        put(Verb.STREAM_REQUEST, Stage.STREAM);
        put(Verb.RANGE_SLICE, Stage.READ);
        put(Verb.BOOTSTRAP_TOKEN, Stage.MISC);
        put(Verb.TREE_REQUEST, Stage.ANTI_ENTROPY);
        put(Verb.TREE_RESPONSE, Stage.ANTI_ENTROPY);
        put(Verb.STREAMING_REPAIR_REQUEST, Stage.ANTI_ENTROPY);
        put(Verb.STREAMING_REPAIR_RESPONSE, Stage.ANTI_ENTROPY);
        put(Verb.GOSSIP_DIGEST_ACK, Stage.GOSSIP);
        put(Verb.GOSSIP_DIGEST_ACK2, Stage.GOSSIP);
        put(Verb.GOSSIP_DIGEST_SYN, Stage.GOSSIP);
        put(Verb.GOSSIP_SHUTDOWN, Stage.GOSSIP);
        put(Verb.DEFINITIONS_UPDATE, Stage.MIGRATION);
        put(Verb.SCHEMA_CHECK, Stage.MIGRATION);
        put(Verb.MIGRATION_REQUEST, Stage.MIGRATION);
        put(Verb.INDEX_SCAN, Stage.READ);
        put(Verb.REPLICATION_FINISHED, Stage.MISC);
        put(Verb.INTERNAL_RESPONSE, Stage.INTERNAL_RESPONSE);
        put(Verb.COUNTER_MUTATION, Stage.MUTATION);
        put(Verb.SNAPSHOT, Stage.MISC);
        put(Verb.UNUSED_1, Stage.INTERNAL_RESPONSE);
        put(Verb.UNUSED_2, Stage.INTERNAL_RESPONSE);
        put(Verb.UNUSED_3, Stage.INTERNAL_RESPONSE);
    }};

    private static int getRingDelay()
    {
        String newdelay = System.getProperty("cassandra.ring_delay_ms");
        if (newdelay != null)
        {
            logger_.info("Overriding RING_DELAY to {}ms", newdelay);
            return Integer.parseInt(newdelay);
        }
        else
            return 30 * 1000;
    }

    /**
     * This pool is used for periodic short (sub-second) tasks.
     */
     public static final DebuggableScheduledThreadPoolExecutor scheduledTasks = new DebuggableScheduledThreadPoolExecutor("ScheduledTasks");

    /**
     * This pool is used by tasks that can have longer execution times, and usually are non periodic.
     */
    public static final DebuggableScheduledThreadPoolExecutor tasks = new DebuggableScheduledThreadPoolExecutor("NonPeriodicTasks");

    /**
     * tasks that do not need to be waited for on shutdown/drain
     */
    public static final DebuggableScheduledThreadPoolExecutor optionalTasks = new DebuggableScheduledThreadPoolExecutor("OptionalTasks");
    static
    {
        tasks.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
    }

    /* This abstraction maintains the token/endpoint metadata information */
    private TokenMetadata tokenMetadata_ = new TokenMetadata();

    public VersionedValue.VersionedValueFactory valueFactory = new VersionedValue.VersionedValueFactory(getPartitioner());

    public static final StorageService instance = new StorageService();

    public static IPartitioner getPartitioner()
    {
        return DatabaseDescriptor.getPartitioner();
    }

    public Collection<Range<Token>> getLocalRanges(String table)
    {
        return getRangesForEndpoint(table, FBUtilities.getBroadcastAddress());
    }

    public Range<Token> getLocalPrimaryRange()
    {
        return getPrimaryRangeForEndpoint(FBUtilities.getBroadcastAddress());
    }

    // For JMX's sake. Use getLocalPrimaryRange for internal uses
    public List<String> getPrimaryRange()
    {
        return getLocalPrimaryRange().asList();
    }

    private final Set<InetAddress> replicatingNodes = Collections.synchronizedSet(new HashSet<InetAddress>());
    private CassandraDaemon daemon;

    private InetAddress removingNode;

    /* Are we starting this node in bootstrap mode? */
    private boolean isBootstrapMode;

    /* we bootstrap but do NOT join the ring unless told to do so */
    private boolean isSurveyMode= Boolean.parseBoolean(System.getProperty("cassandra.write_survey", "false"));

    /* when intialized as a client, we shouldn't write to the system table. */
    private boolean isClientMode;
    private boolean initialized;
    private volatile boolean joined = false;

    private static enum Mode { NORMAL, CLIENT, JOINING, LEAVING, DECOMMISSIONED, MOVING, DRAINING, DRAINED }
    private Mode operationMode;

    private final MigrationManager migrationManager = new MigrationManager();

    /* Used for tracking drain progress */
    private volatile int totalCFs, remainingCFs;

    private static final AtomicInteger nextRepairCommand = new AtomicInteger();

    private final ObjectName jmxObjectName;

    public void finishBootstrapping()
    {
        isBootstrapMode = false;
    }

    /** This method updates the local token on disk  */
    public void setToken(Token token)
    {
        if (logger_.isDebugEnabled())
            logger_.debug("Setting token to {}", token);
        SystemTable.updateToken(token);
        tokenMetadata_.updateNormalToken(token, FBUtilities.getBroadcastAddress());
        Gossiper.instance.addLocalApplicationState(ApplicationState.STATUS, valueFactory.normal(getLocalToken()));
        setMode(Mode.NORMAL, false);
    }

    public StorageService()
    {
        MBeanServer mbs = ManagementFactory.getPlatformMBeanServer();
        try
        {
            jmxObjectName = new ObjectName("org.apache.cassandra.db:type=StorageService");
            mbs.registerMBean(this, jmxObjectName);
        }
        catch (Exception e)
        {
            throw new RuntimeException(e);
        }

        /* register the verb handlers */
        MessagingService.instance().registerVerbHandlers(Verb.MUTATION, new RowMutationVerbHandler());
        MessagingService.instance().registerVerbHandlers(Verb.READ_REPAIR, new ReadRepairVerbHandler());
        MessagingService.instance().registerVerbHandlers(Verb.READ, new ReadVerbHandler());
        MessagingService.instance().registerVerbHandlers(Verb.RANGE_SLICE, new RangeSliceVerbHandler());
        MessagingService.instance().registerVerbHandlers(Verb.INDEX_SCAN, new IndexScanVerbHandler());
        MessagingService.instance().registerVerbHandlers(Verb.COUNTER_MUTATION, new CounterMutationVerbHandler());
        MessagingService.instance().registerVerbHandlers(Verb.TRUNCATE, new TruncateVerbHandler());

        // see BootStrapper for a summary of how the bootstrap verbs interact
        MessagingService.instance().registerVerbHandlers(Verb.BOOTSTRAP_TOKEN, new BootStrapper.BootstrapTokenVerbHandler());
        MessagingService.instance().registerVerbHandlers(Verb.STREAM_REQUEST, new StreamRequestVerbHandler());
        MessagingService.instance().registerVerbHandlers(Verb.STREAM_REPLY, new StreamReplyVerbHandler());
        MessagingService.instance().registerVerbHandlers(Verb.REPLICATION_FINISHED, new ReplicationFinishedVerbHandler());
        MessagingService.instance().registerVerbHandlers(Verb.REQUEST_RESPONSE, new ResponseVerbHandler());
        MessagingService.instance().registerVerbHandlers(Verb.INTERNAL_RESPONSE, new ResponseVerbHandler());
        MessagingService.instance().registerVerbHandlers(Verb.TREE_REQUEST, new TreeRequestVerbHandler());
        MessagingService.instance().registerVerbHandlers(Verb.TREE_RESPONSE, new AntiEntropyService.TreeResponseVerbHandler());
        MessagingService.instance().registerVerbHandlers(Verb.STREAMING_REPAIR_REQUEST, new StreamingRepairTask.StreamingRepairRequest());
        MessagingService.instance().registerVerbHandlers(Verb.STREAMING_REPAIR_RESPONSE, new StreamingRepairTask.StreamingRepairResponse());

        MessagingService.instance().registerVerbHandlers(Verb.GOSSIP_DIGEST_SYN, new GossipDigestSynVerbHandler());
        MessagingService.instance().registerVerbHandlers(Verb.GOSSIP_DIGEST_ACK, new GossipDigestAckVerbHandler());
        MessagingService.instance().registerVerbHandlers(Verb.GOSSIP_DIGEST_ACK2, new GossipDigestAck2VerbHandler());
        MessagingService.instance().registerVerbHandlers(Verb.GOSSIP_SHUTDOWN, new GossipShutdownVerbHandler());

        MessagingService.instance().registerVerbHandlers(Verb.DEFINITIONS_UPDATE, new DefinitionsUpdateVerbHandler());
        MessagingService.instance().registerVerbHandlers(Verb.SCHEMA_CHECK, new SchemaCheckVerbHandler());
        MessagingService.instance().registerVerbHandlers(Verb.MIGRATION_REQUEST, new MigrationRequestVerbHandler());

        MessagingService.instance().registerVerbHandlers(Verb.SNAPSHOT, new SnapshotVerbHandler());

        // spin up the streaming service so it is available for jmx tools.
        if (StreamingService.instance == null)
            throw new RuntimeException("Streaming service is unavailable.");
    }

    public void registerDaemon(CassandraDaemon daemon)
    {
        this.daemon = daemon;
    }

    // should only be called via JMX
    public void stopGossiping()
    {
        if (initialized)
        {
            logger_.warn("Stopping gossip by operator request");
            Gossiper.instance.stop();
            initialized = false;
        }
    }

    // should only be called via JMX
    public void startGossiping()
    {
        if (!initialized)
        {
            logger_.warn("Starting gossip by operator request");
            Gossiper.instance.start((int)(System.currentTimeMillis() / 1000));
            initialized = true;
        }
    }

    // should only be called via JMX
    public void startRPCServer()
    {
        if (daemon == null)
        {
            throw new IllegalStateException("No configured RPC daemon");
        }
        daemon.startRPCServer();
    }

    public void stopRPCServer()
    {
        if (daemon == null)
        {
            throw new IllegalStateException("No configured RPC daemon");
        }
        daemon.stopRPCServer();
    }

    public boolean isRPCServerRunning()
    {
        if (daemon == null)
        {
            return false;
        }
        return daemon.isRPCServerRunning();
    }

    public void stopClient()
    {
        Gossiper.instance.unregister(migrationManager);
        Gossiper.instance.unregister(this);
        Gossiper.instance.stop();
        MessagingService.instance().shutdown();
        // give it a second so that task accepted before the MessagingService shutdown gets submitted to the stage (to avoid RejectedExecutionException)
        try { Thread.sleep(1000L); } catch (InterruptedException e) {}
        StageManager.shutdownNow();
    }

    public boolean isInitialized()
    {
        return initialized;
    }

    public synchronized void initClient() throws IOException, ConfigurationException
    {
        initClient(RING_DELAY);
    }

    public synchronized void initClient(int delay) throws IOException, ConfigurationException
    {
        if (initialized)
        {
            if (!isClientMode)
                throw new UnsupportedOperationException("StorageService does not support switching modes.");
            return;
        }
        initialized = true;
        isClientMode = true;
        logger_.info("Starting up client gossip");
        setMode(Mode.CLIENT, false);
        Gossiper.instance.register(this);
        Gossiper.instance.start((int)(System.currentTimeMillis() / 1000)); // needed for node-ring gathering.
        MessagingService.instance().listen(FBUtilities.getLocalAddress());

        // sleep a while to allow gossip to warm up (the other nodes need to know about this one before they can reply).
        try
        {
            Thread.sleep(delay);
        }
        catch (Exception ex)
        {
            throw new IOError(ex);
        }

        Schema.instance.updateVersionAndAnnounce();
    }

    public synchronized void initServer() throws IOException, ConfigurationException
    {
        initServer(RING_DELAY);
    }

    public synchronized void initServer(int delay) throws IOException, ConfigurationException
    {
        logger_.info("Cassandra version: " + FBUtilities.getReleaseVersionString());
        logger_.info("Thrift API version: " + Constants.VERSION);
        logger_.info("CQL supported versions: " + StringUtils.join(ClientState.getCQLSupportedVersion(), ",") + " (default: " + ClientState.DEFAULT_CQL_VERSION + ")");

        if (initialized)
        {
            if (isClientMode)
                throw new UnsupportedOperationException("StorageService does not support switching modes.");
            return;
        }
        initialized = true;
        isClientMode = false;

        // Ensure StorageProxy is initialized on start-up; see CASSANDRA-3797.
        try
        {
            Class.forName("org.apache.cassandra.service.StorageProxy");
        }
        catch (ClassNotFoundException e)
        {
            throw new AssertionError(e);
        }

        if (!isClientMode)
        {
            // "Touch" metrics classes to trigger static initialization, such that all metrics become available
            // on start-up even if they have not yet been used.
            new ClientRequestMetrics();
        }

        if (Boolean.parseBoolean(System.getProperty("cassandra.load_ring_state", "true")))
        {
            logger_.info("Loading persisted ring state");
            for (Map.Entry<Token, InetAddress> entry : SystemTable.loadTokens().entrySet())
            {
                if (entry.getValue() == FBUtilities.getLocalAddress())
                {
                    // entry has been mistakenly added, delete it
                    SystemTable.removeToken(entry.getKey());
                }
                else
                {
                    tokenMetadata_.updateNormalToken(entry.getKey(), entry.getValue());
                    Gossiper.instance.addSavedEndpoint(entry.getValue());
                }
            }
        }

        if (Boolean.parseBoolean(System.getProperty("cassandra.renew_counter_id", "false")))
        {
            logger_.info("Renewing local node id (as requested)");
            NodeId.renewLocalId();
        }

        // daemon threads, like our executors', continue to run while shutdown hooks are invoked
        Thread drainOnShutdown = new Thread(new WrappedRunnable()
        {
            @Override
            public void runMayThrow() throws ExecutionException, InterruptedException, IOException
            {
                ThreadPoolExecutor mutationStage = StageManager.getStage(Stage.MUTATION);
                if (mutationStage.isShutdown())
                    return; // drained already

                stopRPCServer();
                optionalTasks.shutdown();
                Gossiper.instance.stop();

                // In-progress writes originating here could generate hints to be written, so shut down MessagingService
                // before mutation stage, so we can get all the hints saved before shutting down
                MessagingService.instance().shutdown();
                mutationStage.shutdown();
                mutationStage.awaitTermination(3600, TimeUnit.SECONDS);
                StorageProxy.instance.verifyNoHintsInProgress();

                List<Future<?>> flushes = new ArrayList<Future<?>>();
                for (Table table : Table.all())
                {
                    KSMetaData ksm = Schema.instance.getKSMetaData(table.name);
                    if (!ksm.durableWrites)
                    {
                        for (ColumnFamilyStore cfs : table.getColumnFamilyStores())
                        {
                            Future<?> future = cfs.forceFlush();
                            if (future != null)
                                flushes.add(future);
                        }
                    }
                }
                FBUtilities.waitOnFutures(flushes);

                CommitLog.instance.shutdownBlocking();

                // wait for miscellaneous tasks like sstable and commitlog segment deletion
                tasks.shutdown();
                if (!tasks.awaitTermination(1, TimeUnit.MINUTES))
                    logger_.warn("Miscellaneous task executor still busy after one minute; proceeding with shutdown");
            }
        }, "StorageServiceShutdownHook");
        Runtime.getRuntime().addShutdownHook(drainOnShutdown);

        if (Boolean.parseBoolean(System.getProperty("cassandra.join_ring", "true")))
        {
            joinTokenRing(delay);
        }
        else
        {
            logger_.info("Not joining ring as requested. Use JMX (StorageService->joinRing()) to initiate ring joining");
        }
    }

    private void joinTokenRing(int delay) throws IOException, org.apache.cassandra.config.ConfigurationException
    {
        logger_.info("Starting up server gossip");
        joined = true;


        // have to start the gossip service before we can see any info on other nodes.  this is necessary
        // for bootstrap to get the load info it needs.
        // (we won't be part of the storage ring though until we add a nodeId to our state, below.)
        Gossiper.instance.register(this);
        Gossiper.instance.register(migrationManager);
        Gossiper.instance.start(SystemTable.incrementAndGetGeneration()); // needed for node-ring gathering.

        // gossip Schema.emptyVersion forcing immediate check for schema updates (see MigrationManager#maybeScheduleSchemaPull)
        Schema.instance.updateVersionAndAnnounce(); // Ensure we know our own actual Schema UUID in preparation for updates

        // add rpc listening info
        Gossiper.instance.addLocalApplicationState(ApplicationState.RPC_ADDRESS, valueFactory.rpcaddress(DatabaseDescriptor.getRpcAddress()));
        if (null != DatabaseDescriptor.getReplaceToken())
            Gossiper.instance.addLocalApplicationState(ApplicationState.STATUS, valueFactory.hibernate(true));

        MessagingService.instance().listen(FBUtilities.getLocalAddress());
        LoadBroadcaster.instance.startBroadcasting();
        Gossiper.instance.addLocalApplicationState(ApplicationState.RELEASE_VERSION, valueFactory.releaseVersion());

        HintedHandOffManager.instance.start();

        // We bootstrap if we haven't successfully bootstrapped before, as long as we are not a seed.
        // If we are a seed, or if the user manually sets auto_bootstrap to false,
        // we'll skip streaming data from other nodes and jump directly into the ring.
        //
        // The seed check allows us to skip the RING_DELAY sleep for the single-node cluster case,
        // which is useful for both new users and testing.
        //
        // We attempted to replace this with a schema-presence check, but you need a meaningful sleep
        // to get schema info from gossip which defeats the purpose.  See CASSANDRA-4427 for the gory details.
        Token<?> token;
        InetAddress current = null;
        logger_.debug("Bootstrap variables: {} {} {} {}",
                      new Object[]{ DatabaseDescriptor.isAutoBootstrap(),
                                    SystemTable.bootstrapInProgress(),
                                    SystemTable.bootstrapComplete(),
                                    DatabaseDescriptor.getSeeds().contains(FBUtilities.getBroadcastAddress())});
        if (DatabaseDescriptor.isAutoBootstrap()
            && !SystemTable.bootstrapComplete()
            && !DatabaseDescriptor.getSeeds().contains(FBUtilities.getBroadcastAddress()))
        {
            if (SystemTable.bootstrapInProgress())
                logger_.warn("Detected previous bootstrap failure; retrying");
            else
                SystemTable.setBootstrapState(SystemTable.BootstrapState.IN_PROGRESS);
            setMode(Mode.JOINING, "waiting for ring information", true);
            // first sleep the delay to make sure we see all our peers
            for (int i = 0; i < delay; i += 1000)
            {
                // if we see schema, we can proceed to the next check directly
                if (!Schema.instance.getVersion().equals(Schema.emptyVersion))
                {
                    logger_.debug("got schema: {}", Schema.instance.getVersion());
                    break;
                }
                try
                {
                    Thread.sleep(1000);
                }
                catch (InterruptedException e)
                {
                    throw new AssertionError(e);
                }
            }
            // if our schema hasn't matched yet, keep sleeping until it does
            // (post CASSANDRA-1391 we don't expect this to be necessary very often, but it doesn't hurt to be careful)
            while (!MigrationManager.isReadyForBootstrap())
            {
                setMode(Mode.JOINING, "waiting for schema information to complete", true);
                try
                {
                    Thread.sleep(1000);
                }
                catch (InterruptedException e)
                {
                    throw new AssertionError(e);
                }
            }
            setMode(Mode.JOINING, "schema complete", true);
            setMode(Mode.JOINING, "waiting for pending range calculation", true);
            PendingRangeCalculatorService.instance.blockUntilFinished();
            setMode(Mode.JOINING, "calculation complete, ready to bootstrap", true);


            if (logger_.isDebugEnabled())
                logger_.debug("... got ring + schema info");

            if (DatabaseDescriptor.getReplaceToken() == null)
            {
                if (tokenMetadata_.isMember(FBUtilities.getBroadcastAddress()))
                {
                    String s = "This node is already a member of the token ring; bootstrap aborted. (If replacing a dead node, remove the old one from the ring first.)";
                    throw new UnsupportedOperationException(s);
                }
                setMode(Mode.JOINING, "getting bootstrap token", true);
                token = BootStrapper.getBootstrapToken(tokenMetadata_, LoadBroadcaster.instance.getLoadInfo());
            }
            else
            {
                try
                {
                    // Sleeping additionally to make sure that the server actually is not alive
                    // and giving it more time to gossip if alive.
                    Thread.sleep(LoadBroadcaster.BROADCAST_INTERVAL);
                }
                catch (InterruptedException e)
                {
                    throw new AssertionError(e);
                }
                token = StorageService.getPartitioner().getTokenFactory().fromString(DatabaseDescriptor.getReplaceToken());
                // check for operator errors...
                current = tokenMetadata_.getEndpoint(token);
                if (null != current && Gossiper.instance.getEndpointStateForEndpoint(current).getUpdateTimestamp() > (System.currentTimeMillis() - delay))
                    throw new UnsupportedOperationException("Cannnot replace a token for a Live node... ");
                setMode(Mode.JOINING, "Replacing a node with token: " + token, true);
            }

            bootstrap(token);
            assert !isBootstrapMode; // bootstrap will block until finished
        }
        else
        {
            token = SystemTable.getSavedToken();
            if (token == null)
            {
                String initialToken = DatabaseDescriptor.getInitialToken();
                if (initialToken == null)
                {
                    token = getPartitioner().getRandomToken();
                    logger_.warn("Generated random token " + token + ". Random tokens will result in an unbalanced ring; see http://wiki.apache.org/cassandra/Operations");
                }
                else
                {
                    token = getPartitioner().getTokenFactory().fromString(initialToken);
                    logger_.info("Saved token not found. Using " + token + " from configuration");
                }
            }
            else
            {
                logger_.info("Using saved token " + token);
            }
        }

        if (!isSurveyMode)
        {
            // start participating in the ring.
            SystemTable.setBootstrapState(SystemTable.BootstrapState.COMPLETED);
            setToken(token);
            // remove the existing info about the replaced node.
            if (current != null)
                Gossiper.instance.replacedEndpoint(current);
            logger_.info("Bootstrap/Replace/Move completed! Now serving reads.");
            assert tokenMetadata_.sortedTokens().size() > 0;
        }
        else
        {
            logger_.info("Bootstrap complete, but write survey mode is active, not becoming an active ring member. Use JMX (StorageService->joinRing()) to finalize ring joining.");
        }
    }

    public synchronized void joinRing() throws IOException, org.apache.cassandra.config.ConfigurationException
    {
        if (!joined)
        {
            logger_.info("Joining ring by operator request");
            joinTokenRing(0);
        }
        else if (isSurveyMode)
        {
            setToken(SystemTable.getSavedToken());
            SystemTable.setBootstrapState(SystemTable.BootstrapState.COMPLETED);
            isSurveyMode = false;
            logger_.info("Leaving write survey mode and joining ring at operator request");
            assert tokenMetadata_.sortedTokens().size() > 0;
        }
    }

    public boolean isJoined()
    {
        return joined;
    }

    public void rebuild(String sourceDc)
    {
        logger_.info("rebuild from dc: {}", sourceDc == null ? "(any dc)" : sourceDc);

        RangeStreamer streamer = new RangeStreamer(tokenMetadata_, FBUtilities.getBroadcastAddress(), OperationType.REBUILD);
        streamer.addSourceFilter(new RangeStreamer.FailureDetectorSourceFilter(FailureDetector.instance));
        if (sourceDc != null)
            streamer.addSourceFilter(new RangeStreamer.SingleDatacenterFilter(DatabaseDescriptor.getEndpointSnitch(), sourceDc));

        for (String table : Schema.instance.getNonSystemTables())
            streamer.addRanges(table, getLocalRanges(table));

        streamer.fetch();
    }

    public void setStreamThroughputMbPerSec(int value)
    {
        DatabaseDescriptor.setStreamThroughputOutboundMegabitsPerSec(value);
        logger_.info("setstreamthroughput: throttle set to {}", value);
    }

    public int getStreamThroughputMbPerSec()
    {
        return DatabaseDescriptor.getStreamThroughputOutboundMegabitsPerSec();
    }

    public int getCompactionThroughputMbPerSec()
    {
        return DatabaseDescriptor.getCompactionThroughputMbPerSec();
    }

    public void setCompactionThroughputMbPerSec(int value)
    {
        DatabaseDescriptor.setCompactionThroughputMbPerSec(value);
    }

    public boolean isIncrementalBackupsEnabled()
    {
        return DatabaseDescriptor.isIncrementalBackupsEnabled();
    }

    public void setIncrementalBackupsEnabled(boolean value)
    {
        DatabaseDescriptor.setIncrementalBackupsEnabled(value);
    }

    private void setMode(Mode m, boolean log)
    {
        setMode(m, null, log);
    }

    private void setMode(Mode m, String msg, boolean log)
    {
        operationMode = m;
        String logMsg = msg == null ? m.toString() : String.format("%s: %s", m, msg);
        if (log)
            logger_.info(logMsg);
        else
            logger_.debug(logMsg);
    }

    private void bootstrap(Token token) throws IOException
    {
        isBootstrapMode = true;
        SystemTable.updateToken(token); // DON'T use setToken, that makes us part of the ring locally which is incorrect until we are done bootstrapping
        if (null == DatabaseDescriptor.getReplaceToken())
        {
            // if not an existing token then bootstrap
            Gossiper.instance.addLocalApplicationState(ApplicationState.STATUS, valueFactory.bootstrapping(token));
            setMode(Mode.JOINING, "sleeping " + RING_DELAY + " ms for pending range setup", true);
            try
            {
                Thread.sleep(RING_DELAY);
            }
            catch (InterruptedException e)
            {
                throw new AssertionError(e);
            }
        }
        else
        {
            // Dont set any state for the node which is bootstrapping the existing token...
            tokenMetadata_.updateNormalToken(token, FBUtilities.getBroadcastAddress());
        }
        setMode(Mode.JOINING, "Starting to bootstrap...", true);
        new BootStrapper(FBUtilities.getBroadcastAddress(), token, tokenMetadata_).bootstrap(); // handles token update
    }

    public boolean isBootstrapMode()
    {
        return isBootstrapMode;
    }

    public TokenMetadata getTokenMetadata()
    {
        return tokenMetadata_;
    }

    /**
     * for a keyspace, return the ranges and corresponding listen addresses.
     * @param keyspace
     * @return
     */
    public Map<List<String>, List<String>> getRangeToEndpointMap(String keyspace)
    {
        /* All the ranges for the tokens */
        Map<List<String>, List<String>> map = new HashMap<List<String>, List<String>>();
        for (Map.Entry<Range<Token>,List<InetAddress>> entry : getRangeToAddressMap(keyspace).entrySet())
        {
            map.put(entry.getKey().asList(), stringify(entry.getValue()));
        }
        return map;
    }

    /**
     * Return the rpc address associated with an endpoint as a string.
     * @param endpoint The endpoint to get rpc address for
     * @return
     */
    public String getRpcaddress(InetAddress endpoint)
    {
        if (endpoint.equals(FBUtilities.getBroadcastAddress()))
            return DatabaseDescriptor.getRpcAddress().getHostAddress();
        else if (Gossiper.instance.getEndpointStateForEndpoint(endpoint).getApplicationState(ApplicationState.RPC_ADDRESS) == null)
            return endpoint.getHostAddress();
        else
            return Gossiper.instance.getEndpointStateForEndpoint(endpoint).getApplicationState(ApplicationState.RPC_ADDRESS).value;
    }

    /**
     * for a keyspace, return the ranges and corresponding RPC addresses for a given keyspace.
     * @param keyspace
     * @return
     */
    public Map<List<String>, List<String>> getRangeToRpcaddressMap(String keyspace)
    {
        /* All the ranges for the tokens */
        Map<List<String>, List<String>> map = new HashMap<List<String>, List<String>>();
        for (Map.Entry<Range<Token>, List<InetAddress>> entry : getRangeToAddressMap(keyspace).entrySet())
        {
            List<String> rpcaddrs = new ArrayList<String>(entry.getValue().size());
            for (InetAddress endpoint: entry.getValue())
            {
                rpcaddrs.add(getRpcaddress(endpoint));
            }
            map.put(entry.getKey().asList(), rpcaddrs);
        }
        return map;
    }

    public Map<List<String>, List<String>> getPendingRangeToEndpointMap(String keyspace)
    {
        // some people just want to get a visual representation of things. Allow null and set it to the first
        // non-system table.
        if (keyspace == null)
            keyspace = Schema.instance.getNonSystemTables().get(0);

        Map<List<String>, List<String>> map = new HashMap<List<String>, List<String>>();
        for (Map.Entry<Range<Token>, Collection<InetAddress>> entry : tokenMetadata_.getPendingRanges(keyspace).entrySet())
        {
            List<InetAddress> l = new ArrayList<InetAddress>(entry.getValue());
            map.put(entry.getKey().asList(), stringify(l));
        }
        return map;
    }

    public Map<Range<Token>, List<InetAddress>> getRangeToAddressMap(String keyspace)
    {
        // some people just want to get a visual representation of things. Allow null and set it to the first
        // non-system table.
        if (keyspace == null)
            keyspace = Schema.instance.getNonSystemTables().get(0);

        List<Range<Token>> ranges = getAllRanges(tokenMetadata_.sortedTokens());
        return constructRangeToEndpointMap(keyspace, ranges);
    }

    /**
     * The same as {@code describeRing(String)} but converts TokenRange to the String for JMX compatibility
     *
     * @param keyspace The keyspace to fetch information about
     *
     * @return a List of TokenRange(s) converted to String for the given keyspace
     *
     * @throws InvalidRequestException if there is no ring information available about keyspace
     */
    public List<String> describeRingJMX(String keyspace) throws InvalidRequestException
    {
        List<TokenRange> tokenRanges = describeRing(keyspace);
        List<String> result = new ArrayList<String>(tokenRanges.size());

        for (TokenRange tokenRange : tokenRanges)
            result.add(tokenRange.toString());

        return result;
    }

    /**
     * The TokenRange for a given keyspace.
     *
     * @param keyspace The keyspace to fetch information about
     *
     * @return a List of TokenRange(s) for the given keyspace
     *
     * @throws InvalidRequestException if there is no ring information available about keyspace
     */
    public List<TokenRange> describeRing(String keyspace) throws InvalidRequestException
    {
        if (keyspace == null || !Schema.instance.getNonSystemTables().contains(keyspace))
            throw new InvalidRequestException("There is no ring for the keyspace: " + keyspace);

        List<TokenRange> ranges = new ArrayList<TokenRange>();
        Token.TokenFactory tf = getPartitioner().getTokenFactory();

        for (Map.Entry<Range<Token>, List<InetAddress>> entry : getRangeToAddressMap(keyspace).entrySet())
        {
            Range range = entry.getKey();
            List<InetAddress> addresses = entry.getValue();
            List<String> endpoints = new ArrayList<String>(addresses.size());
            List<String> rpc_endpoints = new ArrayList<String>(addresses.size());
            List<EndpointDetails> epDetails = new ArrayList<EndpointDetails>(addresses.size());

            for (InetAddress endpoint : addresses)
            {
                EndpointDetails details = new EndpointDetails();
                details.host = endpoint.getHostAddress();
                details.datacenter = DatabaseDescriptor.getEndpointSnitch().getDatacenter(endpoint);
                details.rack = DatabaseDescriptor.getEndpointSnitch().getRack(endpoint);

                endpoints.add(details.host);
                rpc_endpoints.add(getRpcaddress(endpoint));

                epDetails.add(details);
            }

            TokenRange tr = new TokenRange(tf.toString(range.left.getToken()), tf.toString(range.right.getToken()), endpoints)
                                    .setEndpoint_details(epDetails)
                                    .setRpc_endpoints(rpc_endpoints);

            ranges.add(tr);
        }

        return ranges;
    }

    public Map<String, String> getTokenToEndpointMap()
    {
        Map<Token, InetAddress> mapInetAddress = tokenMetadata_.getNormalAndBootstrappingTokenToEndpointMap();
        // in order to preserve tokens in ascending order, we use LinkedHashMap here
        Map<String, String> mapString = new LinkedHashMap<String, String>(mapInetAddress.size());
        List<Token> tokens = new ArrayList<Token>(mapInetAddress.keySet());
        Collections.sort(tokens);
        for (Token token : tokens)
        {
            mapString.put(token.toString(), mapInetAddress.get(token).getHostAddress());
        }
        return mapString;
    }

    /**
     * Construct the range to endpoint mapping based on the true view
     * of the world.
     * @param ranges
     * @return mapping of ranges to the replicas responsible for them.
    */
    private Map<Range<Token>, List<InetAddress>> constructRangeToEndpointMap(String keyspace, List<Range<Token>> ranges)
    {
        Map<Range<Token>, List<InetAddress>> rangeToEndpointMap = new HashMap<Range<Token>, List<InetAddress>>();
        for (Range<Token> range : ranges)
        {
            rangeToEndpointMap.put(range, Table.open(keyspace).getReplicationStrategy().getNaturalEndpoints(range.right));
        }
        return rangeToEndpointMap;
    }

    private Map<InetAddress, Collection<Range<Token>>> constructEndpointToRangeMap(String keyspace)
    {
        Multimap<InetAddress, Range<Token>> endpointToRangeMap = Multimaps.newListMultimap(new HashMap<InetAddress, Collection<Range<Token>>>(), new Supplier<List<Range<Token>>>()
        {
            public List<Range<Token>> get()
            {
                return Lists.newArrayList();
            }
        });

        List<Range<Token>> ranges = getAllRanges(tokenMetadata_.sortedTokens());
        for (Range<Token> range : ranges)
        {
            for (InetAddress endpoint : Table.open(keyspace).getReplicationStrategy().getNaturalEndpoints(range.left))
                endpointToRangeMap.put(endpoint, range);
        }
        return endpointToRangeMap.asMap();
    }

    /*
     * Handle the reception of a new particular ApplicationState for a particular endpoint. Note that the value of the
     * ApplicationState has not necessarily "changed" since the last known value, if we already received the same update
     * from somewhere else.
     *
     * onChange only ever sees one ApplicationState piece change at a time (even if many ApplicationState updates were
     * received at the same time), so we perform a kind of state machine here. We are concerned with two events: knowing
     * the token associated with an endpoint, and knowing its operation mode. Nodes can start in either bootstrap or
     * normal mode, and from bootstrap mode can change mode to normal. A node in bootstrap mode needs to have
     * pendingranges set in TokenMetadata; a node in normal mode should instead be part of the token ring.
     *
     * Normal progression of ApplicationState.STATUS values for a node should be like this:
     * STATUS_BOOTSTRAPPING,token
     *   if bootstrapping. stays this way until all files are received.
     * STATUS_NORMAL,token
     *   ready to serve reads and writes.
     * STATUS_LEAVING,token
     *   get ready to leave the cluster as part of a decommission
     * STATUS_LEFT,token
     *   set after decommission is completed.
     *
     * Other STATUS values that may be seen (possibly anywhere in the normal progression):
     * STATUS_MOVING,newtoken
     *   set if node is currently moving to a new token in the ring
     * REMOVING_TOKEN,deadtoken
     *   set if the node is dead and is being removed by its REMOVAL_COORDINATOR
     * REMOVED_TOKEN,deadtoken
     *   set if the node is dead and has been removed by its REMOVAL_COORDINATOR
     *
     * Note: Any time a node state changes from STATUS_NORMAL, it will not be visible to new nodes. So it follows that
     * you should never bootstrap a new node during a removetoken, decommission or move.
     */
    public void onChange(InetAddress endpoint, ApplicationState state, VersionedValue value)
    {
        switch (state)
        {
            case STATUS:
                String apStateValue = value.value;
                String[] pieces = apStateValue.split(VersionedValue.DELIMITER_STR, -1);
                assert (pieces.length > 0);

                String moveName = pieces[0];

                if (moveName.equals(VersionedValue.STATUS_BOOTSTRAPPING))
                    handleStateBootstrap(endpoint, pieces);
                else if (moveName.equals(VersionedValue.STATUS_NORMAL))
                    handleStateNormal(endpoint, pieces);
                else if (moveName.equals(VersionedValue.REMOVING_TOKEN) || moveName.equals(VersionedValue.REMOVED_TOKEN))
                    handleStateRemoving(endpoint, pieces);
                else if (moveName.equals(VersionedValue.STATUS_LEAVING))
                    handleStateLeaving(endpoint, pieces);
                else if (moveName.equals(VersionedValue.STATUS_LEFT))
                    handleStateLeft(endpoint, pieces);
                else if (moveName.equals(VersionedValue.STATUS_MOVING))
                    handleStateMoving(endpoint, pieces);
        }
    }

    /**
     * Handle node bootstrap
     *
     * @param endpoint bootstrapping node
     * @param pieces STATE_BOOTSTRAPPING,bootstrap token as string
     */
    private void handleStateBootstrap(InetAddress endpoint, String[] pieces)
    {
        assert pieces.length >= 2;
        Token token = getPartitioner().getTokenFactory().fromString(pieces[1]);

        if (logger_.isDebugEnabled())
            logger_.debug("Node " + endpoint + " state bootstrapping, token " + token);

        // if this node is present in token metadata, either we have missed intermediate states
        // or the node had crashed. Print warning if needed, clear obsolete stuff and
        // continue.
        if (tokenMetadata_.isMember(endpoint))
        {
            // If isLeaving is false, we have missed both LEAVING and LEFT. However, if
            // isLeaving is true, we have only missed LEFT. Waiting time between completing
            // leave operation and rebootstrapping is relatively short, so the latter is quite
            // common (not enough time for gossip to spread). Therefore we report only the
            // former in the log.
            if (!tokenMetadata_.isLeaving(endpoint))
                logger_.info("Node " + endpoint + " state jump to bootstrap");
            tokenMetadata_.removeEndpoint(endpoint);
        }

        tokenMetadata_.addBootstrapToken(token, endpoint);
        PendingRangeCalculatorService.instance.update();
    }

    /**
     * Handle node move to normal state. That is, node is entering token ring and participating
     * in reads.
     *
     * @param endpoint node
     * @param pieces STATE_NORMAL,token
     */
    private void handleStateNormal(InetAddress endpoint, String[] pieces)
    {
        assert pieces.length >= 2;
        Token token = getPartitioner().getTokenFactory().fromString(pieces[1]);

        if (logger_.isDebugEnabled())
            logger_.debug("Node " + endpoint + " state normal, token " + token);

        if (tokenMetadata_.isMember(endpoint))
            logger_.info("Node " + endpoint + " state jump to normal");

        // we don't want to update if this node is responsible for the token and it has a later startup time than endpoint.
        InetAddress currentOwner = tokenMetadata_.getEndpoint(token);
        if (currentOwner == null)
        {
            logger_.debug("New node " + endpoint + " at token " + token);
            tokenMetadata_.updateNormalToken(token, endpoint);
            if (!isClientMode)
                SystemTable.updateToken(endpoint, token);
        }
        else if (endpoint.equals(currentOwner))
        {
            // set state back to normal, since the node may have tried to leave, but failed and is now back up
            // no need to persist, token/ip did not change
            tokenMetadata_.updateNormalToken(token, endpoint);
        }
        else if (Gossiper.instance.compareEndpointStartup(endpoint, currentOwner) > 0)
        {
            logger_.info(String.format("Nodes %s and %s have the same token %s.  %s is the new owner",
                                       endpoint, currentOwner, token, endpoint));
            tokenMetadata_.updateNormalToken(token, endpoint);
            Gossiper.instance.removeEndpoint(currentOwner);
            if (!isClientMode)
                SystemTable.updateToken(endpoint, token);
        }
        else
        {
            logger_.info(String.format("Nodes %s and %s have the same token %s.  Ignoring %s",
                                       endpoint, currentOwner, token, endpoint));
        }

        if (tokenMetadata_.isMoving(endpoint)) // if endpoint was moving to a new token
            tokenMetadata_.removeFromMoving(endpoint);

        PendingRangeCalculatorService.instance.update();
    }

    /**
     * Handle node preparing to leave the ring
     *
     * @param endpoint node
     * @param pieces STATE_LEAVING,token
     */
    private void handleStateLeaving(InetAddress endpoint, String[] pieces)
    {
        assert pieces.length >= 2;
        String moveValue = pieces[1];
        Token token = getPartitioner().getTokenFactory().fromString(moveValue);

        if (logger_.isDebugEnabled())
            logger_.debug("Node " + endpoint + " state leaving, token " + token);

        // If the node is previously unknown or tokens do not match, update tokenmetadata to
        // have this node as 'normal' (it must have been using this token before the
        // leave). This way we'll get pending ranges right.
        if (!tokenMetadata_.isMember(endpoint))
        {
            logger_.info("Node " + endpoint + " state jump to leaving");
            tokenMetadata_.updateNormalToken(token, endpoint);
        }
        else if (!tokenMetadata_.getToken(endpoint).equals(token))
        {
            logger_.warn("Node " + endpoint + " 'leaving' token mismatch. Long network partition?");
            tokenMetadata_.updateNormalToken(token, endpoint);
        }

        // at this point the endpoint is certainly a member with this token, so let's proceed
        // normally
        tokenMetadata_.addLeavingEndpoint(endpoint);
        PendingRangeCalculatorService.instance.update();
    }

    /**
     * Handle node leaving the ring. This will happen when a node is decommissioned
     *
     * @param endpoint If reason for leaving is decommission, endpoint is the leaving node.
     * @param pieces STATE_LEFT,token
     */
    private void handleStateLeft(InetAddress endpoint, String[] pieces)
    {
        assert pieces.length >= 2;
        Token token = getPartitioner().getTokenFactory().fromString(pieces[1]);

        if (logger_.isDebugEnabled())
            logger_.debug("Node " + endpoint + " state left, token " + token);

        excise(token, endpoint, extractExpireTime(pieces));
    }

    /**
     * Handle node moving inside the ring.
     *
     * @param endpoint moving endpoint address
     * @param pieces STATE_MOVING, token
     */
    private void handleStateMoving(InetAddress endpoint, String[] pieces)
    {
        assert pieces.length >= 2;
        Token token = getPartitioner().getTokenFactory().fromString(pieces[1]);

        if (logger_.isDebugEnabled())
            logger_.debug("Node " + endpoint + " state moving, new token " + token);

        tokenMetadata_.addMovingEndpoint(token, endpoint);

        PendingRangeCalculatorService.instance.update();
    }

    /**
     * Handle notification that a node being actively removed from the ring via 'removetoken'
     *
     * @param endpoint node
     * @param pieces either REMOVED_TOKEN (node is gone) or REMOVING_TOKEN (replicas need to be restored)
     */
    private void handleStateRemoving(InetAddress endpoint, String[] pieces)
    {
        assert (pieces.length > 0);

        if (endpoint.equals(FBUtilities.getBroadcastAddress()))
        {
            logger_.info("Received removeToken gossip about myself. Is this node rejoining after an explicit removetoken?");
            try
            {
                drain();
            }
            catch (Exception e)
            {
                throw new RuntimeException(e);
            }
            return;
        }
        if (tokenMetadata_.isMember(endpoint))
        {
            String state = pieces[0];
            Token removeToken = tokenMetadata_.getToken(endpoint);

            if (VersionedValue.REMOVED_TOKEN.equals(state))
            {
                excise(removeToken, endpoint, extractExpireTime(pieces));
            }
            else if (VersionedValue.REMOVING_TOKEN.equals(state))
            {
                if (logger_.isDebugEnabled())
                    logger_.debug("Token " + removeToken + " removed manually (endpoint was " + endpoint + ")");

                // Note that the endpoint is being removed
                tokenMetadata_.addLeavingEndpoint(endpoint);
                PendingRangeCalculatorService.instance.update();

                // find the endpoint coordinating this removal that we need to notify when we're done
                String[] coordinator = Gossiper.instance.getEndpointStateForEndpoint(endpoint).getApplicationState(ApplicationState.REMOVAL_COORDINATOR).value.split(VersionedValue.DELIMITER_STR, -1);
                Token coordtoken = getPartitioner().getTokenFactory().fromString(coordinator[1]);
                // grab any data we are now responsible for and notify responsible node
                restoreReplicaCount(endpoint, tokenMetadata_.getEndpoint(coordtoken));
            }
        }
        else // now that the gossiper has told us about this nonexistent member, notify the gossiper to remove it
            Gossiper.instance.removeEndpoint(endpoint);
    }

    private void excise(Token token, InetAddress endpoint)
    {
        HintedHandOffManager.instance.deleteHintsForEndpoint(endpoint);
        Gossiper.instance.removeEndpoint(endpoint);
        tokenMetadata_.removeEndpoint(endpoint);
        tokenMetadata_.removeBootstrapToken(token);
        PendingRangeCalculatorService.instance.update();
        if (!isClientMode)
        {
            logger_.info("Removing token " + token + " for " + endpoint);
            SystemTable.removeToken(token);
        }
    }

    private void excise(Token token, InetAddress endpoint, long expireTime)
    {
        addExpireTimeIfFound(endpoint, expireTime);
        excise(token, endpoint);
    }

    protected void addExpireTimeIfFound(InetAddress endpoint, long expireTime)
    {
        if (expireTime != 0L)
        {
            Gossiper.instance.addExpireTimeForEndpoint(endpoint, expireTime);
        }
    }

    protected long extractExpireTime(String[] pieces)
    {
        long expireTime = 0L;
        if (pieces.length >= 3)
        {
            expireTime = Long.parseLong(pieces[2]);
        }
        return expireTime;
    }

    /**
     * Finds living endpoints responsible for the given ranges
     *
     * @param table the table ranges belong to
     * @param ranges the ranges to find sources for
     * @return multimap of addresses to ranges the address is responsible for
     */
    private Multimap<InetAddress, Range<Token>> getNewSourceRanges(String table, Set<Range<Token>> ranges)
    {
        InetAddress myAddress = FBUtilities.getBroadcastAddress();
        Multimap<Range<Token>, InetAddress> rangeAddresses = Table.open(table).getReplicationStrategy().getRangeAddresses(tokenMetadata_);
        Multimap<InetAddress, Range<Token>> sourceRanges = HashMultimap.create();
        IFailureDetector failureDetector = FailureDetector.instance;

        // find alive sources for our new ranges
        for (Range<Token> range : ranges)
        {
            Collection<InetAddress> possibleRanges = rangeAddresses.get(range);
            IEndpointSnitch snitch = DatabaseDescriptor.getEndpointSnitch();
            List<InetAddress> sources = snitch.getSortedListByProximity(myAddress, possibleRanges);

            assert (!sources.contains(myAddress));

            for (InetAddress source : sources)
            {
                if (failureDetector.isAlive(source))
                {
                    sourceRanges.put(source, range);
                    break;
                }
            }
        }
        return sourceRanges;
    }

    /**
     * Sends a notification to a node indicating we have finished replicating data.
     *
     * @param local the local address
     * @param remote node to send notification to
     */
    private void sendReplicationNotification(InetAddress local, InetAddress remote)
    {
        // notify the remote token
        Message msg = new Message(local, StorageService.Verb.REPLICATION_FINISHED, new byte[0], Gossiper.instance.getVersion(remote));
        IFailureDetector failureDetector = FailureDetector.instance;
        if (logger_.isDebugEnabled())
            logger_.debug("Notifying " + remote.toString() + " of replication completion\n");
        while (failureDetector.isAlive(remote))
        {
            IAsyncResult iar = MessagingService.instance().sendRR(msg, remote);
            try
            {
                iar.get(DatabaseDescriptor.getRpcTimeout(), TimeUnit.MILLISECONDS);
                return; // done
            }
            catch(TimeoutException e)
            {
                // try again
            }
        }
    }

    /**
     * Called when an endpoint is removed from the ring. This function checks
     * whether this node becomes responsible for new ranges as a
     * consequence and streams data if needed.
     *
     * This is rather ineffective, but it does not matter so much
     * since this is called very seldom
     *
     * @param endpoint the node that left
     */
    private void restoreReplicaCount(InetAddress endpoint, final InetAddress notifyEndpoint)
    {
        final Multimap<InetAddress, String> fetchSources = HashMultimap.create();
        Multimap<String, Map.Entry<InetAddress, Collection<Range<Token>>>> rangesToFetch = HashMultimap.create();

        final InetAddress myAddress = FBUtilities.getBroadcastAddress();

        for (String table : Schema.instance.getNonSystemTables())
        {
            Multimap<Range<Token>, InetAddress> changedRanges = getChangedRangesForLeaving(table, endpoint);
            Set<Range<Token>> myNewRanges = new HashSet<Range<Token>>();
            for (Map.Entry<Range<Token>, InetAddress> entry : changedRanges.entries())
            {
                if (entry.getValue().equals(myAddress))
                    myNewRanges.add(entry.getKey());
            }
            Multimap<InetAddress, Range<Token>> sourceRanges = getNewSourceRanges(table, myNewRanges);
            for (Map.Entry<InetAddress, Collection<Range<Token>>> entry : sourceRanges.asMap().entrySet())
            {
                fetchSources.put(entry.getKey(), table);
                rangesToFetch.put(table, entry);
            }
        }

        for (final String table : rangesToFetch.keySet())
        {
            for (Map.Entry<InetAddress, Collection<Range<Token>>> entry : rangesToFetch.get(table))
            {
                final InetAddress source = entry.getKey();
                Collection<Range<Token>> ranges = entry.getValue();
                final IStreamCallback callback = new IStreamCallback()
                {
                    public void onSuccess()
                    {
                        synchronized (fetchSources)
                        {
                            fetchSources.remove(source, table);
                            if (fetchSources.isEmpty())
                                sendReplicationNotification(myAddress, notifyEndpoint);
                        }
                    }

                    public void onFailure()
                    {
                        logger_.warn("Streaming from " + source + " failed");
                        onSuccess(); // calling onSuccess to send notification
                    }
                };
                if (logger_.isDebugEnabled())
                    logger_.debug("Requesting from " + source + " ranges " + StringUtils.join(ranges, ", "));
                StreamIn.requestRanges(source, table, ranges, callback, OperationType.RESTORE_REPLICA_COUNT);
            }
        }
    }

    // needs to be modified to accept either a table or ARS.
    private Multimap<Range<Token>, InetAddress> getChangedRangesForLeaving(String table, InetAddress endpoint)
    {
        // First get all ranges the leaving endpoint is responsible for
        Collection<Range<Token>> ranges = getRangesForEndpoint(table, endpoint);

        if (logger_.isDebugEnabled())
            logger_.debug("Node " + endpoint + " ranges [" + StringUtils.join(ranges, ", ") + "]");

        Map<Range<Token>, List<InetAddress>> currentReplicaEndpoints = new HashMap<Range<Token>, List<InetAddress>>();

        // Find (for each range) all nodes that store replicas for these ranges as well
        for (Range<Token> range : ranges)
            currentReplicaEndpoints.put(range, Table.open(table).getReplicationStrategy().calculateNaturalEndpoints(range.right, tokenMetadata_));

        TokenMetadata temp = tokenMetadata_.cloneAfterAllLeft();

        // endpoint might or might not be 'leaving'. If it was not leaving (that is, removetoken
        // command was used), it is still present in temp and must be removed.
        if (temp.isMember(endpoint))
            temp.removeEndpoint(endpoint);

        Multimap<Range<Token>, InetAddress> changedRanges = HashMultimap.create();

        // Go through the ranges and for each range check who will be
        // storing replicas for these ranges when the leaving endpoint
        // is gone. Whoever is present in newReplicaEndpoints list, but
        // not in the currentReplicaEndpoints list, will be needing the
        // range.
        for (Range<Token> range : ranges)
        {
            Collection<InetAddress> newReplicaEndpoints = Table.open(table).getReplicationStrategy().calculateNaturalEndpoints(range.right, temp);
            newReplicaEndpoints.removeAll(currentReplicaEndpoints.get(range));
            if (logger_.isDebugEnabled())
                if (newReplicaEndpoints.isEmpty())
                    logger_.debug("Range " + range + " already in all replicas");
                else
                    logger_.debug("Range " + range + " will be responsibility of " + StringUtils.join(newReplicaEndpoints, ", "));
            changedRanges.putAll(range, newReplicaEndpoints);
        }

        return changedRanges;
    }

    public void onJoin(InetAddress endpoint, EndpointState epState)
    {
        for (Map.Entry<ApplicationState, VersionedValue> entry : epState.getApplicationStateMap().entrySet())
        {
            onChange(endpoint, entry.getKey(), entry.getValue());
        }
    }

    public void onAlive(InetAddress endpoint, EndpointState state)
    {
        if (!isClientMode && getTokenMetadata().isMember(endpoint))
            HintedHandOffManager.instance.scheduleHintDelivery(endpoint);
    }

    public void onRemove(InetAddress endpoint)
    {
        tokenMetadata_.removeEndpoint(endpoint);
        PendingRangeCalculatorService.instance.update();
    }

    public void onDead(InetAddress endpoint, EndpointState state)
    {
        MessagingService.instance().convict(endpoint);
    }

    public void onRestart(InetAddress endpoint, EndpointState state)
    {
        // If we have restarted before the node was even marked down, we need to reset the connection pool
        if (state.isAlive())
            onDead(endpoint, state);
    }

    /** raw load value */
    public double getLoad()
    {
        double bytes = 0;
        for (String tableName : Schema.instance.getTables())
        {
            Table table = Table.open(tableName);
            for (ColumnFamilyStore cfs : table.getColumnFamilyStores())
                bytes += cfs.getLiveDiskSpaceUsed();
        }
        return bytes;
    }

    public String getLoadString()
    {
        return FileUtils.stringifyFileSize(getLoad());
    }

    public Map<String, String> getLoadMap()
    {
        Map<String, String> map = new HashMap<String, String>();
        for (Map.Entry<InetAddress,Double> entry : LoadBroadcaster.instance.getLoadInfo().entrySet())
        {
            map.put(entry.getKey().getHostAddress(), FileUtils.stringifyFileSize(entry.getValue()));
        }
        // gossiper doesn't see its own updates, so we need to special-case the local node
        map.put(FBUtilities.getBroadcastAddress().getHostAddress(), getLoadString());
        return map;
    }

    public final void deliverHints(String host) throws UnknownHostException
    {
        HintedHandOffManager.instance.scheduleHintDelivery(host);
    }

    public Token getLocalToken()
    {
        Token token = SystemTable.getSavedToken();
        assert token != null; // should not be called before initServer sets this
        return token;
    }

    /* These methods belong to the MBean interface */

    public String getToken()
    {
        return getLocalToken().toString();
    }

    public String getReleaseVersion()
    {
        return FBUtilities.getReleaseVersionString();
    }

    public String getSchemaVersion()
    {
        return Schema.instance.getVersion().toString();
    }

    public List<String> getLeavingNodes()
    {
        return stringify(tokenMetadata_.getLeavingEndpoints());
    }

    public List<String> getMovingNodes()
    {
        List<String> endpoints = new ArrayList<String>();

        for (Pair<Token, InetAddress> node : tokenMetadata_.getMovingEndpoints())
        {
            endpoints.add(node.right.getHostAddress());
        }

        return endpoints;
    }

    public List<String> getJoiningNodes()
    {
        return stringify(tokenMetadata_.getBootstrapTokens().values());
    }

    public List<String> getLiveNodes()
    {
        return stringify(Gossiper.instance.getLiveMembers());
    }

    public List<String> getUnreachableNodes()
    {
        return stringify(Gossiper.instance.getUnreachableMembers());
    }

    private static String getCanonicalPath(String filename)
    {
        try
        {
            return new File(filename).getCanonicalPath();
        }
        catch (IOException e)
        {
            throw new IOError(e);
        }
    }

    public String[] getAllDataFileLocations()
    {
        String[] locations = DatabaseDescriptor.getAllDataFileLocations();
        for (int i = 0; i < locations.length; i++)
            locations[i] = getCanonicalPath(locations[i]);
        return locations;
    }

    public String getCommitLogLocation()
    {
        return getCanonicalPath(DatabaseDescriptor.getCommitLogLocation());
    }

    public String getSavedCachesLocation()
    {
        return getCanonicalPath(DatabaseDescriptor.getSavedCachesLocation());
    }

    private List<String> stringify(Iterable<InetAddress> endpoints)
    {
        List<String> stringEndpoints = new ArrayList<String>();
        for (InetAddress ep : endpoints)
        {
            stringEndpoints.add(ep.getHostAddress());
        }
        return stringEndpoints;
    }

    public int getCurrentGenerationNumber()
    {
        return Gossiper.instance.getCurrentGenerationNumber(FBUtilities.getBroadcastAddress());
    }

    public void forceTableCleanup(String tableName, String... columnFamilies) throws IOException, ExecutionException, InterruptedException
    {
        if (tableName.equals(Table.SYSTEM_TABLE))
            throw new RuntimeException("Cleanup of the system table is neither necessary nor wise");

        NodeId.OneShotRenewer nodeIdRenewer = new NodeId.OneShotRenewer();
        for (ColumnFamilyStore cfStore : getValidColumnFamilies(tableName, columnFamilies))
        {
            cfStore.forceCleanup(nodeIdRenewer);
        }
    }

    public void scrub(String tableName, String... columnFamilies) throws IOException, ExecutionException, InterruptedException
    {
        for (ColumnFamilyStore cfStore : getValidColumnFamilies(tableName, columnFamilies))
            cfStore.scrub();
    }

    public void upgradeSSTables(String tableName, String... columnFamilies) throws IOException, ExecutionException, InterruptedException
    {
        for (ColumnFamilyStore cfStore : getValidColumnFamilies(tableName, columnFamilies))
            cfStore.sstablesRewrite();
    }

    public void forceTableCompaction(String tableName, String... columnFamilies) throws IOException, ExecutionException, InterruptedException
    {
        for (ColumnFamilyStore cfStore : getValidColumnFamilies(tableName, columnFamilies))
        {
            cfStore.forceMajorCompaction();
        }
    }

    /**
     * Takes the snapshot for the given tables. A snapshot name must be specified.
     *
     * @param tag the tag given to the snapshot; may not be null or empty
     * @param tableNames the name of the tables to snapshot; empty means "all."
     */
    public void takeSnapshot(String tag, String... tableNames) throws IOException
    {
        if (tag == null || tag.equals(""))
            throw new IOException("You must supply a snapshot name.");

        Iterable<Table> tables;
        if (tableNames.length == 0)
        {
            tables = Table.all();
        }
        else
        {
            ArrayList<Table> t = new ArrayList<Table>(tableNames.length);
            for (String table : tableNames)
                t.add(getValidTable(table));
            tables = t;
        }

        // Do a check to see if this snapshot exists before we actually snapshot
        for (Table table : tables)
            if (table.snapshotExists(tag))
                throw new IOException("Snapshot " + tag + " already exists.");


        for (Table table : tables)
            table.snapshot(tag, null);
    }

    /**
     * Takes the snapshot of a specific column family. A snapshot name must be specified.
     *
     * @param tableName the keyspace which holds the specified column family
     * @param columnFamilyName the column family to snapshot
     * @param tag the tag given to the snapshot; may not be null or empty
     */
    public void takeColumnFamilySnapshot(String tableName, String columnFamilyName, String tag) throws IOException
    {
        if (tableName == null)
            throw new IOException("You must supply a table name");

        if (columnFamilyName == null)
            throw new IOException("You mus supply a column family name");

        if (tag == null || tag.equals(""))
            throw new IOException("You must supply a snapshot name.");

        Table table = getValidTable(tableName);
        if (table.snapshotExists(tag))
            throw new IOException("Snapshot " + tag + " already exists.");

        table.snapshot(tag, columnFamilyName);
    }

    private Table getValidTable(String tableName) throws IOException
    {
        if (!Schema.instance.getTables().contains(tableName))
        {
            throw new IOException("Table " + tableName + " does not exist");
        }
        return Table.open(tableName);
    }

    /**
     * Remove the snapshot with the given name from the given tables.
     * If no tag is specified we will remove all snapshots.
     */
    public void clearSnapshot(String tag, String... tableNames) throws IOException
    {
        if(tag == null)
            tag = "";

        Iterable<Table> tables;
        if (tableNames.length == 0)
        {
            tables = Table.all();
        }
        else
        {
            ArrayList<Table> tempTables = new ArrayList<Table>(tableNames.length);
            for(String table : tableNames)
                tempTables.add(getValidTable(table));
            tables = tempTables;
        }

        for (Table table : tables)
            table.clearSnapshot(tag);

        if (logger_.isDebugEnabled())
            logger_.debug("Cleared out snapshot directories");
    }

    public Iterable<ColumnFamilyStore> getValidColumnFamilies(String tableName, String... cfNames) throws IOException
    {
        Table table = getValidTable(tableName);

        if (cfNames.length == 0)
            // all stores are interesting
            return table.getColumnFamilyStores();

        // filter out interesting stores
        Set<ColumnFamilyStore> valid = new HashSet<ColumnFamilyStore>();
        for (String cfName : cfNames)
        {
            ColumnFamilyStore cfStore = table.getColumnFamilyStore(cfName);
            if (cfStore == null)
            {
                // this means there was a cf passed in that is not recognized in the keyspace. report it and continue.
                logger_.warn(String.format("Invalid column family specified: %s. Proceeding with others.", cfName));
                continue;
            }
            valid.add(cfStore);
        }
        return valid;
    }

    /**
     * Flush all memtables for a table and column families.
     * @param tableName
     * @param columnFamilies
     * @throws IOException
     */
    public void forceTableFlush(final String tableName, final String... columnFamilies)
                throws IOException, ExecutionException, InterruptedException
    {
        for (ColumnFamilyStore cfStore : getValidColumnFamilies(tableName, columnFamilies))
        {
            logger_.debug("Forcing flush on keyspace " + tableName + ", CF " + cfStore.getColumnFamilyName());
            cfStore.forceBlockingFlush();
        }
    }

    /**
     * Sends JMX notification to subscribers.
     *
     * @param type Message type
     * @param message Message itself
     * @param userObject Arbitrary object to attach to notification
     */
    public void sendNotification(String type, String message, Object userObject)
    {
        Notification jmxNotification = new Notification(type, jmxObjectName, notificationSerialNumber.incrementAndGet(), message);
        jmxNotification.setUserData(userObject);
        sendNotification(jmxNotification);
    }

    public int forceRepairAsync(final String tableName, final boolean isSequential, final boolean primaryRange, final String... columnFamilies)
    {
        final Collection<Range<Token>> ranges = primaryRange ? Collections.singletonList(getLocalPrimaryRange()) : getLocalRanges(tableName);
        return forceRepairAsync(tableName, isSequential, ranges, columnFamilies);
    }

    public int forceRepairAsync(final String tableName, final boolean isSequential, final Collection<Range<Token>> ranges, final String... columnFamilies)
    {
        if (Table.SYSTEM_TABLE.equals(tableName))
            return 0;

        final int cmd = nextRepairCommand.incrementAndGet();
        if (ranges.size() > 0)
        {
            new Thread(new WrappedRunnable()
            {
                protected void runMayThrow() throws Exception
                {
                    String message = String.format("Starting repair command #%d, repairing %d ranges for keyspace %s", cmd, ranges.size(), tableName);
                    logger_.info(message);
                    sendNotification("repair", message, new int[]{cmd, AntiEntropyService.Status.STARTED.ordinal()});

                    List<AntiEntropyService.RepairFuture> futures = new ArrayList<AntiEntropyService.RepairFuture>(ranges.size());
                    for (Range<Token> range : ranges)
                    {
                        AntiEntropyService.RepairFuture future;
                        try
                        {
                            future = forceTableRepair(range, tableName, isSequential, columnFamilies);
                        }
                        catch (IllegalArgumentException e)
                        {
                            message = String.format("Repair session failed with error: %s", e);
                            sendNotification("repair", message, new int[]{cmd, AntiEntropyService.Status.SESSION_FAILED.ordinal()});
                            continue;
                        }
                        if (future == null)
                            continue;
                        futures.add(future);
                        // wait for a session to be done with its differencing before starting the next one
                        try
                        {
                            future.session.differencingDone.await();
                        }
                        catch (InterruptedException e)
                        {
                            message = "Interrupted while waiting for the differencing of repair session " + future.session + " to be done. Repair may be imprecise.";
                            logger_.error(message, e);
                            sendNotification("repair", message, new int[]{cmd, AntiEntropyService.Status.SESSION_FAILED.ordinal()});
                        }
                    }
                    for (AntiEntropyService.RepairFuture future : futures)
                    {
                        try
                        {
                            future.get();
                            message = String.format("Repair session %s for range %s finished", future.session.getName(), future.session.getRange().toString());
                            sendNotification("repair", message, new int[]{cmd, AntiEntropyService.Status.SESSION_SUCCESS.ordinal()});
                        }
                        catch (ExecutionException e)
                        {
                            message = String.format("Repair session %s for range %s failed with error %s", future.session.getName(), future.session.getRange().toString(), e.getCause().getMessage());
                            sendNotification("repair", message, new int[]{cmd, AntiEntropyService.Status.SESSION_FAILED.ordinal()});
                        }
                        catch (Exception e)
                        {
                            message = String.format("Repair session %s for range %s failed with error %s", future.session.getName(), future.session.getRange().toString(), e.getMessage());
                            sendNotification("repair", message, new int[]{cmd, AntiEntropyService.Status.SESSION_FAILED.ordinal()});
                        }
                    }
                    sendNotification("repair", String.format("Repair command #%d finished", cmd), new int[]{cmd, AntiEntropyService.Status.FINISHED.ordinal()});
                }
            }).start();
        }
        return cmd;
    }

    public int forceRepairRangeAsync(String beginToken, String endToken, final String tableName, boolean isSequential, final String... columnFamilies)
    {
        Token parsedBeginToken = getPartitioner().getTokenFactory().fromString(beginToken);
        Token parsedEndToken = getPartitioner().getTokenFactory().fromString(endToken);

        logger_.info("starting user-requested repair of range ({}, {}] for keyspace {} and column families {}",
                new Object[] {parsedBeginToken, parsedEndToken, tableName, columnFamilies});
        return forceRepairAsync(tableName, isSequential, Collections.singleton(new Range<Token>(parsedBeginToken, parsedEndToken)), columnFamilies);
    }


    /**
     * Trigger proactive repair for a table and column families.
     * @param tableName
     * @param columnFamilies
     * @throws IOException
     */
    public void forceTableRepair(final String tableName, boolean isSequential, final String... columnFamilies) throws IOException
    {
        if (Table.SYSTEM_TABLE.equals(tableName))
            return;

        Collection<Range<Token>> ranges = getLocalRanges(tableName);
        int cmd = nextRepairCommand.incrementAndGet();
        logger_.info("Starting repair command #{}, repairing {} ranges.", cmd, ranges.size());

        List<AntiEntropyService.RepairFuture> futures = new ArrayList<AntiEntropyService.RepairFuture>(ranges.size());
        for (Range<Token> range : ranges)
        {
            AntiEntropyService.RepairFuture future = forceTableRepair(range, tableName, isSequential, columnFamilies);
            if (future == null)
                continue;
            futures.add(future);
            // wait for a session to be done with its differencing before starting the next one
            try
            {
                future.session.differencingDone.await();
            }
            catch (InterruptedException e)
            {
                logger_.error("Interrupted while waiting for the differencing of repair session " + future.session + " to be done. Repair may be imprecise.", e);
            }
        }

        boolean failedSession = false;

        // block until all repair sessions have completed
        for (AntiEntropyService.RepairFuture future : futures)
        {
            try
            {
                future.get();
            }
            catch (Exception e)
            {
                logger_.error("Repair session " + future.session.getName() + " failed.", e);
                failedSession = true;
            }
        }

        if (failedSession)
            throw new IOException("Repair command #" + cmd + ": some repair session(s) failed (see log for details).");
        else
            logger_.info("Repair command #{} completed successfully", cmd);
    }

    public void forceTableRepairPrimaryRange(final String tableName, boolean isSequential, final String... columnFamilies) throws IOException
    {
        if (Table.SYSTEM_TABLE.equals(tableName))
            return;

        AntiEntropyService.RepairFuture future = forceTableRepair(getLocalPrimaryRange(), tableName, isSequential, columnFamilies);
        if (future == null)
            return;
        try
        {
            future.get();
        }
        catch (Exception e)
        {
            logger_.error("Repair session " + future.session.getName() + " failed.", e);
            throw new IOException("Some repair session(s) failed (see log for details).");
        }
    }

    public void forceTableRepairRange(String beginToken, String endToken, final String tableName, boolean isSequential, final String... columnFamilies) throws IOException
    {
        if (Table.SYSTEM_TABLE.equals(tableName))
            return;

        Token parsedBeginToken = getPartitioner().getTokenFactory().fromString(beginToken);
        Token parsedEndToken = getPartitioner().getTokenFactory().fromString(endToken);

        logger_.info("starting user-requested repair of range ({}, {}] for keyspace {} and column families {}",
                     new Object[] {parsedBeginToken, parsedEndToken, tableName, columnFamilies});
        AntiEntropyService.RepairFuture future = forceTableRepair(new Range<Token>(parsedBeginToken, parsedEndToken), tableName, isSequential, columnFamilies);
        if (future == null)
            return;
        try
        {
            future.get();
        }
        catch (Exception e)
        {
            logger_.error("Repair session " + future.session.getName() + " failed.", e);
        }
    }

    public AntiEntropyService.RepairFuture forceTableRepair(final Range<Token> range, final String tableName, boolean isSequential, final String... columnFamilies) throws IOException
    {
        ArrayList<String> names = new ArrayList<String>();
        for (ColumnFamilyStore cfStore : getValidColumnFamilies(tableName, columnFamilies))
        {
            names.add(cfStore.getColumnFamilyName());
        }

        if (names.isEmpty())
        {
            logger_.info("No column family to repair for keyspace " + tableName);
            return null;
        }

        return AntiEntropyService.instance.submitRepairSession(range, tableName, isSequential, names.toArray(new String[names.size()]));
    }

    public void forceTerminateAllRepairSessions() {
        AntiEntropyService.instance.terminateSessions();
    }

    /* End of MBean interface methods */

    /**
     * This method returns the predecessor of the endpoint ep on the identifier
     * space.
     */
    InetAddress getPredecessor(InetAddress ep)
    {
        Token token = tokenMetadata_.getToken(ep);
        return tokenMetadata_.getEndpoint(tokenMetadata_.getPredecessor(token));
    }

    /*
     * This method returns the successor of the endpoint ep on the identifier
     * space.
     */
    public InetAddress getSuccessor(InetAddress ep)
    {
        Token token = tokenMetadata_.getToken(ep);
        return tokenMetadata_.getEndpoint(tokenMetadata_.getSuccessor(token));
    }

    /**
     * Get the primary range for the specified endpoint.
     * @param ep endpoint we are interested in.
     * @return range for the specified endpoint.
     */
    public Range<Token> getPrimaryRangeForEndpoint(InetAddress ep)
    {
        return tokenMetadata_.getPrimaryRangeFor(tokenMetadata_.getToken(ep));
    }

    /**
     * Get all ranges an endpoint is responsible for (by table)
     * @param ep endpoint we are interested in.
     * @return ranges for the specified endpoint.
     */
    Collection<Range<Token>> getRangesForEndpoint(String table, InetAddress ep)
    {
        return Table.open(table).getReplicationStrategy().getAddressRanges().get(ep);
    }

    /**
     * Get all ranges that span the ring given a set
     * of tokens. All ranges are in sorted order of
     * ranges.
     * @return ranges in sorted order
    */
    public List<Range<Token>> getAllRanges(List<Token> sortedTokens)
    {
        if (logger_.isDebugEnabled())
            logger_.debug("computing ranges for " + StringUtils.join(sortedTokens, ", "));

        if (sortedTokens.isEmpty())
            return Collections.emptyList();
        int size = sortedTokens.size();
        List<Range<Token>> ranges = new ArrayList<Range<Token>>(size + 1);
        for (int i = 1; i < size; ++i)
        {
            Range<Token> range = new Range<Token>(sortedTokens.get(i - 1), sortedTokens.get(i));
            ranges.add(range);
        }
        Range<Token> range = new Range<Token>(sortedTokens.get(size - 1), sortedTokens.get(0));
        ranges.add(range);

        return ranges;
    }

    /**
     * This method returns the N endpoints that are responsible for storing the
     * specified key i.e for replication.
     *
     * @param table keyspace name also known as table
     * @param cf Column family name
     * @param key key for which we need to find the endpoint
     * @return the endpoint responsible for this key
     */
    public List<InetAddress> getNaturalEndpoints(String table, String cf, String key)
    {
        CFMetaData cfMetaData = Schema.instance.getTableDefinition(table).cfMetaData().get(cf);
        return getNaturalEndpoints(table, getPartitioner().getToken(cfMetaData.getKeyValidator().fromString(key)));
    }

    public List<InetAddress> getNaturalEndpoints(String table, ByteBuffer key)
    {
        return getNaturalEndpoints(table, getPartitioner().getToken(key));
    }

    /**
     * This method returns the N endpoints that are responsible for storing the
     * specified key i.e for replication.
     *
     * @param table keyspace name also known as table
     * @param pos position for which we need to find the endpoint
     * @return the endpoint responsible for this token
     */
    public List<InetAddress> getNaturalEndpoints(String table, RingPosition pos)
    {
        return Table.open(table).getReplicationStrategy().getNaturalEndpoints(pos);
    }

    /**
     * This method attempts to return N endpoints that are responsible for storing the
     * specified key i.e for replication.
     *
     * @param table keyspace name also known as table
     * @param key key for which we need to find the endpoint
     * @return the endpoint responsible for this key
     */
    public List<InetAddress> getLiveNaturalEndpoints(String table, ByteBuffer key)
    {
        return getLiveNaturalEndpoints(table, getPartitioner().decorateKey(key));
    }

    public List<InetAddress> getLiveNaturalEndpoints(String table, RingPosition pos)
    {
        List<InetAddress> endpoints = Table.open(table).getReplicationStrategy().getNaturalEndpoints(pos);
        List<InetAddress> liveEps = new ArrayList<InetAddress>(endpoints.size());

        for (InetAddress endpoint : endpoints)
        {
            if (FailureDetector.instance.isAlive(endpoint))
                liveEps.add(endpoint);
        }

        return liveEps;
    }

    public void setLog4jLevel(String classQualifier, String rawLevel)
    {
        Level level = Level.toLevel(rawLevel);
        org.apache.log4j.Logger.getLogger(classQualifier).setLevel(level);
        logger_.info("set log level to " + level + " for classes under '" + classQualifier + "' (if the level doesn't look like '" + rawLevel + "' then log4j couldn't parse '" + rawLevel + "')");
    }

    /**
     * @return list of Token ranges (_not_ keys!) together with estimated key count,
     *      breaking up the data this node is responsible for into pieces of roughly keysPerSplit
     */
    public List<Pair<Range<Token>, Long>> getSplits(String table, String cfName, Range<Token> range, int keysPerSplit)
    {
        Table t = Table.open(table);
        ColumnFamilyStore cfs = t.getColumnFamilyStore(cfName);
        List<DecoratedKey> keys = keySamples(Collections.singleton(cfs), range);

        final long totalRowCountEstimate = (keys.size() + 1) * DatabaseDescriptor.getIndexInterval();

        // splitCount should be much smaller than number of key samples, to avoid huge sampling error
        final int minSamplesPerSplit = 4;
        final int maxSplitCount = keys.size() / minSamplesPerSplit + 1;
        final int splitCount = Math.max(1, Math.min(maxSplitCount, (int)(totalRowCountEstimate / keysPerSplit)));

        List<Token> tokens = keysToTokens(range, keys);
        return getSplits(tokens, splitCount);
    }

    private List<Pair<Range<Token>, Long>> getSplits(List<Token> tokens, int splitCount)
    {
        final double step = (double) (tokens.size() - 1) / splitCount;
        int prevIndex = 0;
        Token prevToken = tokens.get(0);
        List<Pair<Range<Token>, Long>> splits = Lists.newArrayListWithExpectedSize(splitCount);
        for (int i = 1; i <= splitCount; i++)
        {
            int index = (int) Math.round(i * step);
            Token token = tokens.get(index);
            long rowCountEstimate = (index - prevIndex) * DatabaseDescriptor.getIndexInterval();
            splits.add(Pair.create(new Range<Token>(prevToken, token), rowCountEstimate));
            prevIndex = index;
            prevToken = token;
        }
        return splits;
    }

    private List<Token> keysToTokens(Range<Token> range, List<DecoratedKey> keys)
    {
        List<Token> tokens = Lists.newArrayListWithExpectedSize(keys.size() + 2);
        tokens.add(range.left);
        for (DecoratedKey key : keys)
            tokens.add(key.token);
        tokens.add(range.right);
        return tokens;
    }

    private List<DecoratedKey> keySamples(Iterable<ColumnFamilyStore> cfses, Range<Token> range)
    {
        List<DecoratedKey> keys = new ArrayList<DecoratedKey>();
        for (ColumnFamilyStore cfs : cfses)
            Iterables.addAll(keys, cfs.keySamples(range));
        FBUtilities.sortSampledKeys(keys, range);
        return keys;
    }

    /** return a token to which if a node bootstraps it will get about 1/2 of this node's range */
    public Token getBootstrapToken()
    {
        Range<Token> range = getLocalPrimaryRange();

        List<DecoratedKey> keys = keySamples(ColumnFamilyStore.allUserDefined(), range);

        Token token;
        if (keys.size() < 3)
        {
            token = getPartitioner().midpoint(range.left, range.right);
            logger_.debug("Used midpoint to assign token " + token);
        }
        else
        {
            token = keys.get(keys.size() / 2).token;
            logger_.debug("Used key sample of size " + keys.size() + " to assign token " + token);
        }
        if (tokenMetadata_.getEndpoint(token) != null && tokenMetadata_.isMember(tokenMetadata_.getEndpoint(token)))
            throw new RuntimeException("Chose token " + token + " which is already in use by " + tokenMetadata_.getEndpoint(token) + " -- specify one manually with initial_token");
        // Hack to prevent giving nodes tokens with DELIMITER_STR in them (which is fine in a row key/token)
        if (token instanceof StringToken)
        {
            token = new StringToken(((String)token.token).replaceAll(VersionedValue.DELIMITER_STR, ""));
            if (tokenMetadata_.getNormalAndBootstrappingTokenToEndpointMap().containsKey(token))
                throw new RuntimeException("Unable to compute unique token for new node -- specify one manually with initial_token");
        }
        return token;
    }

    /**
     * Broadcast leaving status and update local tokenMetadata_ accordingly
     */
    private void startLeaving()
    {
        Gossiper.instance.addLocalApplicationState(ApplicationState.STATUS, valueFactory.leaving(getLocalToken()));
        tokenMetadata_.addLeavingEndpoint(FBUtilities.getBroadcastAddress());
        PendingRangeCalculatorService.instance.update();
    }

    public void decommission() throws InterruptedException
    {
        if (!tokenMetadata_.isMember(FBUtilities.getBroadcastAddress()))
            throw new UnsupportedOperationException("local node is not a member of the token ring yet");
        if (tokenMetadata_.cloneAfterAllLeft().sortedTokens().size() < 2)
            throw new UnsupportedOperationException("no other normal nodes in the ring; decommission would be pointless");
        PendingRangeCalculatorService.instance.blockUntilFinished();
        for (String table : Schema.instance.getNonSystemTables())
        {
            if (tokenMetadata_.getPendingRanges(table, FBUtilities.getBroadcastAddress()).size() > 0)
                throw new UnsupportedOperationException("data is currently moving to this node; unable to leave the ring");
        }

        if (logger_.isDebugEnabled())
            logger_.debug("DECOMMISSIONING");
        startLeaving();
        setMode(Mode.LEAVING, "sleeping " + RING_DELAY + " ms for pending range setup", true);
        Thread.sleep(RING_DELAY);

        Runnable finishLeaving = new Runnable()
        {
            public void run()
            {
                stopRPCServer();
                Gossiper.instance.stop();
                MessagingService.instance().shutdown();
                StageManager.shutdownNow();
                setMode(Mode.DECOMMISSIONED, true);
                // let op be responsible for killing the process
            }
        };
        unbootstrap(finishLeaving);
    }

    private void leaveRing()
    {
        SystemTable.setBootstrapState(SystemTable.BootstrapState.NEEDS_BOOTSTRAP);
        tokenMetadata_.removeEndpoint(FBUtilities.getBroadcastAddress());
        PendingRangeCalculatorService.instance.update();

        Gossiper.instance.addLocalApplicationState(ApplicationState.STATUS, valueFactory.left(getLocalToken(),Gossiper.computeExpireTime()));
        int delay = Math.max(RING_DELAY, Gossiper.intervalInMillis * 2);
        logger_.info("Announcing that I have left the ring for " + delay + "ms");
        try
        {
            Thread.sleep(delay);
        }
        catch (InterruptedException e)
        {
            throw new AssertionError(e);
        }
    }

    private void unbootstrap(final Runnable onFinish)
    {
        Map<String, Multimap<Range<Token>, InetAddress>> rangesToStream = new HashMap<String, Multimap<Range<Token>, InetAddress>>();

        for (final String table : Schema.instance.getNonSystemTables())
        {
            Multimap<Range<Token>, InetAddress> rangesMM = getChangedRangesForLeaving(table, FBUtilities.getBroadcastAddress());

            if (logger_.isDebugEnabled())
                logger_.debug("Ranges needing transfer are [" + StringUtils.join(rangesMM.keySet(), ",") + "]");

            rangesToStream.put(table, rangesMM);
        }

        setMode(Mode.LEAVING, "streaming data to other nodes", true);

        CountDownLatch latch = streamRanges(rangesToStream);

        // wait for the transfer runnables to signal the latch.
        logger_.debug("waiting for stream aks.");
        try
        {
            latch.await();
        }
        catch (InterruptedException e)
        {
            throw new RuntimeException(e);
        }
        logger_.debug("stream acks all received.");
        leaveRing();
        onFinish.run();
    }

    public void move(String newToken) throws IOException, InterruptedException, ConfigurationException
    {
        getPartitioner().getTokenFactory().validate(newToken);
        move(getPartitioner().getTokenFactory().fromString(newToken));
    }

    /**
     * move the node to new token or find a new token to boot to according to load
     *
     * @param newToken new token to boot to, or if null, find balanced token to boot to
     *
     * @throws IOException on any I/O operation error
     */
    private void move(Token newToken) throws IOException
    {
        if (newToken == null)
            throw new IOException("Can't move to the undefined (null) token.");

        if (tokenMetadata_.sortedTokens().contains(newToken))
            throw new IOException("target token " + newToken + " is already owned by another node.");

        // address of the current node
        InetAddress localAddress = FBUtilities.getBroadcastAddress();
        List<String> tablesToProcess = Schema.instance.getNonSystemTables();

        PendingRangeCalculatorService.instance.blockUntilFinished();
        // checking if data is moving to this node
        for (String table : tablesToProcess)
        {
            if (tokenMetadata_.getPendingRanges(table, localAddress).size() > 0)
                throw new UnsupportedOperationException("data is currently moving to this node; unable to leave the ring");
        }

        Gossiper.instance.addLocalApplicationState(ApplicationState.STATUS, valueFactory.moving(newToken));
        setMode(Mode.MOVING, String.format("Moving %s from %s to %s.", localAddress, getLocalToken(), newToken), true);

        IEndpointSnitch snitch = DatabaseDescriptor.getEndpointSnitch();

        Map<String, Multimap<InetAddress, Range<Token>>> rangesToFetch = new HashMap<String, Multimap<InetAddress, Range<Token>>>();
        Map<String, Multimap<Range<Token>, InetAddress>> rangesToStreamByTable = new HashMap<String, Multimap<Range<Token>, InetAddress>>();

        TokenMetadata tokenMetaClone = tokenMetadata_.cloneAfterAllSettled();

        // for each of the non system tables calculating new ranges
        // which current node will handle after move to the new token
        for (String table : tablesToProcess)
        {
            // replication strategy of the current keyspace (aka table)
            AbstractReplicationStrategy strategy = Table.open(table).getReplicationStrategy();

            // getting collection of the currently used ranges by this keyspace
            Collection<Range<Token>> currentRanges = getRangesForEndpoint(table, localAddress);
            // collection of ranges which this node will serve after move to the new token
            Collection<Range<Token>> updatedRanges = strategy.getPendingAddressRanges(tokenMetadata_, newToken, localAddress);

            // ring ranges and endpoints associated with them
            // this used to determine what nodes should we ping about range data
            Multimap<Range<Token>, InetAddress> rangeAddresses = strategy.getRangeAddresses(tokenMetadata_);

            // calculated parts of the ranges to request/stream from/to nodes in the ring
            Pair<Set<Range<Token>>, Set<Range<Token>>> rangesPerTable = calculateStreamAndFetchRanges(currentRanges, updatedRanges);

            /**
             * In this loop we are going through all ranges "to fetch" and determining
             * nodes in the ring responsible for data we are interested in
             */
            Multimap<Range<Token>, InetAddress> rangesToFetchWithPreferredEndpoints = ArrayListMultimap.create();
            for (Range<Token> toFetch : rangesPerTable.right)
            {
                for (Range<Token> range : rangeAddresses.keySet())
                {
                    if (range.contains(toFetch))
                    {
                        List<InetAddress> endpoints = snitch.getSortedListByProximity(localAddress, rangeAddresses.get(range));
                        // storing range and preferred endpoint set
                        rangesToFetchWithPreferredEndpoints.putAll(toFetch, endpoints);
                    }
                }
            }

            // calculating endpoints to stream current ranges to if needed
            // in some situations node will handle current ranges as part of the new ranges
            Multimap<Range<Token>, InetAddress> rangeWithEndpoints = HashMultimap.create();

            for (Range<Token> toStream : rangesPerTable.left)
            {
                Set<InetAddress> currentEndpoints = ImmutableSet.copyOf(strategy.calculateNaturalEndpoints(toStream.right, tokenMetadata_));
                Set<InetAddress> newEndpoints = ImmutableSet.copyOf(strategy.calculateNaturalEndpoints(toStream.right, tokenMetaClone));
                rangeWithEndpoints.putAll(toStream, Sets.difference(newEndpoints, currentEndpoints));
            }

            // associating table with range-to-endpoints map
            rangesToStreamByTable.put(table, rangeWithEndpoints);

            Multimap<InetAddress, Range<Token>> workMap = RangeStreamer.getWorkMap(rangesToFetchWithPreferredEndpoints);
            rangesToFetch.put(table, workMap);

            if (logger_.isDebugEnabled())
                logger_.debug("Table {}: work map {}.", table, workMap);
        }

        if (!rangesToStreamByTable.isEmpty() || !rangesToFetch.isEmpty())
        {
            setMode(Mode.MOVING, String.format("Sleeping %s ms before start streaming/fetching ranges", RING_DELAY), true);
            try
            {
                Thread.sleep(RING_DELAY);
            }
            catch (InterruptedException e)
            {
                throw new RuntimeException("Sleep interrupted " + e.getMessage());
            }

            setMode(Mode.MOVING, "fetching new ranges and streaming old ranges", true);
            if (logger_.isDebugEnabled())
                logger_.debug("[Move->STREAMING] Work Map: " + rangesToStreamByTable);

            CountDownLatch streamLatch = streamRanges(rangesToStreamByTable);

            if (logger_.isDebugEnabled())
                logger_.debug("[Move->FETCHING] Work Map: " + rangesToFetch);

            CountDownLatch fetchLatch = requestRanges(rangesToFetch);

            try
            {
                streamLatch.await();
                fetchLatch.await();
            }
            catch (InterruptedException e)
            {
                throw new RuntimeException("Interrupted latch while waiting for stream/fetch ranges to finish: " + e.getMessage());
            }
        }

        setToken(newToken); // setting new token as we have everything settled

        if (logger_.isDebugEnabled())
            logger_.debug("Successfully moved to new token {}", getLocalToken());
    }

    /**
     * Get the status of a token removal.
     */
    public String getRemovalStatus()
    {
        if (removingNode == null) {
            return "No token removals in process.";
        }
        return String.format("Removing token (%s). Waiting for replication confirmation from [%s].",
                             tokenMetadata_.getToken(removingNode),
                             StringUtils.join(replicatingNodes, ","));
    }

    /**
     * Force a remove operation to complete. This may be necessary if a remove operation
     * blocks forever due to node/stream failure. removeToken() must be called
     * first, this is a last resort measure.  No further attempt will be made to restore replicas.
     */
    public void forceRemoveCompletion()
    {
        if (!replicatingNodes.isEmpty()  || !tokenMetadata_.getLeavingEndpoints().isEmpty())
        {
            logger_.warn("Removal not confirmed for for " + StringUtils.join(this.replicatingNodes, ","));
            for (InetAddress endpoint : tokenMetadata_.getLeavingEndpoints())
            {
                Token token = tokenMetadata_.getToken(endpoint);
                Gossiper.instance.advertiseTokenRemoved(endpoint, token);
                excise(token, endpoint);
            }
            replicatingNodes.clear();
            removingNode = null;
        }
        else
        {
            throw new UnsupportedOperationException("No tokens to force removal on, call 'removetoken' first");
        }
    }

    /**
     * Remove a node that has died, attempting to restore the replica count.
     * If the node is alive, decommission should be attempted.  If decommission
     * fails, then removeToken should be called.  If we fail while trying to
     * restore the replica count, finally forceRemoveCompleteion should be
     * called to forcibly remove the node without regard to replica count.
     *
     * @param tokenString token for the node
     */
    public void removeToken(String tokenString)
    {
        InetAddress myAddress = FBUtilities.getBroadcastAddress();
        Token localToken = tokenMetadata_.getToken(myAddress);
        Token token = getPartitioner().getTokenFactory().fromString(tokenString);
        InetAddress endpoint = tokenMetadata_.getEndpoint(token);

        if (endpoint == null)
            throw new UnsupportedOperationException("Token not found.");

        if (endpoint.equals(myAddress))
             throw new UnsupportedOperationException("Cannot remove node's own token");

        if (Gossiper.instance.getLiveMembers().contains(endpoint))
            throw new UnsupportedOperationException("Node " + endpoint + " is alive and owns this token. Use decommission command to remove it from the ring");

        // A leaving endpoint that is dead is already being removed.
        if (tokenMetadata_.isLeaving(endpoint))
            logger_.warn("Node " + endpoint + " is already being removed, continuing removal anyway");

        if (!replicatingNodes.isEmpty())
            throw new UnsupportedOperationException("This node is already processing a removal. Wait for it to complete, or use 'removetoken force' if this has failed.");

        // Find the endpoints that are going to become responsible for data
        for (String table : Schema.instance.getNonSystemTables())
        {
            // if the replication factor is 1 the data is lost so we shouldn't wait for confirmation
            if (Table.open(table).getReplicationStrategy().getReplicationFactor() == 1)
                continue;

            // get all ranges that change ownership (that is, a node needs
            // to take responsibility for new range)
            Multimap<Range<Token>, InetAddress> changedRanges = getChangedRangesForLeaving(table, endpoint);
            IFailureDetector failureDetector = FailureDetector.instance;
            for (InetAddress ep : changedRanges.values())
            {
                if (failureDetector.isAlive(ep))
                    replicatingNodes.add(ep);
                else
                    logger_.warn("Endpoint " + ep + " is down and will not receive data for re-replication of " + endpoint);
            }
        }
        removingNode = endpoint;

        tokenMetadata_.addLeavingEndpoint(endpoint);
        PendingRangeCalculatorService.instance.update();
        // the gossiper will handle spoofing this node's state to REMOVING_TOKEN for us
        // we add our own token so other nodes to let us know when they're done
        Gossiper.instance.advertiseRemoving(endpoint, token, localToken);

        // kick off streaming commands
        restoreReplicaCount(endpoint, myAddress);

        // wait for ReplicationFinishedVerbHandler to signal we're done
        while (!replicatingNodes.isEmpty())
        {
            try
            {
                Thread.sleep(100);
            }
            catch (InterruptedException e)
            {
                throw new AssertionError(e);
            }
        }

        excise(token, endpoint);

        // gossiper will indicate the token has left
        Gossiper.instance.advertiseTokenRemoved(endpoint, token);

        replicatingNodes.clear();
        removingNode = null;
    }

    public void confirmReplication(InetAddress node)
    {
        // replicatingNodes can be empty in the case where this node used to be a removal coordinator,
        // but restarted before all 'replication finished' messages arrived. In that case, we'll
        // still go ahead and acknowledge it.
        if (!replicatingNodes.isEmpty())
        {
            replicatingNodes.remove(node);
        }
        else
        {
            logger_.info("Received unexpected REPLICATION_FINISHED message from " + node
                         + ". Was this node recently a removal coordinator?");
        }
    }

    public boolean isClientMode()
    {
        return isClientMode;
    }

    public synchronized void requestGC()
    {
        if (hasUnreclaimedSpace())
        {
            logger_.info("requesting GC to free disk space");
            System.gc();
            try
            {
                Thread.sleep(1000);
            }
            catch (InterruptedException e)
            {
                throw new AssertionError(e);
            }
        }
    }

    private boolean hasUnreclaimedSpace()
    {
        for (ColumnFamilyStore cfs : ColumnFamilyStore.all())
        {
            if (cfs.hasUnreclaimedSpace())
                return true;
        }
        return false;
    }

    public String getOperationMode()
    {
        return operationMode.toString();
    }

    public String getDrainProgress()
    {
        return String.format("Drained %s/%s ColumnFamilies", remainingCFs, totalCFs);
    }

    /**
     * Shuts node off to writes, empties memtables and the commit log.
     * There are two differences between drain and the normal shutdown hook:
     * - Drain waits for in-progress streaming to complete
     * - Drain flushes *all* columnfamilies (shutdown hook only flushes non-durable CFs)
     */
    public synchronized void drain() throws IOException, InterruptedException, ExecutionException
    {
        ExecutorService mutationStage = StageManager.getStage(Stage.MUTATION);
        if (mutationStage.isTerminated())
        {
            logger_.warn("Cannot drain node (did it already happen?)");
            return;
        }
        setMode(Mode.DRAINING, "starting drain process", true);
        stopRPCServer();
        optionalTasks.shutdown();
        Gossiper.instance.stop();

        setMode(Mode.DRAINING, "shutting down MessageService", false);
        MessagingService.instance().shutdown();
        setMode(Mode.DRAINING, "waiting for streaming", false);
        MessagingService.instance().waitForStreaming();

        setMode(Mode.DRAINING, "clearing mutation stage", false);
        mutationStage.shutdown();
        mutationStage.awaitTermination(3600, TimeUnit.SECONDS);

        StorageProxy.instance.verifyNoHintsInProgress();

        setMode(Mode.DRAINING, "flushing column families", false);
        List<ColumnFamilyStore> cfses = new ArrayList<ColumnFamilyStore>();
        for (String tableName : Schema.instance.getNonSystemTables())
        {
            Table table = Table.open(tableName);
            cfses.addAll(table.getColumnFamilyStores());
        }
        totalCFs = remainingCFs = cfses.size();
        for (ColumnFamilyStore cfs : cfses)
        {
            cfs.forceBlockingFlush();
            remainingCFs--;
        }

        ColumnFamilyStore.postFlushExecutor.shutdown();
        ColumnFamilyStore.postFlushExecutor.awaitTermination(60, TimeUnit.SECONDS);

        CommitLog.instance.shutdownBlocking();

        // wait for miscellaneous tasks like sstable and commitlog segment deletion
        tasks.shutdown();
        if (!tasks.awaitTermination(1, TimeUnit.MINUTES))
            logger_.warn("Miscellaneous task executor still busy after one minute; proceeding with shutdown");

        setMode(Mode.DRAINED, true);
    }

    // Never ever do this at home. Used by tests.
    IPartitioner setPartitionerUnsafe(IPartitioner newPartitioner)
    {
        IPartitioner oldPartitioner = DatabaseDescriptor.getPartitioner();
        DatabaseDescriptor.setPartitioner(newPartitioner);
        valueFactory = new VersionedValue.VersionedValueFactory(getPartitioner());
        return oldPartitioner;
    }

    TokenMetadata setTokenMetadataUnsafe(TokenMetadata tmd)
    {
        TokenMetadata old = tokenMetadata_;
        tokenMetadata_ = tmd;
        return old;
    }

    public void truncate(String keyspace, String columnFamily) throws UnavailableException, TimeoutException, IOException
    {
        StorageProxy.truncateBlocking(keyspace, columnFamily);
    }

    public Map<String, Float> getOwnership()
    {
        List<Token> sortedTokens = new ArrayList<Token>(tokenMetadata_.getTokenToEndpointMapForReading().keySet());
        Collections.sort(sortedTokens);
        Map<Token, Float> token_map = getPartitioner().describeOwnership(sortedTokens);
        Map<String, Float> string_map = new HashMap<String, Float>();
        for(Map.Entry<Token, Float> entry : token_map.entrySet())
        {
            string_map.put(entry.getKey().toString(), entry.getValue());
        }
        return string_map;
    }

    public Map<String, Float> effectiveOwnership(String keyspace) throws ConfigurationException
    {
        Map<String, Float> effective = Maps.newHashMap();
        if (Schema.instance.getNonSystemTables().size() <= 0)
            throw new ConfigurationException("Couldn't find any Non System Keyspaces to infer replication topology");
        if (keyspace == null && !hasSameReplication(Schema.instance.getNonSystemTables()))
            throw new ConfigurationException("Non System keyspaces doesnt have the same topology");

        if (keyspace == null)
            keyspace = Schema.instance.getNonSystemTables().get(0);

        List<Token> sortedTokens = new ArrayList<Token>(tokenMetadata_.getTokenToEndpointMapForReading().keySet());
        Collections.sort(sortedTokens);
        Map<Token, Float> ownership = getPartitioner().describeOwnership(sortedTokens);

        for (Entry<InetAddress, Collection<Range<Token>>> ranges : constructEndpointToRangeMap(keyspace).entrySet())
        {
            Token token = tokenMetadata_.getToken(ranges.getKey());
            for (Range<Token> range: ranges.getValue())
            {
                float value = effective.get(token.toString()) == null ? 0.0F : effective.get(token.toString());
                effective.put(token.toString(), value + ownership.get(range.left));
            }
        }
        return effective;
    }

    private boolean hasSameReplication(List<String> list)
    {
        if (list.isEmpty())
            return false;

        for (int i = 0; i < list.size() -1; i++)
        {
            KSMetaData ksm1 = Schema.instance.getKSMetaData(list.get(i));
            KSMetaData ksm2 = Schema.instance.getKSMetaData(list.get(i + 1));
            if (!ksm1.strategyClass.equals(ksm2.strategyClass) ||
                    !Iterators.elementsEqual(ksm1.strategyOptions.entrySet().iterator(),
                                             ksm2.strategyOptions.entrySet().iterator()))
                return false;
        }
        return true;
    }

    public List<String> getKeyspaces()
    {
        List<String> tableslist = new ArrayList<String>(Schema.instance.getTables());
        return Collections.unmodifiableList(tableslist);
    }

    public void updateSnitch(String epSnitchClassName, Boolean dynamic, Integer dynamicUpdateInterval, Integer dynamicResetInterval, Double dynamicBadnessThreshold) throws ConfigurationException
    {
        IEndpointSnitch oldSnitch = DatabaseDescriptor.getEndpointSnitch();

        // new snitch registers mbean during construction
        IEndpointSnitch newSnitch = FBUtilities.construct(epSnitchClassName, "snitch");
        if (dynamic)
        {
            DatabaseDescriptor.setDynamicUpdateInterval(dynamicUpdateInterval);
            DatabaseDescriptor.setDynamicResetInterval(dynamicResetInterval);
            DatabaseDescriptor.setDynamicBadnessThreshold(dynamicBadnessThreshold);
            newSnitch = new DynamicEndpointSnitch(newSnitch);
        }

        // point snitch references to the new instance
        DatabaseDescriptor.setEndpointSnitch(newSnitch);
        for (String ks : Schema.instance.getTables())
        {
            Table.open(ks).getReplicationStrategy().snitch = newSnitch;
        }

        if (oldSnitch instanceof DynamicEndpointSnitch)
            ((DynamicEndpointSnitch)oldSnitch).unregisterMBean();
    }

    /**
     * Flushes the two largest memtables by ops and by throughput
     */
    public void flushLargestMemtables()
    {
        ColumnFamilyStore largest = null;
        for (ColumnFamilyStore cfs : ColumnFamilyStore.all())
        {
            long total = cfs.getTotalMemtableLiveSize();

            if (total > 0 && (largest == null || total > largest.getTotalMemtableLiveSize()))
            {
                logger_.debug(total + " estimated memtable size for " + cfs);
                largest = cfs;
            }
        }
        if (largest == null)
        {
            logger_.info("Unable to reduce heap usage since there are no dirty column families");
            return;
        }

        logger_.warn("Flushing " + largest + " to relieve memory pressure");
        largest.forceFlush();
    }

    /**
     * Seed data to the endpoints that will be responsible for it at the future
     *
     * @param rangesToStreamByTable tables and data ranges with endpoints included for each
     * @return latch to count down
     */
    private CountDownLatch streamRanges(final Map<String, Multimap<Range<Token>, InetAddress>> rangesToStreamByTable)
    {
        final CountDownLatch latch = new CountDownLatch(rangesToStreamByTable.keySet().size());
        for (Map.Entry<String, Multimap<Range<Token>, InetAddress>> entry : rangesToStreamByTable.entrySet())
        {
            Multimap<Range<Token>, InetAddress> rangesWithEndpoints = entry.getValue();

            if (rangesWithEndpoints.isEmpty())
            {
                latch.countDown();
                continue;
            }

            final String table = entry.getKey();

            final Set<Map.Entry<Range<Token>, InetAddress>> pending = new HashSet<Map.Entry<Range<Token>, InetAddress>>(rangesWithEndpoints.entries());

            for (final Map.Entry<Range<Token>, InetAddress> endPointEntry : rangesWithEndpoints.entries())
            {
                final Range<Token> range = endPointEntry.getKey();
                final InetAddress newEndpoint = endPointEntry.getValue();

                final IStreamCallback callback = new IStreamCallback()
                {
                    public void onSuccess()
                    {
                        synchronized (pending)
                        {
                            pending.remove(endPointEntry);

                            if (pending.isEmpty())
                                latch.countDown();
                        }
                    }

                    public void onFailure()
                    {
                        logger_.warn("Streaming to " + endPointEntry + " failed");
                        onSuccess(); // calling onSuccess for latch countdown
                    }
                };

                StageManager.getStage(Stage.STREAM).execute(new Runnable()
                {
                    public void run()
                    {
                        // TODO each call to transferRanges re-flushes, this is potentially a lot of waste
                        StreamOut.transferRanges(newEndpoint, Table.open(table), Arrays.asList(range), callback, OperationType.UNBOOTSTRAP);
                    }
                });
            }
        }
        return latch;
    }

    /**
     * Used to request ranges from endpoints in the ring (will block until all data is fetched and ready)
     * @param ranges ranges to fetch as map of the preferred address and range collection
     * @return latch to count down
     */
    private CountDownLatch requestRanges(final Map<String, Multimap<InetAddress, Range<Token>>> ranges)
    {
        final CountDownLatch latch = new CountDownLatch(ranges.keySet().size());
        for (Map.Entry<String, Multimap<InetAddress, Range<Token>>> entry : ranges.entrySet())
        {
            Multimap<InetAddress, Range<Token>> endpointWithRanges = entry.getValue();

            if (endpointWithRanges.isEmpty())
            {
                latch.countDown();
                continue;
            }

            final String table = entry.getKey();
            final Set<InetAddress> pending = new HashSet<InetAddress>(endpointWithRanges.keySet());

            // Send messages to respective folks to stream data over to me
            for (final InetAddress source: endpointWithRanges.keySet())
            {
                Collection<Range<Token>> toFetch = endpointWithRanges.get(source);

                final IStreamCallback callback = new IStreamCallback()
                {
                    public void onSuccess()
                    {
                        pending.remove(source);

                        if (pending.isEmpty())
                            latch.countDown();
                    }

                    public void onFailure()
                    {
                        logger_.warn("Streaming from " + source + " failed");
                        onSuccess(); // calling onSuccess for latch countdown
                    }
                };

                if (logger_.isDebugEnabled())
                    logger_.debug("Requesting from " + source + " ranges " + StringUtils.join(toFetch, ", "));

                // sending actual request
                StreamIn.requestRanges(source, table, toFetch, callback, OperationType.BOOTSTRAP);
            }
        }
        return latch;
    }

    /**
     * Calculate pair of ranges to stream/fetch for given two range collections
     * (current ranges for table and ranges after move to new token)
     *
     * @param current collection of the ranges by current token
     * @param updated collection of the ranges after token is changed
     * @return pair of ranges to stream/fetch for given current and updated range collections
     */
    public Pair<Set<Range<Token>>, Set<Range<Token>>> calculateStreamAndFetchRanges(Collection<Range<Token>> current, Collection<Range<Token>> updated)
    {
        Set<Range<Token>> toStream = new HashSet<Range<Token>>();
        Set<Range<Token>> toFetch  = new HashSet<Range<Token>>();


        for (Range r1 : current)
        {
            boolean intersect = false;
            for (Range r2 : updated)
            {
                if (r1.intersects(r2))
                {
                    // adding difference ranges to fetch from a ring
                    toStream.addAll(r1.subtract(r2));
                    intersect = true;
                    break;
                }
            }
            if (!intersect)
            {
                toStream.add(r1); // should seed whole old range
            }
        }

        for (Range r2 : updated)
        {
            boolean intersect = false;
            for (Range r1 : current)
            {
                if (r2.intersects(r1))
                {
                    // adding difference ranges to fetch from a ring
                    toFetch.addAll(r2.subtract(r1));
                    intersect = true;
                    break;
                }
            }
            if (!intersect)
            {
                toFetch.add(r2); // should fetch whole old range
            }
        }

        return new Pair<Set<Range<Token>>, Set<Range<Token>>>(toStream, toFetch);
    }

    public void bulkLoad(String directory)
    {
        File dir = new File(directory);

        if (!dir.exists() || !dir.isDirectory())
            throw new IllegalArgumentException("Invalid directory " + directory);

        SSTableLoader.Client client = new SSTableLoader.Client()
        {
            @Override
            public void init(String keyspace)
            {
                try
                {
                    setPartitioner(DatabaseDescriptor.getPartitioner());
                    for (Map.Entry<Range<Token>, List<InetAddress>> entry : StorageService.instance.getRangeToAddressMap(keyspace).entrySet())
                    {
                        Range<Token> range = entry.getKey();
                        for (InetAddress endpoint : entry.getValue())
                            addRangeForEndpoint(range, endpoint);
                    }
                }
                catch (Exception e)
                {
                    throw new RuntimeException(e);
                }
            }

            @Override
            public boolean validateColumnFamily(String keyspace, String cfName)
            {
                return Schema.instance.getCFMetaData(keyspace, cfName) != null;
            }
        };

        SSTableLoader loader = new SSTableLoader(dir, client, new OutputHandler.LogOutput());
        try
        {
            loader.stream().get();
        }
        catch (Exception e)
        {
            throw new RuntimeException(e);
        }
    }

    public int getExceptionCount()
    {
        return AbstractCassandraDaemon.exceptions.get();
    }

    public void rescheduleFailedDeletions()
    {
        SSTableDeletingTask.rescheduleFailedTasks();
    }

    /**
     * #{@inheritDoc}
     */
    public void loadNewSSTables(String ksName, String cfName)
    {
        ColumnFamilyStore.loadNewSSTables(ksName, cfName);
    }

    /**
     * #{@inheritDoc}
     */
    public List<String> sampleKeyRange() // do not rename to getter - see CASSANDRA-4452 for details
    {
        List<DecoratedKey> keys = keySamples(ColumnFamilyStore.allUserDefined(), getLocalPrimaryRange());

        List<String> sampledKeys = new ArrayList<String>(keys.size());
        for (DecoratedKey key : keys)
            sampledKeys.add(key.getToken().toString());
        return sampledKeys;
    }

    public void rebuildSecondaryIndex(String ksName, String cfName, String... idxNames)
    {
        ColumnFamilyStore.rebuildSecondaryIndex(ksName, cfName, idxNames);
    }

    public void resetLocalSchema() throws IOException
    {
        MigrationManager.resetLocalSchema();
    }
}
