// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.systemtask;

import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.state.systemtask.SystemTask;
import com.swirlds.state.spi.WritableQueueState;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Optional;

/**
 * Default implementation backed by the SystemTaskService queue state.
 */
public class SystemTasksImpl implements SystemTasks {

    private final WritableQueueState<SystemTask> queue;

    /**
     * Construct with the underlying writable queue state from SystemTaskService.
     * @param queue the writable queue state
     */
    public SystemTasksImpl(@NonNull final WritableQueueState<SystemTask> queue) {
        this.queue = requireNonNull(queue);
    }

    @Override
    public void offer(@NonNull final SystemTask task) {
        requireNonNull(task);
        queue.add(task);
    }

    @Override
    @NonNull
    public Optional<SystemTask> peek() {
        return Optional.ofNullable(queue.peek());
    }

    @Override
    @NonNull
    public Optional<SystemTask> poll() {
        return Optional.ofNullable(queue.poll());
    }
}
