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
package org.apache.cassandra.service;

import java.net.InetAddress;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicInteger;

import com.google.common.collect.Iterables;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.apache.cassandra.db.Table;
import org.apache.cassandra.exceptions.UnavailableException;
import org.apache.cassandra.gms.FailureDetector;
import org.apache.cassandra.net.MessageIn;
import org.apache.cassandra.db.ConsistencyLevel;
import org.apache.cassandra.db.WriteType;

/**
 * Handles blocking writes for ONE, ANY, TWO, THREE, QUORUM, and ALL consistency levels.
 */
public class WriteResponseHandler extends AbstractWriteResponseHandler
{
    protected static final Logger logger = LoggerFactory.getLogger(WriteResponseHandler.class);

    protected final AtomicInteger responses;

    public WriteResponseHandler(Collection<InetAddress> writeEndpoints,
                                Collection<InetAddress> pendingEndpoints,
                                ConsistencyLevel consistencyLevel,
                                Table table,
                                Runnable callback,
                                WriteType writeType)
    {
        super(table, writeEndpoints, pendingEndpoints, consistencyLevel, callback, writeType);
        responses = new AtomicInteger(consistencyLevel.blockFor(table));
    }

    public WriteResponseHandler(InetAddress endpoint, WriteType writeType, Runnable callback)
    {
        this(Arrays.asList(endpoint), Collections.<InetAddress>emptyList(), ConsistencyLevel.ONE, null, callback, writeType);
    }

    public WriteResponseHandler(InetAddress endpoint, WriteType writeType)
    {
        this(endpoint, writeType, null);
    }

    public void response(MessageIn m)
    {
        if (responses.decrementAndGet() == 0)
            signal();
    }

    protected int ackCount()
    {
        return consistencyLevel.blockFor(table) - responses.get();
    }

    public boolean isLatencyForSnitch()
    {
        return false;
    }
}
