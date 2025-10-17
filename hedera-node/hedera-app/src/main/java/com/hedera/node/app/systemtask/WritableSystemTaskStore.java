// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.systemtask;

import static com.hedera.node.app.systemtask.schemas.V0690SystemTaskSchema.SYSTEM_TASK_QUEUE_STATE_ID;

import com.hedera.hapi.node.state.systemtask.SystemTask;
import com.swirlds.state.spi.WritableQueueState;
import com.swirlds.state.spi.WritableStates;
import edu.umd.cs.findbugs.annotations.NonNull;

/** Writable accessor for the system task queue. */
public class WritableSystemTaskStore {
    private final WritableQueueState<SystemTask> systemTaskQueue;

    public WritableSystemTaskStore(@NonNull final WritableStates states) {
        this.systemTaskQueue = states.getQueue(SYSTEM_TASK_QUEUE_STATE_ID);
    }

    /** Adds a task to the queue. */
    public void enqueue(@NonNull final SystemTask task) {
        systemTaskQueue.add(task);
    }

    /** Removes and returns the next task from the queue, or null if none available. */
    public SystemTask dequeue() {
        return systemTaskQueue.poll();
    }

    /** Returns direct access to the underlying writable queue. */
    public @NonNull WritableQueueState<SystemTask> queue() {
        return systemTaskQueue;
    }
}
