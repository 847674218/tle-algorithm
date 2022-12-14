package org.apache.cassandra.db.marshal;
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


import java.nio.ByteBuffer;

import org.apache.cassandra.cql.jdbc.JdbcAscii;

public class AsciiType extends AbstractType<String>
{
    public static final AsciiType instance = new AsciiType();

    AsciiType() {} // singleton

    public String getString(ByteBuffer bytes)
    {
        try
        {
            return JdbcAscii.instance.getString(bytes);
        }
        catch (org.apache.cassandra.cql.jdbc.MarshalException e)
        {
            throw new MarshalException(e.getMessage());
        }
    }

    public int compare(ByteBuffer o1, ByteBuffer o2)
    {
        return BytesType.bytesCompare(o1, o2);
    }

    public String compose(ByteBuffer bytes)
    {
        return JdbcAscii.instance.getString(bytes);
    }

    public ByteBuffer decompose(String value)
    {
        return JdbcAscii.instance.decompose(value);
    }

    public ByteBuffer fromString(String source)
    {
        return decompose(source);
    }

    public void validate(ByteBuffer bytes) throws MarshalException
    {
        // 0-127
        for (int i = bytes.position(); i < bytes.limit(); i++)
        {
            byte b = bytes.get(i);
            if (b < 0 || b > 127)
                throw new MarshalException("Invalid byte for ascii: " + Byte.toString(b));
        }
    }
}
