// SPDX-License-Identifier: Apache-2.0
package com.swirlds.common.threading.pool;

import java.time.Instant;

/**
 * An exception thrown by @{@link ParallelExecutor} when one or both of the tasks fails. Since these tasks can fail at
 * different times, a timestamp is added to this exception.
 */
public class ParallelExecutionException extends Exception {
    /**
     * @param cause
     * 		the original exception
     * @param time
     * 		the time to attach to the message
     */
    public ParallelExecutionException(final Throwable cause, final Instant time) {
        super("Time thrown: " + time.toString(), cause);
    }

    /**
     * @param cause
     * 		the original exception
     */
    public ParallelExecutionException(final Throwable cause) {
        this(cause, Instant.now());
    }
}
