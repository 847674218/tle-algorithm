/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 * <p/>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p/>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.cassandra.service;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOError;
import java.io.IOException;
import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.util.*;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.ArrayList;
import java.util.Collection;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import java.lang.management.ManagementFactory;
import java.lang.management.RuntimeMXBean;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.apache.cassandra.concurrent.Stage;
import org.apache.cassandra.concurrent.StageManager;
import org.apache.cassandra.config.CFMetaData;
import org.apache.cassandra.config.ConfigurationException;
import org.apache.cassandra.config.KSMetaData;
import org.apache.cassandra.config.Schema;
import org.apache.cassandra.db.*;
import org.apache.cassandra.db.filter.QueryFilter;
import org.apache.cassandra.db.filter.QueryPath;
import org.apache.cassandra.gms.*;
import org.apache.cassandra.io.util.FastByteArrayInputStream;
import org.apache.cassandra.io.util.FastByteArrayOutputStream;
import org.apache.cassandra.net.IAsyncCallback;
import org.apache.cassandra.net.Message;
import org.apache.cassandra.net.MessagingService;
import org.apache.cassandra.utils.ByteBufferUtil;
import org.apache.cassandra.utils.FBUtilities;
import org.apache.cassandra.utils.UUIDGen;
import org.apache.cassandra.utils.WrappedRunnable;
import org.apache.commons.lang.ArrayUtils;

public class MigrationManager implements IEndpointStateChangeSubscriber
{
    private static final Logger logger = LoggerFactory.getLogger(MigrationManager.class);

    // try that many times to send migration request to the node before giving up
    private static final int MIGRATION_REQUEST_RETRIES = 3;
    private static final ByteBuffer LAST_MIGRATION_KEY = ByteBufferUtil.bytes("Last Migration");

    private static final RuntimeMXBean runtimeMXBean = ManagementFactory.getRuntimeMXBean();

    public static final int MIGRATION_DELAY_IN_MS = 60000;

    public void onJoin(InetAddress endpoint, EndpointState epState)
    {}

    public void onChange(InetAddress endpoint, ApplicationState state, VersionedValue value)
    {
        if (state != ApplicationState.SCHEMA || endpoint.equals(FBUtilities.getBroadcastAddress()))
            return;

        maybeScheduleSchemaPull(UUID.fromString(value.value), endpoint);
    }

    public void onAlive(InetAddress endpoint, EndpointState state)
    {
        VersionedValue value = state.getApplicationState(ApplicationState.SCHEMA);

        if (value != null)
            maybeScheduleSchemaPull(UUID.fromString(value.value), endpoint);
    }

    public void onDead(InetAddress endpoint, EndpointState state)
    {}

    public void onRestart(InetAddress endpoint, EndpointState state)
    {}

    public void onRemove(InetAddress endpoint)
    {}

    /**
     * If versions differ this node sends request with local migration list to the endpoint
     * and expecting to receive a list of migrations to apply locally.
     */
    private static void maybeScheduleSchemaPull(final UUID theirVersion, final InetAddress endpoint)
    {
        // Can't request migrations from nodes with versions younger than 1.1.7
        if (Gossiper.instance.getVersion(endpoint) < MessagingService.VERSION_117)
            return;

        // Don't request migrations from nodes with versions that are >= 1.2
        if (Gossiper.instance.getVersion(endpoint) >= MessagingService.VERSION_12)
            return;

        if (Schema.instance.getVersion().equals(theirVersion))
            return;

        if (Schema.emptyVersion.equals(Schema.instance.getVersion()) || runtimeMXBean.getUptime() < MIGRATION_DELAY_IN_MS)
        {
            // If we think we may be bootstrapping or have recently started, submit MigrationTask immediately
            submitMigrationTask(endpoint);
        }
        else
        {
            // Include a delay to make sure we have a chance to apply any changes being
            // pushed out simultaneously. See CASSANDRA-5025
            Runnable runnable = new Runnable()
            {
                public void run()
                {
                    // grab the latest version of the schema since it may have changed again since the initial scheduling
                    VersionedValue value = Gossiper.instance.getEndpointStateForEndpoint(endpoint).getApplicationState(ApplicationState.SCHEMA);
                    UUID currentVersion = UUID.fromString(value.value);
                    if (Schema.instance.getVersion().equals(currentVersion))
                        return;

                    submitMigrationTask(endpoint);
                }
            };
            StorageService.optionalTasks.schedule(runnable, MIGRATION_DELAY_IN_MS, TimeUnit.MILLISECONDS);
        }
    }

