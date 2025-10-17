// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.systemtask;

import static com.hedera.node.app.hapi.utils.EntityType.SYSTEM_TASK;
import static com.hedera.node.app.systemtask.schemas.V069SystemTaskSchema.SYSTEM_TASK_QUEUE_STATE_ID;

import com.hedera.hapi.node.state.systemtask.SystemTask;
import com.hedera.node.app.spi.ids.WritableEntityCounters;
import com.swirlds.state.spi.WritableQueueState;
import com.swirlds.state.spi.WritableStates;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;

/**
 * Writable accessor for the system task queue.
 */
public class WritableSystemTaskStore extends ReadableSystemTaskStore {
    private final WritableQueueState<SystemTask> systemTaskQueue;
    private final WritableEntityCounters entityCounters;

    public WritableSystemTaskStore(
            @NonNull final WritableStates states, @NonNull final WritableEntityCounters entityCounters) {
        super(states, entityCounters);
        this.systemTaskQueue = states.getQueue(SYSTEM_TASK_QUEUE_STATE_ID);
        this.entityCounters = entityCounters;
    }

    /**
     * Adds a task to the queue.
     */
    public void offer(@NonNull final SystemTask task) {
        systemTaskQueue.add(task);
        entityCounters.incrementEntityTypeCount(SYSTEM_TASK);
    }

    /**
     * Removes and returns the next task from the queue, or null if none available.
     */
    public @Nullable SystemTask poll() {
        final var task = systemTaskQueue.poll();
        if (task != null) {
            entityCounters.decrementEntityTypeCounter(SYSTEM_TASK);
        }
        return task;
    }

    /**
     * Returns direct access to the underlying writable queue.
     */
    public @NonNull WritableQueueState<SystemTask> queue() {
        return systemTaskQueue;
    }
}
