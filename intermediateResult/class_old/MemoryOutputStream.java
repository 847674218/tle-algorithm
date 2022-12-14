package org.apache.cassandra.io.util;
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
import java.io.OutputStream;

/**
 * This class provides a way to stream the writes into the {@link Memory}
 */
public class MemoryOutputStream extends OutputStream
{

    private final Memory mem;
    private int position = 0;

    public MemoryOutputStream(Memory mem)
    {
        this.mem = mem;
    }

    public void write(int b)
    {
        mem.setByte(position++, (byte) b);
    }

    @Override
    public void write(byte[] b, int off, int len) throws IOException
    {
        mem.setBytes(position, b, off, len);
        position += len;
    }

    public int position()
    {
        return position;
    }
}
