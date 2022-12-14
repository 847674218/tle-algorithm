package org.apache.cassandra.tools;
/*
 *
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 *
 */


import java.io.IOException;
import java.io.PrintStream;
import java.lang.management.MemoryUsage;
import java.net.ConnectException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.text.DecimalFormat;
import java.util.*;
import java.util.Map.Entry;
import java.util.concurrent.ExecutionException;

import org.apache.commons.cli.*;

import org.apache.cassandra.concurrent.JMXEnabledThreadPoolExecutorMBean;
import org.apache.cassandra.config.ConfigurationException;
import org.apache.cassandra.db.ColumnFamilyStoreMBean;
import org.apache.cassandra.db.compaction.CompactionManagerMBean;
import org.apache.cassandra.db.compaction.OperationType;
import org.apache.cassandra.net.MessagingServiceMBean;
import org.apache.cassandra.service.CacheServiceMBean;
import org.apache.cassandra.service.StorageProxyMBean;
import org.apache.cassandra.thrift.InvalidRequestException;
import org.apache.cassandra.utils.EstimatedHistogram;
import org.apache.cassandra.utils.Pair;

public class NodeCmd
{
    private static final Pair<String, String> SNAPSHOT_COLUMNFAMILY_OPT = new Pair<String, String>("cf", "column-family");
    private static final Pair<String, String> HOST_OPT = new Pair<String, String>("h", "host");
    private static final Pair<String, String> PORT_OPT = new Pair<String, String>("p", "port");
    private static final Pair<String, String> USERNAME_OPT = new Pair<String, String>("u",  "username");
    private static final Pair<String, String> PASSWORD_OPT = new Pair<String, String>("pw", "password");
    private static final Pair<String, String> TAG_OPT = new Pair<String, String>("t", "tag");
    private static final Pair<String, String> PRIMARY_RANGE_OPT = new Pair<String, String>("pr", "partitioner-range");
    private static final Pair<String, String> START_TOKEN_OPT = new Pair<String, String>("st", "start-token");
    private static final Pair<String, String> END_TOKEN_OPT = new Pair<String, String>("et", "end-token");
    private static final Pair<String, String> SNAPSHOT_REPAIR_OPT = new Pair<String, String>("snapshot", "with-snapshot");

    private static final String DEFAULT_HOST = "127.0.0.1";
    private static final int DEFAULT_PORT = 7199;

    private static ToolOptions options = null;

    private NodeProbe probe;

    static
    {
        options = new ToolOptions();

        options.addOption(SNAPSHOT_COLUMNFAMILY_OPT, true, "only take a snapshot of the specified column family");
        options.addOption(HOST_OPT,     true, "node hostname or ip address");
        options.addOption(PORT_OPT,     true, "remote jmx agent port number");
        options.addOption(USERNAME_OPT, true, "remote jmx agent username");
        options.addOption(PASSWORD_OPT, true, "remote jmx agent password");
        options.addOption(TAG_OPT,      true, "optional name to give a snapshot");
        options.addOption(PRIMARY_RANGE_OPT, false, "only repair the first range returned by the partitioner for the node");
        options.addOption(SNAPSHOT_REPAIR_OPT, false, "repair one node at a time using snapshots");
        options.addOption(START_TOKEN_OPT, true, "token at which repair range starts");
        options.addOption(END_TOKEN_OPT, true, "token at which repair range ends");
    }

    public NodeCmd(NodeProbe probe)
    {
        this.probe = probe;
    }

    private enum NodeCommand
    {
        CFHISTOGRAMS,
        CFSTATS,
        CLEANUP,
        CLEARSNAPSHOT,
        COMPACT,
        COMPACTIONSTATS,
        DECOMMISSION,
        DISABLEGOSSIP,
        DISABLETHRIFT,
        DRAIN,
        ENABLEGOSSIP,
        ENABLETHRIFT,
        FLUSH,
        GETCOMPACTIONTHRESHOLD,
        GETENDPOINTS,
        GETSSTABLES,
        GOSSIPINFO,
        INFO,
        INVALIDATEKEYCACHE,
        INVALIDATEROWCACHE,
        JOIN,
        MOVE,
        NETSTATS,
        PROXYHISTOGRAMS,
        REBUILD,
        REFRESH,
        REMOVETOKEN,
        REPAIR,
        RING,
        SCRUB,
        SETCACHECAPACITY,
        SETCOMPACTIONTHRESHOLD,
        SETCOMPACTIONTHROUGHPUT,
        SETSTREAMTHROUGHPUT,
        SNAPSHOT,
        STATUSTHRIFT,
        STOP,
        TPSTATS,
        UPGRADESSTABLES,
        VERSION,
        DESCRIBERING,
        RANGEKEYSAMPLE,
        REBUILD_INDEX,
        RESETLOCALSCHEMA
    }


