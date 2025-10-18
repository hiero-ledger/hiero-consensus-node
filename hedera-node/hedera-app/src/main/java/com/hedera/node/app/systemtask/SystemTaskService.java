// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.systemtask;

import static java.util.Objects.requireNonNull;

import com.hedera.node.app.systemtask.schemas.V069SystemTaskSchema;
import com.swirlds.state.lifecycle.SchemaRegistry;
import com.swirlds.state.lifecycle.Service;
import edu.umd.cs.findbugs.annotations.NonNull;

/**
 * Manages a FIFO queue of system tasks for deferred but deterministic processing.
 */
public final class SystemTaskService implements Service {
    public static final String NAME = "SystemTaskService";

    @Override
    public @NonNull String getServiceName() {
        return NAME;
    }

    @Override
    @SuppressWarnings({"unchecked", "rawtypes"})
    public void registerSchemas(@NonNull final SchemaRegistry registry) {
        requireNonNull(registry);
        registry.register(new V069SystemTaskSchema());
    }
}
