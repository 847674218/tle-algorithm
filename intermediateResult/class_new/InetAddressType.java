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
package org.apache.cassandra.db.marshal;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;

import org.apache.cassandra.cql.jdbc.JdbcInetAddress;
import org.apache.cassandra.utils.ByteBufferUtil;

public class InetAddressType extends AbstractType<InetAddress>
{
    public static final InetAddressType instance = new InetAddressType();

    InetAddressType() {} // singleton

    public InetAddress compose(ByteBuffer bytes)
    {
        return JdbcInetAddress.instance.compose(bytes);
    }

    public ByteBuffer decompose(InetAddress value)
    {
        return JdbcInetAddress.instance.decompose(value);
    }

    public int compare(ByteBuffer o1, ByteBuffer o2)
    {
        return ByteBufferUtil.compareUnsigned(o1, o2);
    }

    public String getString(ByteBuffer bytes)
    {
        return JdbcInetAddress.instance.getString(bytes);
    }

    public ByteBuffer fromString(String source) throws MarshalException
    {
        // Return an empty ByteBuffer for an empty string.
        if (source.isEmpty())
            return ByteBufferUtil.EMPTY_BYTE_BUFFER;

        InetAddress address;

        try
        {
            address = InetAddress.getByName(source);
        }
        catch (Exception e)
        {
            throw new MarshalException(String.format("unable to make inetaddress from '%s'", source), e);
        }

        return decompose(address);
    }

    public void validate(ByteBuffer bytes) throws MarshalException
    {
        try
        {
            InetAddress.getByAddress(ByteBufferUtil.getArray(bytes));
        }
        catch (UnknownHostException e)
        {
            throw new MarshalException(String.format("Expected 4 or 16 byte inetaddress; got %s", ByteBufferUtil.bytesToHex(bytes)));
        }
    }
}