    /**
     * Prints usage information to stdout.
     */
    private static void printUsage()
    {
        HelpFormatter hf = new HelpFormatter();
        StringBuilder header = new StringBuilder();
        header.append("\nAvailable commands:\n");
        // No args
        addCmdHelp(header, "join", "Join the ring");
        addCmdHelp(header, "info", "Print node information (uptime, load, ...)");
        addCmdHelp(header, "cfstats", "Print statistics on column families");
        addCmdHelp(header, "version", "Print cassandra version");
        addCmdHelp(header, "tpstats", "Print usage statistics of thread pools");
        addCmdHelp(header, "proxyhistograms", "Print statistic histograms for network operations");
        addCmdHelp(header, "drain", "Drain the node (stop accepting writes and flush all column families)");
        addCmdHelp(header, "decommission", "Decommission the *node I am connecting to*");
        addCmdHelp(header, "compactionstats", "Print statistics on compactions");
        addCmdHelp(header, "disablegossip", "Disable gossip (effectively marking the node dead)");
        addCmdHelp(header, "enablegossip", "Reenable gossip");
        addCmdHelp(header, "disablethrift", "Disable thrift server");
        addCmdHelp(header, "enablethrift", "Reenable thrift server");
        addCmdHelp(header, "statusthrift", "Status of thrift server");
        addCmdHelp(header, "gossipinfo", "Shows the gossip information for the cluster");
        addCmdHelp(header, "invalidatekeycache", "Invalidate the key cache");
        addCmdHelp(header, "invalidaterowcache", "Invalidate the row cache");
        addCmdHelp(header, "resetlocalschema", "Reset node's local schema and resync");

        // One arg
        addCmdHelp(header, "ring [keyspace]", "Print information about the token ring for a given keyspace (for all keyspaces if it is not specified)");
        addCmdHelp(header, "netstats [host]", "Print network information on provided host (connecting node by default)");
        addCmdHelp(header, "move <new token>", "Move node on the token ring to a new token");
        addCmdHelp(header, "removetoken status|force|<token>", "Show status of current token removal, force completion of pending removal or remove providen token");
        addCmdHelp(header, "setcompactionthroughput <value_in_mb>", "Set the MB/s throughput cap for compaction in the system, or 0 to disable throttling.");
        addCmdHelp(header, "setstreamthroughput <value_in_mb>", "Set the MB/s throughput cap for streaming in the system, or 0 to disable throttling.");
        addCmdHelp(header, "describering [keyspace]", "Shows the token ranges info of a given keyspace.");
        addCmdHelp(header, "rangekeysample", "Shows the sampled keys held across all keyspaces.");
        addCmdHelp(header, "rebuild [src-dc-name]", "Rebuild data by streaming from other nodes (similarly to bootstrap)");

        // Two args
        addCmdHelp(header, "snapshot [keyspaces...] -cf [columnfamilyName] -t [snapshotName]", "Take a snapshot of the optionally specified column family of the specified keyspaces using optional name snapshotName");
        addCmdHelp(header, "clearsnapshot [keyspaces...] -t [snapshotName]", "Remove snapshots for the specified keyspaces. Either remove all snapshots or remove the snapshots with the given name.");
        addCmdHelp(header, "flush [keyspace] [cfnames]", "Flush one or more column family");
        addCmdHelp(header, "repair [keyspace] [cfnames]", "Repair one or more column family (use -pr to repair only the first range returned by the partitioner)");
        addCmdHelp(header, "cleanup [keyspace] [cfnames]", "Run cleanup on one or more column family");
        addCmdHelp(header, "compact [keyspace] [cfnames]", "Force a (major) compaction on one or more column family");
        addCmdHelp(header, "scrub [keyspace] [cfnames]", "Scrub (rebuild sstables for) one or more column family");

        addCmdHelp(header, "upgradesstables [keyspace] [cfnames]", "Scrub (rebuild sstables for) one or more column family");
        addCmdHelp(header, "getcompactionthreshold <keyspace> <cfname>", "Print min and max compaction thresholds for a given column family");
        addCmdHelp(header, "cfhistograms <keyspace> <cfname>", "Print statistic histograms for a given column family");
        addCmdHelp(header, "refresh <keyspace> <cf-name>", "Load newly placed SSTables to the system without restart.");
        addCmdHelp(header, "rebuild_index <keyspace> <cf-name> <idx1,idx1>", "a full rebuilds of native secondry index for a given column family. IndexNameExample: Standard3.IdxName,Standard3.IdxName1");
        addCmdHelp(header, "setcachecapacity <key-cache-capacity> <row-cache-capacity>", "Set global key and row cache capacities (in MB units).");

        // Three args
        addCmdHelp(header, "getendpoints <keyspace> <cf> <key>", "Print the end points that owns the key");
        addCmdHelp(header, "getsstables <keyspace> <cf> <key>", "Print the sstable filenames that own the key");

        // Four args
        addCmdHelp(header, "setcompactionthreshold <keyspace> <cfname> <minthreshold> <maxthreshold>", "Set the min and max compaction thresholds for a given column family");
        addCmdHelp(header, "stop <compaction_type>", "Supported types are COMPACTION, VALIDATION, CLEANUP, SCRUB, INDEX_BUILD");

        String usage = String.format("java %s --host <arg> <command>%n", NodeCmd.class.getName());
        hf.printHelp(usage, "", options, "");
        System.out.println(header.toString());
    }

    private static void addCmdHelp(StringBuilder sb, String cmd, String description)
    {
        sb.append("  ").append(cmd);
        // Ghetto indentation (trying, but not too hard, to not look too bad)
        if (cmd.length() <= 20)
            for (int i = cmd.length(); i < 22; ++i) sb.append(" ");
        sb.append(" - ").append(description).append("\n");
    }

