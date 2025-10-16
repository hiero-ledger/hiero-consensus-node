// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.systemtask;

import com.hedera.hapi.node.state.systemtask.SystemTask;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Optional;

/**
 * API for interacting with the SystemTaskService queue.
 */
public interface SystemTasks {
    /**
     * Offer a task to the tail of the system task queue.
     * @param task the task to enqueue
     */
    void offer(@NonNull SystemTask task);

    /**
     * Peek at the head of the queue without removing it.
     * @return the head element if present
     */
    @NonNull
    Optional<SystemTask> peek();

    /**
     * Poll the head of the queue, removing it if present.
     * @return the removed head element if present
     */
    @NonNull
    Optional<SystemTask> poll();
}