    private static void submitMigrationTask(InetAddress endpoint)
    {
        /*
         * Do not de-ref the future because that causes distributed deadlock (CASSANDRA-3832) because we are
         * running in the gossip stage.
         */
        StageManager.getStage(Stage.MIGRATION).submit(new MigrationTask(endpoint));
    }

    public static boolean isReadyForBootstrap()
    {
        return StageManager.getStage(Stage.MIGRATION).getActiveCount() == 0;
    }

    public static void announceNewKeyspace(KSMetaData ksm) throws ConfigurationException
    {
        ksm.validate();

        if (Schema.instance.getTableDefinition(ksm.name) != null)
            throw new ConfigurationException(String.format("Cannot add already existing keyspace '%s'.", ksm.name));

        logger.info(String.format("Create new Keyspace: %s", ksm));
        announce(ksm.toSchema(FBUtilities.timestampMicros()));
    }

    public static void announceNewColumnFamily(CFMetaData cfm) throws ConfigurationException
    {
        cfm.validate();

        KSMetaData ksm = Schema.instance.getTableDefinition(cfm.ksName);
        if (ksm == null)
            throw new ConfigurationException(String.format("Cannot add column family '%s' to non existing keyspace '%s'.", cfm.cfName, cfm.ksName));
        else if (ksm.cfMetaData().containsKey(cfm.cfName))
            throw new ConfigurationException(String.format("Cannot add already existing column family '%s' to keyspace '%s'.", cfm.cfName, cfm.ksName));

        logger.info(String.format("Create new ColumnFamily: %s", cfm));
        announce(cfm.toSchema(FBUtilities.timestampMicros()));
    }

    public static void announceKeyspaceUpdate(KSMetaData ksm) throws ConfigurationException
    {
        ksm.validate();

        KSMetaData oldKsm = Schema.instance.getKSMetaData(ksm.name);
        if (oldKsm == null)
            throw new ConfigurationException(String.format("Cannot update non existing keyspace '%s'.", ksm.name));

        logger.info(String.format("Update Keyspace '%s' From %s To %s", ksm.name, oldKsm, ksm));
        announce(oldKsm.toSchemaUpdate(ksm, FBUtilities.timestampMicros()));
    }

    public static void announceColumnFamilyUpdate(CFMetaData cfm) throws ConfigurationException
    {
        cfm.validate();

        CFMetaData oldCfm = Schema.instance.getCFMetaData(cfm.ksName, cfm.cfName);
        if (oldCfm == null)
            throw new ConfigurationException(String.format("Cannot update non existing column family '%s' in keyspace '%s'.", cfm.cfName, cfm.ksName));

        logger.info(String.format("Update ColumnFamily '%s/%s' From %s To %s", cfm.ksName, cfm.cfName, oldCfm, cfm));
        announce(oldCfm.toSchemaUpdate(cfm, FBUtilities.timestampMicros()));
    }

    public static void announceKeyspaceDrop(String ksName) throws ConfigurationException
    {
        KSMetaData oldKsm = Schema.instance.getKSMetaData(ksName);
        if (oldKsm == null)
            throw new ConfigurationException(String.format("Cannot drop non existing keyspace '%s'.", ksName));

        logger.info(String.format("Drop Keyspace '%s'", oldKsm.name));
        announce(oldKsm.dropFromSchema(FBUtilities.timestampMicros()));
    }

    public static void announceColumnFamilyDrop(String ksName, String cfName) throws ConfigurationException
    {
        CFMetaData oldCfm = Schema.instance.getCFMetaData(ksName, cfName);
        if (oldCfm == null)
            throw new ConfigurationException(String.format("Cannot drop non existing column family '%s' in keyspace '%s'.", cfName, ksName));

        logger.info(String.format("Drop ColumnFamily '%s/%s'", oldCfm.ksName, oldCfm.cfName));
        announce(oldCfm.dropFromSchema(FBUtilities.timestampMicros()));
    }