    /**
     * Write a textual representation of the Cassandra ring.
     *
     * @param outs the stream to write to
     */
    public void printRing(PrintStream outs, String keyspace)
    {
        Map<String, String> tokenToEndpoint = probe.getTokenToEndpointMap();
        List<String> sortedTokens = new ArrayList<String>(tokenToEndpoint.keySet());

        Collection<String> liveNodes = probe.getLiveNodes();
        Collection<String> deadNodes = probe.getUnreachableNodes();
        Collection<String> joiningNodes = probe.getJoiningNodes();
        Collection<String> leavingNodes = probe.getLeavingNodes();
        Collection<String> movingNodes = probe.getMovingNodes();
        Map<String, String> loadMap = probe.getLoadMap();

        String format = "%-16s%-12s%-12s%-7s%-8s%-16s%-20s%-44s%n";

        // Calculate per-token ownership of the ring
        Map<String, Float> ownerships;
        try
        {
            ownerships = probe.effectiveOwnership(keyspace);
            outs.printf(format, "Address", "DC", "Rack", "Status", "State", "Load", "Effective-Ownership", "Token");
        }
        catch (ConfigurationException ex)
        {
            ownerships = probe.getOwnership();
            outs.printf("Note: Ownership information does not include topology, please specify a keyspace. \n");
            outs.printf(format, "Address", "DC", "Rack", "Status", "State", "Load", "Owns", "Token");
        }

        // show pre-wrap token twice so you can always read a node's range as
        // (previous line token, current line token]
        if (sortedTokens.size() > 1)
            outs.printf(format, "", "", "", "", "", "", "", sortedTokens.get(sortedTokens.size() - 1));

        for (String token : sortedTokens)
        {
            String primaryEndpoint = tokenToEndpoint.get(token);
            String dataCenter;
            try
            {
                dataCenter = probe.getEndpointSnitchInfoProxy().getDatacenter(primaryEndpoint);
            }
            catch (UnknownHostException e)
            {
                dataCenter = "Unknown";
            }
            String rack;
            try
            {
                rack = probe.getEndpointSnitchInfoProxy().getRack(primaryEndpoint);
            }
            catch (UnknownHostException e)
            {
                rack = "Unknown";
            }
            String status = liveNodes.contains(primaryEndpoint)
                            ? "Up"
                            : deadNodes.contains(primaryEndpoint)
                              ? "Down"
                              : "?";

            String state = "Normal";

            if (joiningNodes.contains(primaryEndpoint))
                state = "Joining";
            else if (leavingNodes.contains(primaryEndpoint))
                state = "Leaving";
            else if (movingNodes.contains(primaryEndpoint))
                state = "Moving";

            String load = loadMap.containsKey(primaryEndpoint)
                          ? loadMap.get(primaryEndpoint)
                          : "?";
            String owns = new DecimalFormat("##0.00%").format(ownerships.get(token) == null ? 0.0F : ownerships.get(token));
            outs.printf(format, primaryEndpoint, dataCenter, rack, status, state, load, owns, token);
        }
    }

    public void printThreadPoolStats(PrintStream outs)
    {
        outs.printf("%-25s%10s%10s%15s%10s%18s%n", "Pool Name", "Active", "Pending", "Completed", "Blocked", "All time blocked");

        Iterator<Map.Entry<String, JMXEnabledThreadPoolExecutorMBean>> threads = probe.getThreadPoolMBeanProxies();
        while (threads.hasNext())
        {
            Entry<String, JMXEnabledThreadPoolExecutorMBean> thread = threads.next();
            String poolName = thread.getKey();
            JMXEnabledThreadPoolExecutorMBean threadPoolProxy = thread.getValue();
            outs.printf("%-25s%10s%10s%15s%10s%18s%n",
                        poolName,
                        threadPoolProxy.getActiveCount(),
                        threadPoolProxy.getPendingTasks(),
                        threadPoolProxy.getCompletedTasks(),
                        threadPoolProxy.getCurrentlyBlockedTasks(),
                        threadPoolProxy.getTotalBlockedTasks());
        }

        outs.printf("%n%-20s%10s%n", "Message type", "Dropped");
        for (Entry<String, Integer> entry : probe.getDroppedMessages().entrySet())
            outs.printf("%-20s%10s%n", entry.getKey(), entry.getValue());
    }

    /**
     * Write node information.
     *
     * @param outs the stream to write to
     */
    public void printInfo(PrintStream outs)
    {
        boolean gossipInitialized = probe.isInitialized();
        outs.printf("%-17s: %s%n", "Token", probe.getToken());
        outs.printf("%-17s: %s%n", "Gossip active", gossipInitialized);
        outs.printf("%-17s: %s%n", "Thrift active", probe.isThriftServerRunning());
        outs.printf("%-17s: %s%n", "Load", probe.getLoadString());
        if (gossipInitialized)
            outs.printf("%-17s: %s%n", "Generation No", probe.getCurrentGenerationNumber());
        else
            outs.printf("%-17s: %s%n", "Generation No", 0);

        // Uptime
        long secondsUp = probe.getUptime() / 1000;
        outs.printf("%-17s: %d%n", "Uptime (seconds)", secondsUp);

        // Memory usage
        MemoryUsage heapUsage = probe.getHeapMemoryUsage();
        double memUsed = (double)heapUsage.getUsed() / (1024 * 1024);
        double memMax = (double)heapUsage.getMax() / (1024 * 1024);
        outs.printf("%-17s: %.2f / %.2f%n", "Heap Memory (MB)", memUsed, memMax);

        // Data Center/Rack
        outs.printf("%-17s: %s%n", "Data Center", probe.getDataCenter());
        outs.printf("%-17s: %s%n", "Rack", probe.getRack());

        // Exceptions
        outs.printf("%-17s: %s%n", "Exceptions", probe.getExceptionCount());

        CacheServiceMBean cacheService = probe.getCacheServiceMBean();

        // Key Cache: Hits, Requests, RecentHitRate, SavePeriodInSeconds
        outs.printf("%-17s: size %d (bytes), capacity %d (bytes), %d hits, %d requests, %.3f recent hit rate, %d save period in seconds%n",
                    "Key Cache",
                    cacheService.getKeyCacheSize(),
                    cacheService.getKeyCacheCapacityInBytes(),
                    cacheService.getKeyCacheHits(),
                    cacheService.getKeyCacheRequests(),
                    cacheService.getKeyCacheRecentHitRate(),
                    cacheService.getKeyCacheSavePeriodInSeconds());

        // Row Cache: Hits, Requests, RecentHitRate, SavePeriodInSeconds
        outs.printf("%-17s: size %d (bytes), capacity %d (bytes), %d hits, %d requests, %.3f recent hit rate, %d save period in seconds%n",
                    "Row Cache",
                    cacheService.getRowCacheSize(),
                    cacheService.getRowCacheCapacityInBytes(),
                    cacheService.getRowCacheHits(),
                    cacheService.getRowCacheRequests(),
                    cacheService.getRowCacheRecentHitRate(),
                    cacheService.getRowCacheSavePeriodInSeconds());
    }

