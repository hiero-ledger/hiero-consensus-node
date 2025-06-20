// SPDX-License-Identifier: Apache-2.0
package com.swirlds.base.internal;

import edu.umd.cs.findbugs.annotations.NonNull;

/**
 * Interface that is supported by {@link com.swirlds.base.internal.BaseExecutorFactory} to provide additional
 * information about the task. Can be combined with {@link Runnable} or {@link java.util.concurrent.Callable}.
 */
public interface BaseTask {

    /**
     * Default type
     */
    String DEFAULT_TYPE = "unknown";

    /**
     * Get the type of the task.
     *
     * @return the type of the task
     */
    @NonNull
    default String getType() {
        return DEFAULT_TYPE;
    }
}