    /**
     * actively announce a new version to active hosts via rpc
     * @param schema The schema mutation to be applied
     */
    private static void announce(RowMutation schema)
    {
        FBUtilities.waitOnFuture(announce(Collections.singletonList(schema)));
    }

    private static void pushSchemaMutation(InetAddress endpoint, Collection<RowMutation> schema)
    {
        try
        {
            Message msg = makeMigrationMessage(schema, Gossiper.instance.getVersion(endpoint));
            MessagingService.instance().sendOneWay(msg, endpoint);
        }
        catch (IOException ex)
        {
            throw new IOError(ex);
        }
    }

    // Returns a future on the local application of the schema
    private static Future<?> announce(final Collection<RowMutation> schema)
    {
        Future<?> f = StageManager.getStage(Stage.MIGRATION).submit(new WrappedRunnable()
        {
            protected void runMayThrow() throws IOException, ConfigurationException
            {
                DefsTable.mergeSchema(schema);
            }
        });

        for (InetAddress endpoint : Gossiper.instance.getLiveMembers())
        {
            if (endpoint.equals(FBUtilities.getBroadcastAddress()))
                continue; // we've delt with localhost already

            // don't send migrations to the nodes with the versions older than < 1.1
            if (Gossiper.instance.getVersion(endpoint) < MessagingService.VERSION_11)
                continue;

            pushSchemaMutation(endpoint, schema);
        }
        return f;
    }

    /**
     * Announce my version passively over gossip.
     * Used to notify nodes as they arrive in the cluster.
     *
     * @param version The schema version to announce
     */
    public static void passiveAnnounce(UUID version)
    {
        Gossiper.instance.addLocalApplicationState(ApplicationState.SCHEMA, StorageService.instance.valueFactory.schema(version));
        logger.debug("Gossiping my schema version " + version);
    }

    /**
     * Serialize given row mutations into raw bytes and make a migration message
     * (other half of transformation is in DefinitionsUpdateResponseVerbHandler.)
     *
     * @param schema The row mutations to send to remote nodes
     * @param version The version to use for message
     *
     * @return Serialized migration containing schema mutations
     *
     * @throws IOException on failed serialization
     */
    private static Message makeMigrationMessage(Collection<RowMutation> schema, int version) throws IOException
    {
        return new Message(FBUtilities.getBroadcastAddress(), StorageService.Verb.DEFINITIONS_UPDATE, serializeSchema(schema, version), version);
    }

    /**
     * Serialize given row mutations into raw bytes
     *
     * @param schema The row mutations to serialize
     * @param version The version of the message service to use for serialization
     *
     * @return serialized mutations
     *
     * @throws IOException on failed serialization
     */
    public static byte[] serializeSchema(Collection<RowMutation> schema, int version) throws IOException
    {
        FastByteArrayOutputStream bout = new FastByteArrayOutputStream();
        DataOutputStream dout = new DataOutputStream(bout);
        dout.writeInt(schema.size());

        for (RowMutation mutation : schema)
            RowMutation.serializer().serialize(mutation, dout, version);

        dout.close();

        return bout.toByteArray();
    }

    /**
     * Deserialize migration message considering data compatibility starting from version 1.1
     *
     * @param data The data of the message from coordinator which hold schema mutations to apply
     * @param version The version of the message
     *
     * @return The collection of the row mutations to apply on the node (aka schema)
     *
     * @throws IOException if message is of incompatible version or data is corrupted
     */
    public static Collection<RowMutation> deserializeMigrationMessage(byte[] data, int version) throws IOException
    {
        Collection<RowMutation> schema = new ArrayList<RowMutation>();
        DataInputStream in = new DataInputStream(new FastByteArrayInputStream(data));

        int count = in.readInt();

        for (int i = 0; i < count; i++)
            schema.add(RowMutation.serializer().deserialize(in, version));

        return schema;
    }