    public void printReleaseVersion(PrintStream outs)
    {
        outs.println("ReleaseVersion: " + probe.getReleaseVersion());
    }

    public void printNetworkStats(final InetAddress addr, PrintStream outs)
    {
        outs.printf("Mode: %s%n", probe.getOperationMode());
        Set<InetAddress> hosts = addr == null ? probe.getStreamDestinations() : new HashSet<InetAddress>(){{add(addr);}};
        if (hosts.size() == 0)
            outs.println("Not sending any streams.");
        for (InetAddress host : hosts)
        {
            try
            {
                List<String> files = probe.getFilesDestinedFor(host);
                if (files.size() > 0)
                {
                    outs.printf("Streaming to: %s%n", host);
                    for (String file : files)
                        outs.printf("   %s%n", file);
                }
                else
                {
                    outs.printf(" Nothing streaming to %s%n", host);
                }
            }
            catch (IOException ex)
            {
                outs.printf("   Error retrieving file data for %s%n", host);
            }
        }

        hosts = addr == null ? probe.getStreamSources() : new HashSet<InetAddress>(){{add(addr); }};
        if (hosts.size() == 0)
            outs.println("Not receiving any streams.");
        for (InetAddress host : hosts)
        {
            try
            {
                List<String> files = probe.getIncomingFiles(host);
                if (files.size() > 0)
                {
                    outs.printf("Streaming from: %s%n", host);
                    for (String file : files)
                        outs.printf("   %s%n", file);
                }
                else
                {
                    outs.printf(" Nothing streaming from %s%n", host);
                }
            }
            catch (IOException ex)
            {
                outs.printf("   Error retrieving file data for %s%n", host);
            }
        }

        MessagingServiceMBean ms = probe.msProxy;
        outs.printf("%-25s", "Pool Name");
        outs.printf("%10s", "Active");
        outs.printf("%10s", "Pending");
        outs.printf("%15s%n", "Completed");

        int pending;
        long completed;

        pending = 0;
        for (int n : ms.getCommandPendingTasks().values())
            pending += n;
        completed = 0;
        for (long n : ms.getCommandCompletedTasks().values())
            completed += n;
        outs.printf("%-25s%10s%10s%15s%n", "Commands", "n/a", pending, completed);

        pending = 0;
        for (int n : ms.getResponsePendingTasks().values())
            pending += n;
        completed = 0;
        for (long n : ms.getResponseCompletedTasks().values())
            completed += n;
        outs.printf("%-25s%10s%10s%15s%n", "Responses", "n/a", pending, completed);
    }

    public void printCompactionStats(PrintStream outs)
    {
        int compactionThroughput = probe.getCompactionThroughput();
        CompactionManagerMBean cm = probe.getCompactionManagerProxy();
        outs.println("pending tasks: " + cm.getPendingTasks());
        if (cm.getCompactions().size() > 0)
            outs.printf("%25s%16s%16s%16s%16s%10s%n", "compaction type", "keyspace", "column family", "bytes compacted", "bytes total", "progress");
        long remainingBytes = 0;
        for (Map<String, String> c : cm.getCompactions())
        {
            String percentComplete = new Long(c.get("totalBytes")) == 0
                                   ? "n/a"
                                   : new DecimalFormat("0.00").format((double) new Long(c.get("bytesComplete")) / new Long(c.get("totalBytes")) * 100) + "%";
            outs.printf("%25s%16s%16s%16s%16s%10s%n", c.get("taskType"), c.get("keyspace"), c.get("columnfamily"), c.get("bytesComplete"), c.get("totalBytes"), percentComplete);
            if (c.get("taskType").equals(OperationType.COMPACTION.toString()))
                remainingBytes += (new Long(c.get("totalBytes")) - new Long(c.get("bytesComplete")));
        }
        long remainingTimeInSecs = compactionThroughput == 0 || remainingBytes == 0
                        ? -1 
                        : (remainingBytes) / (long) (1024L * 1024L * compactionThroughput);
        String remainingTime = remainingTimeInSecs < 0 
                        ? "n/a"
                        : String.format("%dh%02dm%02ds", remainingTimeInSecs / 3600, (remainingTimeInSecs % 3600) / 60, (remainingTimeInSecs % 60)); 

        outs.printf("%25s%10s%n", "Active compaction remaining time : ", remainingTime);
    }

