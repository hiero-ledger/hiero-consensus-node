// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.spi.systemtasks;

import com.hedera.hapi.node.state.systemtask.SystemTask;
import edu.umd.cs.findbugs.annotations.NonNull;

/**
 * Service Provider Interface for handling system tasks.
 */
public interface SystemTaskHandler {
    /**
     * Returns true if this handler can process the given task.
     *
     * @param task the task to check
     * @return whether this handler supports the task
     */
    boolean supports(@NonNull SystemTask task);

    /**
     * Handle the current task using the provided context.
     *
     * @param context the context for handling the task
     */
    void handle(@NonNull SystemTaskContext context);
}
