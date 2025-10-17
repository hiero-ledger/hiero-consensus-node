// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.systemtask;

import static com.hedera.node.app.hapi.utils.EntityType.SYSTEM_TASK;
import static com.hedera.node.app.systemtask.schemas.V069SystemTaskSchema.SYSTEM_TASK_QUEUE_STATE_ID;
import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.state.systemtask.SystemTask;
import com.hedera.node.app.spi.ids.ReadableEntityCounters;
import com.swirlds.state.spi.ReadableQueueState;
import com.swirlds.state.spi.ReadableStates;
import edu.umd.cs.findbugs.annotations.NonNull;

/**
 * Readable accessor for the system task queue.
 */
public class ReadableSystemTaskStore {
    private final ReadableQueueState<SystemTask> systemTaskQueue;
    private final ReadableEntityCounters entityCounters;

    public ReadableSystemTaskStore(
            @NonNull final ReadableStates states, @NonNull final ReadableEntityCounters entityCounters) {
        this.entityCounters = requireNonNull(entityCounters);
        this.systemTaskQueue = requireNonNull(states).getQueue(SYSTEM_TASK_QUEUE_STATE_ID);
    }

    /**
     * Returns the underlying readable queue for system tasks.
     */
    public @NonNull ReadableQueueState<SystemTask> queue() {
        return systemTaskQueue;
    }

    public long numPendingTasks() {
        return entityCounters.getCounterFor(SYSTEM_TASK);
    }
}