    public void printColumnFamilyStats(PrintStream outs)
    {
        Map <String, List <ColumnFamilyStoreMBean>> cfstoreMap = new HashMap <String, List <ColumnFamilyStoreMBean>>();

        // get a list of column family stores
        Iterator<Map.Entry<String, ColumnFamilyStoreMBean>> cfamilies = probe.getColumnFamilyStoreMBeanProxies();

        while (cfamilies.hasNext())
        {
            Entry<String, ColumnFamilyStoreMBean> entry = cfamilies.next();
            String tableName = entry.getKey();
            ColumnFamilyStoreMBean cfsProxy = entry.getValue();

            if (!cfstoreMap.containsKey(tableName))
            {
                List<ColumnFamilyStoreMBean> columnFamilies = new ArrayList<ColumnFamilyStoreMBean>();
                columnFamilies.add(cfsProxy);
                cfstoreMap.put(tableName, columnFamilies);
            }
            else
            {
                cfstoreMap.get(tableName).add(cfsProxy);
            }
        }

        // print out the table statistics
        for (Entry<String, List<ColumnFamilyStoreMBean>> entry : cfstoreMap.entrySet())
        {
            String tableName = entry.getKey();
            List<ColumnFamilyStoreMBean> columnFamilies = entry.getValue();
            long tableReadCount = 0;
            long tableWriteCount = 0;
            int tablePendingTasks = 0;
            double tableTotalReadTime = 0.0f;
            double tableTotalWriteTime = 0.0f;

            outs.println("Keyspace: " + tableName);
            for (ColumnFamilyStoreMBean cfstore : columnFamilies)
            {
                long writeCount = cfstore.getWriteCount();
                long readCount = cfstore.getReadCount();

                if (readCount > 0)
                {
                    tableReadCount += readCount;
                    tableTotalReadTime += cfstore.getTotalReadLatencyMicros();
                }
                if (writeCount > 0)
                {
                    tableWriteCount += writeCount;
                    tableTotalWriteTime += cfstore.getTotalWriteLatencyMicros();
                }
                tablePendingTasks += cfstore.getPendingTasks();
            }

            double tableReadLatency = tableReadCount > 0 ? tableTotalReadTime / tableReadCount / 1000 : Double.NaN;
            double tableWriteLatency = tableWriteCount > 0 ? tableTotalWriteTime / tableWriteCount / 1000 : Double.NaN;

            outs.println("\tRead Count: " + tableReadCount);
            outs.println("\tRead Latency: " + String.format("%s", tableReadLatency) + " ms.");
            outs.println("\tWrite Count: " + tableWriteCount);
            outs.println("\tWrite Latency: " + String.format("%s", tableWriteLatency) + " ms.");
            outs.println("\tPending Tasks: " + tablePendingTasks);

            // print out column family statistics for this table
            for (ColumnFamilyStoreMBean cfstore : columnFamilies)
            {
                outs.println("\t\tColumn Family: " + cfstore.getColumnFamilyName());
                outs.println("\t\tSSTable count: " + cfstore.getLiveSSTableCount());
                outs.println("\t\tSpace used (live): " + cfstore.getLiveDiskSpaceUsed());
                outs.println("\t\tSpace used (total): " + cfstore.getTotalDiskSpaceUsed());
                outs.println("\t\tNumber of Keys (estimate): " + cfstore.estimateKeys());
                outs.println("\t\tMemtable Columns Count: " + cfstore.getMemtableColumnsCount());
                outs.println("\t\tMemtable Data Size: " + cfstore.getMemtableDataSize());
                outs.println("\t\tMemtable Switch Count: " + cfstore.getMemtableSwitchCount());
                outs.println("\t\tRead Count: " + cfstore.getReadCount());
                outs.println("\t\tRead Latency: " + String.format("%01.3f", cfstore.getRecentReadLatencyMicros() / 1000) + " ms.");
                outs.println("\t\tWrite Count: " + cfstore.getWriteCount());
                outs.println("\t\tWrite Latency: " + String.format("%01.3f", cfstore.getRecentWriteLatencyMicros() / 1000) + " ms.");
                outs.println("\t\tPending Tasks: " + cfstore.getPendingTasks());
                outs.println("\t\tBloom Filter False Positives: " + cfstore.getBloomFilterFalsePositives());
                outs.println("\t\tBloom Filter False Ratio: " + String.format("%01.5f", cfstore.getRecentBloomFilterFalseRatio()));
                outs.println("\t\tBloom Filter Space Used: " + cfstore.getBloomFilterDiskSpaceUsed());
                outs.println("\t\tCompacted row minimum size: " + cfstore.getMinRowSize());
                outs.println("\t\tCompacted row maximum size: " + cfstore.getMaxRowSize());
                outs.println("\t\tCompacted row mean size: " + cfstore.getMeanRowSize());

                outs.println("");
            }
            outs.println("----------------");
        }
    }

    public void printRemovalStatus(PrintStream outs)
    {
        outs.println("RemovalStatus: " + probe.getRemovalStatus());
    }

    private void printCfHistograms(String keySpace, String columnFamily, PrintStream output)
    {
        ColumnFamilyStoreMBean store = this.probe.getCfsProxy(keySpace, columnFamily);

        // default is 90 offsets
        long[] offsets = new EstimatedHistogram().getBucketOffsets();

        long[] rrlh = store.getRecentReadLatencyHistogramMicros();
        long[] rwlh = store.getRecentWriteLatencyHistogramMicros();
        long[] sprh = store.getRecentSSTablesPerReadHistogram();
        long[] ersh = store.getEstimatedRowSizeHistogram();
        long[] ecch = store.getEstimatedColumnCountHistogram();

        output.println(String.format("%s/%s histograms", keySpace, columnFamily));

        output.println(String.format("%-10s%10s%18s%18s%18s%18s",
                                     "Offset", "SSTables", "Write Latency", "Read Latency", "Row Size", "Column Count"));

        for (int i = 0; i < offsets.length; i++)
        {
            output.println(String.format("%-10d%10s%18s%18s%18s%18s",
                                         offsets[i],
                                         (i < sprh.length ? sprh[i] : "0"),
                                         (i < rwlh.length ? rwlh[i] : "0"),
                                         (i < rrlh.length ? rrlh[i] : "0"),
                                         (i < ersh.length ? ersh[i] : "0"),
                                         (i < ecch.length ? ecch[i] : "0")));
        }
    }

    private void printProxyHistograms(PrintStream output)
        {
            StorageProxyMBean sp = this.probe.getSpProxy();
            long[] offsets = new EstimatedHistogram().getBucketOffsets();
            long[] rrlh = sp.getRecentReadLatencyHistogramMicros();
            long[] rwlh = sp.getRecentWriteLatencyHistogramMicros();
            long[] rrnglh = sp.getRecentRangeLatencyHistogramMicros();

            output.println("proxy histograms");
            output.println(String.format("%-10s%10s%18s%18s",
                                        "Offset", "Read Latency", "Write Latency", "Range Latency"));
            for (int i = 0; i < offsets.length; i++)
            {
                output.println(String.format("%-10d%18s%18s%18s",
                                            offsets[i],
                                            (i < rrlh.length ? rrlh[i] : "0"),
                                            (i < rwlh.length ? rwlh[i] : "0"),
                                            (i < rrnglh.length ? rrnglh[i] : "0")));
            }
    }

