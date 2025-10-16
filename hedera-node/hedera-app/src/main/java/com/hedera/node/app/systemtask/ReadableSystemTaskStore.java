// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.systemtask;

import static com.hedera.node.app.systemtask.schemas.V0690SystemTaskSchema.SYSTEM_TASK_QUEUE_STATE_ID;

import com.hedera.hapi.node.state.systemtask.SystemTask;
import com.swirlds.state.spi.ReadableQueueState;
import com.swirlds.state.spi.ReadableStates;
import edu.umd.cs.findbugs.annotations.NonNull;

/** Readable accessor for the system task queue. */
public class ReadableSystemTaskStore {
    private final ReadableQueueState<SystemTask> systemTaskQueue;

    public ReadableSystemTaskStore(@NonNull final ReadableStates states) {
        this.systemTaskQueue = states.getQueue(SYSTEM_TASK_QUEUE_STATE_ID);
    }

    /** Returns the underlying readable queue for system tasks. */
    public @NonNull ReadableQueueState<SystemTask> queue() {
        return systemTaskQueue;
    }
}

