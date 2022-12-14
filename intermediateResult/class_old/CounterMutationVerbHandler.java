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

package org.apache.cassandra.db;

import java.io.*;
import java.util.concurrent.TimeoutException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.apache.cassandra.config.DatabaseDescriptor;
import org.apache.cassandra.io.util.FastByteArrayInputStream;
import org.apache.cassandra.net.*;
import org.apache.cassandra.utils.FBUtilities;
import org.apache.cassandra.service.StorageProxy;
import org.apache.cassandra.thrift.UnavailableException;

public class CounterMutationVerbHandler implements IVerbHandler
{
    private static Logger logger = LoggerFactory.getLogger(CounterMutationVerbHandler.class);

    public void doVerb(final Message message, final String id)
    {
        byte[] bytes = message.getMessageBody();
        FastByteArrayInputStream buffer = new FastByteArrayInputStream(bytes);

        try
        {
            DataInputStream is = new DataInputStream(buffer);
            final CounterMutation cm = CounterMutation.serializer().deserialize(is, message.getVersion());
            if (logger.isDebugEnabled())
              logger.debug("Applying forwarded " + cm);

            String localDataCenter = DatabaseDescriptor.getEndpointSnitch().getDatacenter(FBUtilities.getBroadcastAddress());
            // We should not wait for the result of the write in this thread,
            // otherwise we could have a distributed deadlock between replicas
            // running this VerbHandler (see #4578).
            // Instead, we use a callback to send the response. Note that the callback
            // will not be called if the request timeout, but this is ok
            // because the coordinator of the counter mutation will timeout on
            // it's own in that case.
            StorageProxy.applyCounterMutationOnLeader(cm, localDataCenter, new Runnable(){
                public void run()
                {
                    try
                    {
                        WriteResponse response = new WriteResponse(cm.getTable(), cm.key(), true);
                        Message responseMessage = WriteResponse.makeWriteResponseMessage(message, response);
                        MessagingService.instance().sendReply(responseMessage, id, message.getFrom());
                    }
                    catch (IOException e)
                    {
                        logger.error("Error writing response to counter mutation", e);
                    }
                }
            });
        }
        catch (UnavailableException e)
        {
            // We check for UnavailableException in the coordinator not. It is
            // hence reasonable to let the coordinator timeout in the very
            // unlikely case we arrive here
        }
        catch (TimeoutException e)
        {
            // The coordinator will timeout on it's own so ignore
        }
        catch (IOException e)
        {
            logger.error("Error in counter mutation", e);
        }
    }
}
