// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.systemtask.dispatcher;

import static java.util.Objects.requireNonNull;

import com.hedera.node.app.spi.systemtasks.SystemTaskContext;
import com.hedera.node.app.spi.systemtasks.SystemTaskHandler;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.List;
import javax.inject.Inject;
import javax.inject.Singleton;

/**
 * Dispatches a {@link SystemTaskContext} to a matching {@link SystemTaskHandler}.
 */
@Singleton
public class SystemTaskDispatcher {

    private final List<SystemTaskHandler> handlers;

    @Inject
    public SystemTaskDispatcher(@NonNull final List<SystemTaskHandler> handlers) {
        this.handlers = requireNonNull(handlers);
    }

    /**
     * Finds a handler that {@link SystemTaskHandler#supports} the current task
     * in the given context, and invokes it. Throws {@link UnsupportedOperationException}
     * if no suitable handler is found.
     *
     * @param context the system task context
     */
    public void dispatch(@NonNull final SystemTaskContext context) {
        requireNonNull(context, "context must not be null");
        final var task = context.currentTask();
        for (final var handler : handlers) {
            if (handler.supports(task)) {
                handler.handle(context);
                return;
            }
        }
        throw new UnsupportedOperationException("No SystemTaskHandler supports the given task");
    }
}
