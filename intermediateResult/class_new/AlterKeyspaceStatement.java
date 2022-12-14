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
package org.apache.cassandra.cql3.statements;

import org.apache.cassandra.auth.Permission;
import org.apache.cassandra.config.DatabaseDescriptor;
import org.apache.cassandra.config.KSMetaData;
import org.apache.cassandra.config.Schema;
import org.apache.cassandra.cql3.KSPropDefs;
import org.apache.cassandra.db.Table;
import org.apache.cassandra.exceptions.*;
import org.apache.cassandra.locator.AbstractReplicationStrategy;
import org.apache.cassandra.service.ClientState;
import org.apache.cassandra.service.MigrationManager;
import org.apache.cassandra.service.StorageService;
import org.apache.cassandra.transport.messages.ResultMessage;

public class AlterKeyspaceStatement extends SchemaAlteringStatement
{
    private final String name;
    private final KSPropDefs attrs;

    public AlterKeyspaceStatement(String name, KSPropDefs attrs)
    {
        super();
        this.name = name;
        this.attrs = attrs;
    }

    @Override
    public String keyspace()
    {
        return name;
    }

    public void checkAccess(ClientState state) throws UnauthorizedException, InvalidRequestException
    {
        state.hasKeyspaceAccess(name, Permission.ALTER);
    }

    @Override
    public void validate(ClientState state) throws RequestValidationException
    {
        super.validate(state);

        KSMetaData ksm = Schema.instance.getKSMetaData(name);
        if (ksm == null)
            throw new InvalidRequestException("Unknown keyspace " + name);
        if (ksm.name.equalsIgnoreCase(Table.SYSTEM_KS))
            throw new InvalidRequestException("Cannot alter system keyspace");

        attrs.validate();

        if (attrs.getReplicationStrategyClass() == null && !attrs.getReplicationOptions().isEmpty())
        {
            throw new ConfigurationException("Missing replication strategy class");
        }
        else if (attrs.getReplicationStrategyClass() != null)
        {
            // trial run to let ARS validate class + per-class options
            AbstractReplicationStrategy.createReplicationStrategy(name,
                                                                  AbstractReplicationStrategy.getClass(attrs.getReplicationStrategyClass()),
                                                                  StorageService.instance.getTokenMetadata(),
                                                                  DatabaseDescriptor.getEndpointSnitch(),
                                                                  attrs.getReplicationOptions());
        }
    }

    public void announceMigration() throws RequestValidationException
    {
        KSMetaData ksm = Schema.instance.getKSMetaData(name);
        // In the (very) unlikely case the keyspace was dropped since validate()
        if (ksm == null)
            throw new InvalidRequestException("Unknown keyspace " + name);

        MigrationManager.announceKeyspaceUpdate(attrs.asKSMetadataUpdate(ksm));
    }

    public ResultMessage.SchemaChange.Change changeType()
    {
        return ResultMessage.SchemaChange.Change.UPDATED;
    }
}
