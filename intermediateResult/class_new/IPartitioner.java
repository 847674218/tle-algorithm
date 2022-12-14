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
package org.apache.cassandra.dht;

import java.nio.ByteBuffer;
import java.util.List;
import java.util.Map;

import org.apache.cassandra.db.DecoratedKey;

public interface IPartitioner<T extends Token>
{
    /**
     * @deprecated Used by SSTables before version 'e'.
     *
     * Convert the on disk representation to a DecoratedKey object
     * @param key On disk representation
     * @return DecoratedKey object
     */
    public DecoratedKey convertFromDiskFormat(ByteBuffer key);

    /**
     * Transform key to object representation of the on-disk format.
     *
     * @param key the raw, client-facing key
     * @return decorated version of key
     */
    public DecoratedKey decorateKey(ByteBuffer key);

    /**
     * Calculate a Token representing the approximate "middle" of the given
     * range.
     *
     * @return The approximate midpoint between left and right.
     */
    public Token midpoint(Token left, Token right);

    /**
     * @return The minimum possible Token in the range that is being partitioned.
     */
    public T getMinimumToken();

    /**
     * @return a Token that can be used to route a given key
     * (This is NOT a method to create a Token from its string representation;
     * for that, use TokenFactory.fromString.)
     */
    public T getToken(ByteBuffer key);

    /**
     * @return a randomly generated token
     */
    public T getRandomToken();

    public Token.TokenFactory getTokenFactory();

    /**
     * @return True if the implementing class preserves key order in the Tokens
     * it generates.
     */
    public boolean preservesOrder();

    /**
     * Calculate the deltas between tokens in the ring in order to compare
     *  relative sizes.
     *
     * @param sortedTokens a sorted List of Tokens
     * @return the mapping from 'token' to 'percentage of the ring owned by that token'.
     */
    public Map<Token, Float> describeOwnership(List<Token> sortedTokens);

    public <R extends RingPosition> R minValue(Class<R> klass);
}
