// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.systemtask;

import com.hedera.node.app.systemtask.schemas.V0690SystemTaskSchema;
import com.swirlds.state.lifecycle.SchemaRegistry;
import com.swirlds.state.lifecycle.Service;
import edu.umd.cs.findbugs.annotations.NonNull;

/**
 * A small-s System Task service that provides a FIFO queue of system tasks for
 * asynchronous background processing by the node.
 */
public final class SystemTaskService implements Service {
    public static final String NAME = "SystemTaskService";

    @Override
    public @NonNull String getServiceName() {
        return NAME;
    }

    @Override
    public void registerSchemas(@NonNull final SchemaRegistry registry) {
        registry.register(new V0690SystemTaskSchema());
    }
}