    /**
     * Clear all locally stored schema information and reset schema to initial state.
     * Called by user (via JMX) who wants to get rid of schema disagreement.
     *
     * @throws IOException if schema tables truncation fails
     */
    public static void resetLocalSchema() throws IOException
    {
        logger.info("Starting local schema reset...");

        try
        {
            if (logger.isDebugEnabled())
                logger.debug("Truncating schema tables...");

            // truncate schema tables
            FBUtilities.waitOnFutures(new ArrayList<Future<?>>(3)
            {{
                SystemTable.schemaCFS(SystemTable.SCHEMA_KEYSPACES_CF).truncate();
                SystemTable.schemaCFS(SystemTable.SCHEMA_COLUMNFAMILIES_CF).truncate();
                SystemTable.schemaCFS(SystemTable.SCHEMA_COLUMNS_CF).truncate();
            }});

            if (logger.isDebugEnabled())
                logger.debug("Clearing local schema keyspace definitions...");

            Schema.instance.clear();

            Set<InetAddress> liveEndpoints = Gossiper.instance.getLiveMembers();
            liveEndpoints.remove(FBUtilities.getBroadcastAddress());

            // force migration is there are nodes around, first of all
            // check if there are nodes with versions >= 1.1.7 to request migrations from,
            // because migration format of the nodes with versions < 1.1 is incompatible with older versions
            // and due to broken timestamps in versions prior to 1.1.7
            for (InetAddress node : liveEndpoints)
            {
                if (Gossiper.instance.getVersion(node) >= MessagingService.VERSION_117)
                {
                    if (logger.isDebugEnabled())
                        logger.debug("Requesting schema from " + node);

                    FBUtilities.waitOnFuture(StageManager.getStage(Stage.MIGRATION).submit(new MigrationTask(node)));
                    break;
                }
            }

            logger.info("Local schema reset is complete.");
        }
        catch (InterruptedException e)
        {
            throw new RuntimeException(e);
        }
        catch (ExecutionException e)
        {
            throw new RuntimeException(e);
        }
    }

    /**
     * Used only in case node has old style migration schema (newly updated)
     * @return the UUID identifying version of the last applied migration
     */
    @Deprecated
    public static UUID getLastMigrationId()
    {
        DecoratedKey<?> dkey = StorageService.getPartitioner().decorateKey(LAST_MIGRATION_KEY);
        Table defs = Table.open(Table.SYSTEM_TABLE);
        ColumnFamilyStore cfStore = defs.getColumnFamilyStore(DefsTable.OLD_SCHEMA_CF);
        QueryFilter filter = QueryFilter.getNamesFilter(dkey, new QueryPath(DefsTable.OLD_SCHEMA_CF), LAST_MIGRATION_KEY);
        ColumnFamily cf = cfStore.getColumnFamily(filter);
        if (cf == null || cf.getColumnNames().size() == 0)
            return null;
        else
            return UUIDGen.getUUID(cf.getColumn(LAST_MIGRATION_KEY).value());
    }

    static class MigrationTask extends WrappedRunnable
    {
        private final InetAddress endpoint;

        MigrationTask(InetAddress endpoint)
        {
            this.endpoint = endpoint;
        }

        public void runMayThrow() throws Exception
        {
            Message message = new Message(FBUtilities.getBroadcastAddress(),
                                          StorageService.Verb.MIGRATION_REQUEST,
                                          ArrayUtils.EMPTY_BYTE_ARRAY,
                                          Gossiper.instance.getVersion(endpoint));

            if (!FailureDetector.instance.isAlive(endpoint))
            {
                logger.error("Can't send migration request: node {} is down.", endpoint);
                return;
            }

            IAsyncCallback cb = new IAsyncCallback()
            {
                public void response(Message message)
                {
                    try
                    {
                        DefsTable.mergeRemoteSchema(message.getMessageBody(), message.getVersion());
                    }
                    catch (IOException e)
                    {
                        logger.error("IOException merging remote schema", e);
                    }
                    catch (ConfigurationException e)
                    {
                        logger.error("Configuration exception merging remote schema", e);
                    }
                }

                public boolean isLatencyForSnitch()
                {
                    return false;
                }
            };

            MessagingService.instance().sendRR(message, endpoint, cb);
        }
    }
}
