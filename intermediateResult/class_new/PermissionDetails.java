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
package org.apache.cassandra.auth;

import com.google.common.base.Objects;
import com.google.common.collect.ComparisonChain;

/**
 *  Sets of instances of this class are returned by IAuthorizer.listPermissions() method for LIST PERMISSIONS query.
 *  None of the fields are nullable.
 */
public class PermissionDetails implements Comparable<PermissionDetails>
{
    public final String username;
    public final IResource resource;
    public final Permission permission;

    public PermissionDetails(String username, IResource resource, Permission permission)
    {
        this.username = username;
        this.resource = resource;
        this.permission = permission;
    }

    @Override
    public int compareTo(PermissionDetails other)
    {
        return ComparisonChain.start()
                              .compare(username, other.username)
                              .compare(resource.getName(), other.resource.getName())
                              .compare(permission, other.permission)
                              .result();
    }

    @Override
    public String toString()
    {
        return String.format("<PermissionDetails username:%s resource:%s permission:%s>",
                             username,
                             resource.getName(),
                             permission);
    }

    @Override
    public boolean equals(Object o)
    {
        return Objects.equal(this, o);
    }

    @Override
    public int hashCode()
    {
        return Objects.hashCode(username, resource, permission);
    }
}
