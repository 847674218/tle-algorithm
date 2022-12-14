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
import java.util.HashMap;
import java.util.Map;

import com.google.common.collect.ImmutableMap;

import org.apache.cassandra.exceptions.ConfigurationException;
import org.apache.cassandra.exceptions.SyntaxException;
import org.apache.cassandra.utils.ByteBufferUtil;

public class ColumnToCollectionType extends AbstractType<ByteBuffer>
{
    // interning instances
    private static final Map<Map<ByteBuffer, CollectionType>, ColumnToCollectionType> instances = new HashMap<Map<ByteBuffer, CollectionType>, ColumnToCollectionType>();

    public final Map<ByteBuffer, CollectionType> defined;

    public static ColumnToCollectionType getInstance(TypeParser parser) throws SyntaxException, ConfigurationException
    {
        return getInstance(parser.getCollectionsParameters());
    }

    public static synchronized ColumnToCollectionType getInstance(Map<ByteBuffer, CollectionType> defined)
    {
        assert defined != null;

        ColumnToCollectionType t = instances.get(defined);
        if (t == null)
        {
            t = new ColumnToCollectionType(defined);
            instances.put(defined, t);
        }
        return t;
    }

    private ColumnToCollectionType(Map<ByteBuffer, CollectionType> defined)
    {
        this.defined = ImmutableMap.copyOf(defined);
    }

    public int compare(ByteBuffer o1, ByteBuffer o2)
    {
        throw new UnsupportedOperationException("ColumnToCollectionType should only be used in composite types, never alone");
    }

    public int compareCollectionMembers(ByteBuffer o1, ByteBuffer o2, ByteBuffer collectionName)
    {
        CollectionType t = defined.get(collectionName);
        if (t == null)
            throw new RuntimeException(ByteBufferUtil.bytesToHex(collectionName) + " is not defined as a collection");

        return t.nameComparator().compare(o1, o2);
    }

    public ByteBuffer compose(ByteBuffer bytes)
    {
        return BytesType.instance.compose(bytes);
    }

    public ByteBuffer decompose(ByteBuffer value)
    {
        return BytesType.instance.decompose(value);
    }

    public String getString(ByteBuffer bytes)
    {
        return BytesType.instance.getString(bytes);
    }

    public ByteBuffer fromString(String source)
    {
        try
        {
            return ByteBufferUtil.hexToBytes(source);
        }
        catch (NumberFormatException e)
        {
            throw new MarshalException(String.format("cannot parse '%s' as hex bytes", source), e);
        }
    }

    public void validate(ByteBuffer bytes)
    {
        throw new UnsupportedOperationException("ColumnToCollectionType should only be used in composite types, never alone");
    }

    public void validateCollectionMember(ByteBuffer bytes, ByteBuffer collectionName) throws MarshalException
    {
        CollectionType t = defined.get(collectionName);
        if (t == null)
            throw new MarshalException(ByteBufferUtil.bytesToHex(collectionName) + " is not defined as a collection");

        t.nameComparator().validate(bytes);
    }

    @Override
    public boolean isCompatibleWith(AbstractType<?> previous)
    {
        if (!(previous instanceof ColumnToCollectionType))
            return false;

        ColumnToCollectionType prev = (ColumnToCollectionType)previous;
        // We are compatible if we have all the definitions previous have (but we can have more).
        for (Map.Entry<ByteBuffer, CollectionType> entry : prev.defined.entrySet())
        {
            if (!entry.getValue().isCompatibleWith(defined.get(entry.getKey())))
                return false;
        }
        return true;
    }

    @Override
    public String toString()
    {
        return getClass().getName() + TypeParser.stringifyCollectionsParameters(defined);
    }
}
