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
package org.apache.cassandra.transport;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import com.google.common.base.Splitter;

import org.apache.cassandra.db.ConsistencyLevel;
import org.apache.cassandra.db.marshal.Int32Type;
import org.apache.cassandra.db.marshal.UTF8Type;
import org.apache.cassandra.transport.messages.CredentialsMessage;
import org.apache.cassandra.transport.messages.ExecuteMessage;
import org.apache.cassandra.transport.messages.OptionsMessage;
import org.apache.cassandra.transport.messages.PrepareMessage;
import org.apache.cassandra.transport.messages.QueryMessage;
import org.apache.cassandra.transport.messages.RegisterMessage;
import org.apache.cassandra.transport.messages.StartupMessage;
import org.apache.cassandra.utils.Hex;
import static org.apache.cassandra.config.EncryptionOptions.ClientEncryptionOptions;

public class Client extends SimpleClient
{
    public Client(String host, int port, ClientEncryptionOptions encryptionOptions)
    {
        super(host, port, encryptionOptions);
    }

    public void run() throws IOException
    {
        // Start the connection attempt.
        System.out.print("Connecting...");
        establishConnection();
        System.out.println();

        // Read commands from the stdin.
        BufferedReader in = new BufferedReader(new InputStreamReader(System.in));
        for (;;)
        {
            System.out.print(">> ");
            System.out.flush();
            String line = in.readLine();
            if (line == null) {
                break;
            }
            Message.Request req = parseLine(line.trim());
            if (req == null)
            {
                System.out.println("! Error parsing line.");
                continue;
            }

            try
            {
                Message.Response resp = execute(req);
                System.out.println("-> " + resp);
            }
            catch (Exception e)
            {
                System.err.println("ERROR: " + e.getMessage());
            }
        }

        close();
    }

    private Message.Request parseLine(String line)
    {
        Splitter splitter = Splitter.on(' ').trimResults().omitEmptyStrings();
        Iterator<String> iter = splitter.split(line).iterator();
        if (!iter.hasNext())
            return null;
        String msgType = iter.next().toUpperCase();
        if (msgType.equals("STARTUP"))
        {
            Map<String, String> options = new HashMap<String, String>();
            options.put(StartupMessage.CQL_VERSION, "3.0.0");
            while (iter.hasNext())
            {
               String next = iter.next();
               if (next.toLowerCase().equals("snappy"))
               {
                   options.put(StartupMessage.COMPRESSION, "snappy");
                   connection.setCompressor(FrameCompressor.SnappyCompressor.instance);
               }
            }
            return new StartupMessage(options);
        }
        else if (msgType.equals("QUERY"))
        {
            String query = line.substring(6);
            return new QueryMessage(query, ConsistencyLevel.ONE);
        }
        else if (msgType.equals("PREPARE"))
        {
            String query = line.substring(8);
            return new PrepareMessage(query);
        }
        else if (msgType.equals("EXECUTE"))
        {
            try
            {
                byte[] id = Hex.hexToBytes(iter.next());
                List<ByteBuffer> values = new ArrayList<ByteBuffer>();
                while(iter.hasNext())
                {
                    String next = iter.next();
                    ByteBuffer bb;
                    try
                    {
                        int v = Integer.parseInt(next);
                        bb = Int32Type.instance.decompose(v);
                    }
                    catch (NumberFormatException e)
                    {
                        bb = UTF8Type.instance.decompose(next);
                    }
                    values.add(bb);
                }
                return new ExecuteMessage(id, values, ConsistencyLevel.ONE);
            }
            catch (Exception e)
            {
                return null;
            }
        }
        else if (msgType.equals("OPTIONS"))
        {
            return new OptionsMessage();
        }
        else if (msgType.equals("CREDENTIALS"))
        {
            CredentialsMessage msg = new CredentialsMessage();
            while (iter.hasNext())
            {
                String next = iter.next();
                String[] kv = next.split("=");
                if (kv.length != 2)
                    return null;
                msg.credentials.put(kv[0], kv[1]);
            }
            return msg;
        }
        else if (msgType.equals("REGISTER"))
        {
            String type = line.substring(9).toUpperCase();
            try
            {
                return new RegisterMessage(Collections.singletonList(Enum.valueOf(Event.Type.class, type)));
            }
            catch (IllegalArgumentException e)
            {
                System.err.println("[ERROR] Unknown event type: " + type);
                return null;
            }
        }
        return null;
    }

    public static void main(String[] args) throws Exception
    {
        // Print usage if no argument is specified.
        if (args.length != 2)
        {
            System.err.println("Usage: " + Client.class.getSimpleName() + " <host> <port>");
            return;
        }

        // Parse options.
        String host = args[0];
        int port = Integer.parseInt(args[1]);

        ClientEncryptionOptions encryptionOptions = new ClientEncryptionOptions();
        System.out.println("CQL binary protocol console " + host + "@" + port);

        new Client(host, port, encryptionOptions).run();
        System.exit(0);
    }
}
