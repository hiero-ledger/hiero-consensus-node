// SPDX-License-Identifier: Apache-2.0
package com.swirlds.component.framework.schedulers.internal;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.function.Consumer;

/**
 * A task that is performed by a {@link SequentialThreadTaskScheduler}.
 *
 * @param handler the handler to call
 * @param data    the data to pass to the handler
 */
record SequentialThreadTask(@NonNull Consumer<Object> handler, @NonNull Object data) {

    /**
     * Handle the task.
     */
    public void handle() {
        handler.accept(data);
    }
}
