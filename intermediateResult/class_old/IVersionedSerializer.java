package org.apache.cassandra.io;
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


import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;

public interface IVersionedSerializer<T>
{
    /**
     * Serialize the specified type into the specified DataOutputStream instance.
     * @param t type that needs to be serialized
     * @param dos DataOutput into which serialization needs to happen.
     * @throws java.io.IOException
     */
    public void serialize(T t, DataOutput dos, int version) throws IOException;

    /**
     * Deserialize into the specified DataInputStream instance.
     * @param dis DataInput from which deserialization needs to happen.
     * @throws IOException
     * @return the type that was deserialized
     */
    public T deserialize(DataInput dis, int version) throws IOException;

    public long serializedSize(T t, int version);
}