    private void printEndPoints(String keySpace, String cf, String key, PrintStream output)
    {
        List<InetAddress> endpoints = this.probe.getEndpoints(keySpace, cf, key);

        for (InetAddress anEndpoint : endpoints)
        {
           output.println(anEndpoint.getHostAddress());
        }
    }

    private void printSSTables(String keyspace, String cf, String key, PrintStream output)
    {
        List<String> sstables = this.probe.getSSTables(keyspace, cf, key);
        for (String sstable : sstables)
        {
            output.println(sstable);
        }
    }

    private void printIsThriftServerRunning(PrintStream outs)
    {
        outs.println(probe.isThriftServerRunning() ? "running" : "not running");
    }

    public static void main(String[] args) throws IOException, InterruptedException, ConfigurationException, ParseException
    {
        CommandLineParser parser = new PosixParser();
        ToolCommandLine cmd = null;

        try
        {
            cmd = new ToolCommandLine(parser.parse(options, args));
        }
        catch (ParseException p)
        {
            badUse(p.getMessage());
        }

        String host = cmd.hasOption(HOST_OPT.left) ? cmd.getOptionValue(HOST_OPT.left) : DEFAULT_HOST;

        int port = DEFAULT_PORT;

        String portNum = cmd.getOptionValue(PORT_OPT.left);
        if (portNum != null)
        {
            try
            {
                port = Integer.parseInt(portNum);
            }
            catch (NumberFormatException e)
            {
                throw new ParseException("Port must be a number");
            }
        }

        String username = cmd.getOptionValue(USERNAME_OPT.left);
        String password = cmd.getOptionValue(PASSWORD_OPT.left);

        NodeProbe probe = null;
        try
        {
            probe = username == null ? new NodeProbe(host, port) : new NodeProbe(host, port, username, password);
        }
        catch (IOException ioe)
        {
            Throwable inner = findInnermostThrowable(ioe);
            if (inner instanceof ConnectException)
            {
                System.err.printf("Failed to connect to '%s:%d': %s\n", host, port, inner.getMessage());
                System.exit(1);
            }
            else if (inner instanceof UnknownHostException)
            {
                System.err.printf("Cannot resolve '%s': unknown host\n", host);
                System.exit(1);
            }
            else
            {
                err(ioe, "Error connecting to remote JMX agent!");
            }
        }
        try
        {
            NodeCommand command = null;

            try
            {
                command = cmd.getCommand();
            }
            catch (IllegalArgumentException e)
            {
                badUse(e.getMessage());
            }


            NodeCmd nodeCmd = new NodeCmd(probe);

            // Execute the requested command.
            String[] arguments = cmd.getCommandArguments();
            String tag;
            String columnFamilyName = null;

            switch (command)
            {
                case RING :
                    if (arguments.length > 0) { nodeCmd.printRing(System.out, arguments[0]); }
                    else                      { nodeCmd.printRing(System.out, null); };
                    break;

                case INFO            : nodeCmd.printInfo(System.out); break;
                case CFSTATS         : nodeCmd.printColumnFamilyStats(System.out); break;
                case TPSTATS         : nodeCmd.printThreadPoolStats(System.out); break;
                case VERSION         : nodeCmd.printReleaseVersion(System.out); break;
                case COMPACTIONSTATS : nodeCmd.printCompactionStats(System.out); break;
                case DISABLEGOSSIP   : probe.stopGossiping(); break;
                case ENABLEGOSSIP    : probe.startGossiping(); break;
                case DISABLETHRIFT   : probe.stopThriftServer(); break;
                case ENABLETHRIFT    : probe.startThriftServer(); break;
                case STATUSTHRIFT    : nodeCmd.printIsThriftServerRunning(System.out); break;
                case RESETLOCALSCHEMA: probe.resetLocalSchema(); break;

                case DECOMMISSION :
                    if (arguments.length > 0)
                    {
                        System.err.println("Decommission will decommission the node you are connected to and does not take arguments!");
                        System.exit(1);
                    }
                    probe.decommission();
                    break;

                case DRAIN :
                    try { probe.drain(); }
                    catch (ExecutionException ee) { err(ee, "Error occured during flushing"); }
                    break;

                case NETSTATS :
                    if (arguments.length > 0) { nodeCmd.printNetworkStats(InetAddress.getByName(arguments[0]), System.out); }
                    else                      { nodeCmd.printNetworkStats(null, System.out); }
                    break;

                case SNAPSHOT :
                    columnFamilyName = cmd.getOptionValue(SNAPSHOT_COLUMNFAMILY_OPT.left);
                    /* FALL THRU */
                case CLEARSNAPSHOT :
                    tag = cmd.getOptionValue(TAG_OPT.left);
                    handleSnapshots(command, tag, arguments, columnFamilyName, probe);
                    break;

                case MOVE :
                    if (arguments.length != 1) { badUse("Missing token argument for move."); }
                    probe.move(arguments[0]);
                    break;

                case JOIN:
                    if (probe.isJoined())
                    {
                        System.err.println("This node has already joined the ring.");
                        System.exit(1);
                    }

                    probe.joinRing();
                    break;

                case SETCOMPACTIONTHROUGHPUT :
                    if (arguments.length != 1) { badUse("Missing value argument."); }
                    probe.setCompactionThroughput(Integer.valueOf(arguments[0]));
                    break;

                case SETSTREAMTHROUGHPUT :
                    if (arguments.length != 1) { badUse("Missing value argument."); }
                    probe.setStreamThroughput(Integer.valueOf(arguments[0]));
                    break;

                case REBUILD :
                    if (arguments.length > 1) { badUse("Too many arguments."); }
                    probe.rebuild(arguments.length == 1 ? arguments[0] : null);
                    break;

                case REMOVETOKEN :
                    if (arguments.length != 1) { badUse("Missing an argument for removetoken (either status, force, or a token)"); }
                    else if (arguments[0].equals("status")) { nodeCmd.printRemovalStatus(System.out); }
                    else if (arguments[0].equals("force"))  { nodeCmd.printRemovalStatus(System.out); probe.forceRemoveCompletion(); }
                    else                                    { probe.removeToken(arguments[0]); }
                    break;

                case INVALIDATEKEYCACHE :
                    probe.invalidateKeyCache();
                    break;

                case INVALIDATEROWCACHE :
                    probe.invalidateRowCache();
                    break;

                case CLEANUP :
                case COMPACT :
                case REPAIR  :
                case FLUSH   :
                case SCRUB   :
                case UPGRADESSTABLES   :
                    optionalKSandCFs(command, cmd, arguments, probe);
                    break;

                case GETCOMPACTIONTHRESHOLD :
                    if (arguments.length != 2) { badUse("getcompactionthreshold requires ks and cf args."); }
                    probe.getCompactionThreshold(System.out, arguments[0], arguments[1]);
                    break;

                case CFHISTOGRAMS :
                    if (arguments.length != 2) { badUse("cfhistograms requires ks and cf args"); }
                    nodeCmd.printCfHistograms(arguments[0], arguments[1], System.out);
                    break;

                case SETCACHECAPACITY :
                    if (arguments.length != 2) { badUse("setcachecapacity requires key-cache-capacity, and row-cache-capacity args."); }
                    probe.setCacheCapacities(Integer.parseInt(arguments[0]), Integer.parseInt(arguments[1]));
                    break;

                case SETCOMPACTIONTHRESHOLD :
                    if (arguments.length != 4) { badUse("setcompactionthreshold requires ks, cf, min, and max threshold args."); }
                    int minthreshold = Integer.parseInt(arguments[2]);
                    int maxthreshold = Integer.parseInt(arguments[3]);
                    if ((minthreshold < 0) || (maxthreshold < 0)) { badUse("Thresholds must be positive integers"); }
                    if (minthreshold > maxthreshold)              { badUse("Min threshold cannot be greater than max."); }
                    if (minthreshold < 2 && maxthreshold != 0)    { badUse("Min threshold must be at least 2"); }
                    probe.setCompactionThreshold(arguments[0], arguments[1], minthreshold, maxthreshold);
                    break;

                case GETENDPOINTS :
                    if (arguments.length != 3) { badUse("getendpoints requires ks, cf and key args"); }
                    nodeCmd.printEndPoints(arguments[0], arguments[1], arguments[2], System.out);
                    break;

                case GETSSTABLES:
                    if (arguments.length != 3) { badUse("getsstables requires ks, cf and key args"); }
                    nodeCmd.printSSTables(arguments[0], arguments[1], arguments[2], System.out);
                    break;

                case PROXYHISTOGRAMS :
                    if (arguments.length != 0) { badUse("proxyhistograms does not take arguments"); }
                    nodeCmd.printProxyHistograms(System.out);
                    break;

                case REFRESH:
                    if (arguments.length != 2) { badUse("load_new_sstables requires ks and cf args"); }
                    probe.loadNewSSTables(arguments[0], arguments[1]);
                    break;

                case REBUILD_INDEX:
                    if (arguments.length < 2) { badUse("rebuild_index requires ks and cf args"); }
                    if (arguments.length >= 3)
                        probe.rebuildIndex(arguments[0], arguments[1], arguments[2].split(","));
                    else
                        probe.rebuildIndex(arguments[0], arguments[1]);

                    break;

                case GOSSIPINFO : nodeCmd.printGossipInfo(System.out); break;

                case STOP:
                    if (arguments.length != 1) { badUse("stop requires a type."); }
                    probe.stop(arguments[0].toUpperCase());
                    break;

                case DESCRIBERING :
                    if (arguments.length != 1) { badUse("Missing keyspace argument for describering."); }
                    nodeCmd.printDescribeRing(arguments[0], System.out);
                    break;

                case RANGEKEYSAMPLE :
                    nodeCmd.printRangeKeySample(System.out);
                    break;

                default :
                    throw new RuntimeException("Unreachable code.");
            }
        }
        finally
        {
            if (probe != null)
            {
                try
                {
                    probe.close();
                }
                catch (IOException ex)
                {
                    // swallow the exception so the user will see the real one.
                }
            }
        }
        System.exit(probe.isFailed() ? 1 : 0);
    }

