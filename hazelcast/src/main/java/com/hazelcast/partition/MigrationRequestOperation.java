/*
 * Copyright (c) 2008-2013, Hazelcast, Inc. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.hazelcast.partition;

import com.hazelcast.core.Member;
import com.hazelcast.core.MemberLeftException;
import com.hazelcast.nio.Address;
import com.hazelcast.nio.IOUtil;
import com.hazelcast.nio.ObjectDataInput;
import com.hazelcast.nio.ObjectDataOutput;
import com.hazelcast.nio.serialization.SerializationService;
import com.hazelcast.spi.*;
import com.hazelcast.spi.impl.NodeEngineImpl;

import java.io.IOException;
import java.util.Collection;
import java.util.LinkedList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;

public final class MigrationRequestOperation extends BaseMigrationOperation {

    public MigrationRequestOperation() {
    }

    public MigrationRequestOperation(MigrationInfo migrationInfo) {
        super(migrationInfo);
    }

    public void run() {
        final Address from = migrationInfo.getFromAddress();
        final Address to = migrationInfo.getToAddress();
        if (to.equals(from)) {
            getLogger().log(Level.FINEST, "To and from addresses are same! => " + toString());
            success = false;
            return;
        }
        if (from == null) {
            getLogger().log(Level.FINEST, "From address is null => " + toString());
        }
        if (migrationInfo.startProcessing()) {
            try {
                final PartitionServiceImpl partitionService = getService();
                Member target = partitionService.getMember(to);
                if (target == null) {
                    getLogger().log(Level.WARNING, "Target member of task could not be found! => " + toString());
                    success = false;
                    return;
                }
                partitionService.addActiveMigration(migrationInfo);
                final NodeEngine nodeEngine = getNodeEngine();
                final long timeout = nodeEngine.getGroupProperties().PARTITION_MIGRATION_TIMEOUT.getLong();
                final Collection<Operation> tasks = prepareMigrationTasks();
                if (tasks.size() > 0) {
                    final SerializationService serializationService = nodeEngine.getSerializationService();
                    final ObjectDataOutput out = serializationService.createObjectDataOutput(1024 * 32);
                    try {
                        out.writeInt(tasks.size());
                        for (Operation task : tasks) {
                            serializationService.writeObject(out, task);
                        }
                        final byte[] data = IOUtil.compress(out.toByteArray());
                        final MigrationOperation migrationOperation = new MigrationOperation(migrationInfo, data, tasks.size());
                        Invocation inv = nodeEngine.getOperationService().createInvocationBuilder(PartitionServiceImpl.SERVICE_NAME,
                                migrationOperation, to)
                                .setTryCount(3).setTryPauseMillis(1000).setReplicaIndex(getReplicaIndex()).build();
                        Future future = inv.invoke();
                        success = (Boolean) nodeEngine.toObject(future.get(timeout, TimeUnit.SECONDS));
                    } finally {
                        IOUtil.closeResource(out);
                    }
                } else {
                    success = true;
                }
            } catch (Throwable e) {
                if (e instanceof ExecutionException) {
                    e = e.getCause() != null ? e.getCause() : e;
                }
                Level level = (e instanceof MemberLeftException || e instanceof InterruptedException)
                        || !getNodeEngine().isActive() ? Level.FINEST : Level.WARNING;
                getLogger().log(level, e.getMessage(), e);
                success = false;
            } finally {
                migrationInfo.doneProcessing();
            }
        } else {
            getLogger().log(Level.WARNING, "Migration is cancelled -> " + migrationInfo);
            success = false;
        }
    }

    @Override
    public Object getResponse() {
        return success;
    }

    @Override
    public boolean returnsResponse() {
        return true;
    }

    private Collection<Operation> prepareMigrationTasks() {
        NodeEngineImpl nodeEngine = (NodeEngineImpl) getNodeEngine();
        final MigrationServiceEvent event = new MigrationServiceEvent(MigrationEndpoint.SOURCE, migrationInfo);
        final Collection<Operation> tasks = new LinkedList<Operation>();
        for (MigrationAwareService service : nodeEngine.getServices(MigrationAwareService.class)) {
            final Operation op = service.prepareMigrationOperation(event);
            if (op != null) {
                op.setServiceName(service.getServiceName());
                service.beforeMigration(event);
                tasks.add(op);
            }
        }
        return tasks;
    }

    protected void writeInternal(ObjectDataOutput out) throws IOException {
        super.writeInternal(out);
    }

    protected void readInternal(ObjectDataInput in) throws IOException {
        super.readInternal(in);
    }
}
