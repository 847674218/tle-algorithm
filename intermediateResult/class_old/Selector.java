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
package org.apache.cassandra.cql3.statements;

import com.google.common.base.Objects;

import org.apache.cassandra.cql3.ColumnIdentifier;

public interface Selector
{
    public enum Function
    {
        WRITE_TIME, TTL;

        @Override
        public String toString()
        {
            switch (this)
            {
                case WRITE_TIME:
                    return "writetime";
                case TTL:
                    return "ttl";
            }
            throw new AssertionError();
        }
    }

    public ColumnIdentifier id();
    public boolean hasFunction();
    public Function function();

    public static class WithFunction implements Selector
    {
        private final Function function;
        private final ColumnIdentifier id;

        public WithFunction(ColumnIdentifier id, Function function)
        {
            this.id = id;
            this.function = function;
        }

        public ColumnIdentifier id()
        {
            return id;
        }

        @Override
        public boolean hasFunction()
        {
            return true;
        }

        @Override
        public Function function()
        {
            return function;
        }

        @Override
        public final int hashCode()
        {
            return Objects.hashCode(function, id);
        }

        @Override
        public final boolean equals(Object o)
        {
            if(!(o instanceof WithFunction))
                return false;
            Selector that = (WithFunction)o;
            return id().equals(that.id()) && function() == that.function();
        }

        @Override
        public String toString()
        {
            return function + "(" + id + ")";
        }
    }
}
