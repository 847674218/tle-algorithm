package org.apache.cassandra.stress.operations;
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
import java.nio.ByteBuffer;
import java.util.Collections;

import com.yammer.metrics.core.TimerContext;
import org.apache.cassandra.db.ColumnFamilyType;
import org.apache.cassandra.stress.Session;
import org.apache.cassandra.stress.util.CassandraClient;
import org.apache.cassandra.stress.util.Operation;
import org.apache.cassandra.thrift.Compression;
import org.apache.cassandra.thrift.CqlResult;
import org.apache.cassandra.thrift.CqlResultType;
import org.apache.cassandra.utils.ByteBufferUtil;

public class CqlCounterGetter extends Operation
{
    private static String cqlQuery = null;

    public CqlCounterGetter(Session client, int idx)
    {
        super(client, idx);
    }

    public void run(CassandraClient client) throws IOException
    {
        if (session.getColumnFamilyType() == ColumnFamilyType.Super)
            throw new RuntimeException("Super columns are not implemented for CQL");

        if (cqlQuery == null)
        {
            StringBuilder query = new StringBuilder("SELECT ");

            if (session.cqlVersion.startsWith("2"))
                query.append("FIRST ").append(session.getColumnsPerKey()).append(" ''..''");
            else
                query.append("*");

            String counterCF = session.cqlVersion.startsWith("2") ? "Counter1" : "Counter3";

            query.append(" FROM ").append(wrapInQuotesIfRequired(counterCF));

            if (session.cqlVersion.startsWith("2"))
                query.append(" USING CONSISTENCY ").append(session.getConsistencyLevel().toString());

            cqlQuery = query.append(" WHERE KEY=?").toString();
        }

        byte[] key = generateKey();
        String formattedQuery = null;

        TimerContext context = session.latency.time();

        boolean success = false;
        String exceptionMessage = null;

        for (int t = 0; t < session.getRetryTimes(); t++)
        {
            if (success)
                break;

            try
            {
                CqlResult result = null;

                if (session.usePreparedStatements())
                {
                    Integer stmntId = getPreparedStatement(client, cqlQuery);
                    if (session.cqlVersion.startsWith("3"))
                        result = client.execute_prepared_cql3_query(stmntId, Collections.singletonList(ByteBufferUtil.bytes(getUnQuotedCqlBlob(key))), session.getConsistencyLevel());
                    else
                        result = client.execute_prepared_cql_query(stmntId, Collections.singletonList(ByteBufferUtil.bytes(getUnQuotedCqlBlob(key))));
                }
                else
                {
                    if (formattedQuery == null)
                        formattedQuery = formatCqlQuery(cqlQuery, Collections.singletonList(getUnQuotedCqlBlob(key)));

                    if (session.cqlVersion.startsWith("3"))
                        result = client.execute_cql3_query(ByteBuffer.wrap(formattedQuery.getBytes()), Compression.NONE, session.getConsistencyLevel());
                    else
                        result = client.execute_cql_query(ByteBuffer.wrap(formattedQuery.getBytes()), Compression.NONE);
                }

                assert result.type.equals(CqlResultType.ROWS) : "expected ROWS result type";
                assert result.rows.size() == 0 : "expected exactly one row";
                success = (result.rows.get(0).columns.size() != 0);
            }
            catch (Exception e)
            {
                exceptionMessage = getExceptionMessage(e);
                success = false;
            }
        }

        if (!success)
        {
            error(String.format("Operation [%d] retried %d times - error reading counter key %s %s%n",
                                index,
                                session.getRetryTimes(),
                                new String(key),
                                (exceptionMessage == null) ? "" : "(" + exceptionMessage + ")"));
        }

        session.operations.getAndIncrement();
        session.keys.getAndIncrement();
        context.stop();
    }
}
