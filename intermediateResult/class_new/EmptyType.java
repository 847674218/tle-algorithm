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

import java.nio.ByteBuffer;

import org.apache.cassandra.utils.ByteBufferUtil;

/**
 * A type that only accept empty data.
 * It is only useful as a value validation type, not as a comparator since column names can't be empty.
 */
public class EmptyType extends AbstractType<Void>
{
    public static final EmptyType instance = new EmptyType();

    private EmptyType() {} // singleton

    public Void compose(ByteBuffer bytes)
    {
        return null;
    }

    public ByteBuffer decompose(Void value)
    {
        return ByteBufferUtil.EMPTY_BYTE_BUFFER;
    }

    public int compare(ByteBuffer o1, ByteBuffer o2)
    {
        return 0;
    }

    public String getString(ByteBuffer bytes)
    {
        return "";
    }

    public ByteBuffer fromString(String source) throws MarshalException
    {
        if (!source.isEmpty())
            throw new MarshalException(String.format("'%s' is not empty", source));

        return ByteBufferUtil.EMPTY_BYTE_BUFFER;
    }

    public void validate(ByteBuffer bytes) throws MarshalException
    {
        if (bytes.remaining() > 0)
            throw new MarshalException("EmptyType only accept empty values");
    }
}