    private static Throwable findInnermostThrowable(Throwable ex)
    {
        Throwable inner = ex.getCause();
        return inner == null ? ex : findInnermostThrowable(inner);
    }

    private void printDescribeRing(String keyspaceName, PrintStream out)
    {
        out.println("Schema Version:" + probe.getSchemaVersion());
        out.println("TokenRange: ");
        try
        {
            for (String tokenRangeString : probe.describeRing(keyspaceName))
            {
                out.println("\t" + tokenRangeString);
            }
        }
        catch (InvalidRequestException e)
        {
            err(e, e.getWhy());
        }
    }

    private void printRangeKeySample(PrintStream outs)
    {
        outs.println("RangeKeySample: ");
        List<String> tokenStrings = this.probe.sampleKeyRange();
        for (String tokenString : tokenStrings)
        {
            outs.println("\t" + tokenString);
        }
    }

    private void printGossipInfo(PrintStream out) {
        out.println(probe.getGossipInfo());
    }

    private static void badUse(String useStr)
    {
        System.err.println(useStr);
        printUsage();
        System.exit(1);
    }

    private static void err(Exception e, String errStr)
    {
        System.err.println(errStr);
        e.printStackTrace();
        System.exit(3);
    }

    private static void complainNonzeroArgs(String[] args, NodeCommand cmd)
    {
        if (args.length > 0) {
            System.err.println("Too many arguments for command '"+cmd.toString()+"'.");
            printUsage();
            System.exit(1);
        }
    }

