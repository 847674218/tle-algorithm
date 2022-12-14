/*
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
 */
package org.apache.cassandra.cql3;

import java.nio.ByteBuffer;
import java.util.List;

import org.apache.cassandra.thrift.InvalidRequestException;

/**
 * Build a potentially composite column name.
 */
public interface ColumnNameBuilder
{
    /**
     * Add a new ByteBuffer as the next component for this name.
     * @param bb the ByteBuffer to add
     * @throws IllegalStateException if the builder if full, i.e. if enough component has been added.
     * @return this builder
     */
    public ColumnNameBuilder add(ByteBuffer bb);

    /**
     * Add a new Term as the next component for this name.
     * @param t the Term to add
     * @param op the relationship this component should respect.
     * @param variables the variables corresponding to prepared markers
     * @throws IllegalStateException if the builder if full, i.e. if enough component has been added.
     * @return this builder
     */
    public ColumnNameBuilder add(Term t, Relation.Type op, List<ByteBuffer> variables) throws InvalidRequestException;

    /**
     * Returns the number of component already added to this builder.
     * @return the number of component in this Builder
     */
    public int componentCount();

    /**
     * Build the column name.
     * @return the built column name
     */
    public ByteBuffer build();

    /**
     * Build the column name so that the result sorts at the end of the range
     * represented by this (uncomplete) column name.
     * @throws IllegalStateException if the builder is empty or full.
     */
    public ByteBuffer buildAsEndOfRange();

    /**
     * Clone this builder.
     * @return the cloned builder.
     */
    public ColumnNameBuilder copy();
}