    private static void handleSnapshots(NodeCommand nc, String tag, String[] cmdArgs, String columnFamily, NodeProbe probe) throws InterruptedException, IOException
    {
        String[] keyspaces = Arrays.copyOfRange(cmdArgs, 0, cmdArgs.length);
        System.out.print("Requested snapshot for: ");
        if ( keyspaces.length > 0 )
        {
          for (int i = 0; i < keyspaces.length; i++)
              System.out.print(keyspaces[i] + " ");
        }
        else
        {
            System.out.print("all keyspaces ");
        }

        if (columnFamily != null)
        {
            System.out.print("and column family: " + columnFamily);
        }
        System.out.println();

        switch (nc)
        {
            case SNAPSHOT :
                if (tag == null || tag.equals(""))
                    tag = new Long(System.currentTimeMillis()).toString();
                probe.takeSnapshot(tag, columnFamily, keyspaces);
                System.out.println("Snapshot directory: " + tag);
                break;
            case CLEARSNAPSHOT :
                probe.clearSnapshot(tag, keyspaces);
                break;
        }
    }

    private static void optionalKSandCFs(NodeCommand nc, ToolCommandLine cmd, String[] cmdArgs, NodeProbe probe) throws InterruptedException, IOException
    {
        // if there is one additional arg, it's the keyspace; more are columnfamilies
        List<String> keyspaces = cmdArgs.length == 0 ? probe.getKeyspaces() : Arrays.asList(cmdArgs[0]);
        for (String keyspace : keyspaces)
        {
            if (!probe.getKeyspaces().contains(keyspace))
            {
                System.err.println("Keyspace [" + keyspace + "] does not exist.");
                System.exit(1);
            }
        }

        // second loop so we're less likely to die halfway through due to invalid keyspace
        for (String keyspace : keyspaces)
        {
            String[] columnFamilies = cmdArgs.length <= 1 ? new String[0] : Arrays.copyOfRange(cmdArgs, 1, cmdArgs.length);
            switch (nc)
            {
                case REPAIR  :
                    boolean snapshot = cmd.hasOption(SNAPSHOT_REPAIR_OPT.left);
                    boolean primaryRange = cmd.hasOption(PRIMARY_RANGE_OPT.left);
                    if (cmd.hasOption(START_TOKEN_OPT.left) || cmd.hasOption(END_TOKEN_OPT.left))
                        probe.forceRepairRangeAsync(System.out, keyspace, snapshot, cmd.getOptionValue(START_TOKEN_OPT.left), cmd.getOptionValue(END_TOKEN_OPT.left), columnFamilies);
                    else
                        probe.forceRepairAsync(System.out, keyspace, snapshot, primaryRange, columnFamilies);
                    break;
                case FLUSH   :
                    try { probe.forceTableFlush(keyspace, columnFamilies); }
                    catch (ExecutionException ee) { err(ee, "Error occurred during flushing"); }
                    break;
                case COMPACT :
                    try { probe.forceTableCompaction(keyspace, columnFamilies); }
                    catch (ExecutionException ee) { err(ee, "Error occurred during compaction"); }
                    break;
                case CLEANUP :
                    if (keyspace.equals("system")) { break; } // Skip cleanup on system cfs.
                    try { probe.forceTableCleanup(keyspace, columnFamilies); }
                    catch (ExecutionException ee) { err(ee, "Error occurred during cleanup"); }
                    break;
                case SCRUB :
                    try { probe.scrub(keyspace, columnFamilies); }
                    catch (ExecutionException ee) { err(ee, "Error occurred while scrubbing keyspace " + keyspace); }
                    break;
                case UPGRADESSTABLES :
                    try { probe.upgradeSSTables(keyspace, columnFamilies); }
                    catch (ExecutionException ee) { err(ee, "Error occurred while upgrading the sstables for keyspace " + keyspace); }
                    break;
                default:
                    throw new RuntimeException("Unreachable code.");
            }
        }
    }


    private static class ToolOptions extends Options
    {
        public void addOption(Pair<String, String> opts, boolean hasArgument, String description)
        {
            addOption(opts, hasArgument, description, false);
        }

        public void addOption(Pair<String, String> opts, boolean hasArgument, String description, boolean required)
        {
            addOption(opts.left, opts.right, hasArgument, description, required);
        }

        public void addOption(String opt, String longOpt, boolean hasArgument, String description, boolean required)
        {
            Option option = new Option(opt, longOpt, hasArgument, description);
            option.setRequired(required);
            addOption(option);
        }
    }

    private static class ToolCommandLine
    {
        private final CommandLine commandLine;

        public ToolCommandLine(CommandLine commands)
        {
            commandLine = commands;
        }

        public Option[] getOptions()
        {
            return commandLine.getOptions();
        }

        public boolean hasOption(String opt)
        {
            return commandLine.hasOption(opt);
        }

        public String getOptionValue(String opt)
        {
            return commandLine.getOptionValue(opt);
        }

        public NodeCommand getCommand()
        {
            if (commandLine.getArgs().length == 0)
                throw new IllegalArgumentException("Command was not specified.");

            String command = commandLine.getArgs()[0];

            try
            {
                return NodeCommand.valueOf(command.toUpperCase());
            }
            catch (IllegalArgumentException e)
            {
                throw new IllegalArgumentException("Unrecognized command: " + command);
            }
        }

        public String[] getCommandArguments()
        {
            List params = commandLine.getArgList();

            if (params.size() < 2) // command parameters are empty
                return new String[0];

            String[] toReturn = new String[params.size() - 1];

            for (int i = 1; i < params.size(); i++)
                toReturn[i - 1] = (String) params.get(i);

            return toReturn;
        }
    }
}
