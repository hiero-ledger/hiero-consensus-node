// SPDX-License-Identifier: Apache-2.0
package com.swirlds.common.threading.pool;

import com.swirlds.base.state.MutabilityException;
import com.swirlds.base.state.Mutable;
import com.swirlds.base.state.Startable;
import java.util.concurrent.Callable;
import org.hiero.base.concurrent.ThrowingRunnable;

/**
 * Used for executing tasks in parallel
 */
public interface ParallelExecutor extends Mutable, Startable {

    /**
     * Run multiple tasks in parallel, the first one in the current thread, and the others in background threads.
     *
     * <p>
     * This method returns only after all tasks have finished.
     * </p>
     *
     * @param errorHandler    a cleanup code to be executed if an exception gets thrown
     * @param foregroundTask  a task to execute in parallel, utilizing caller thread
     * @param backgroundTasks the tasks to execute in parallel, using pool thread
     * @return result of executing foregroundTask callable
     * @throws MutabilityException        if executed prior to object being started
     * @throws ParallelExecutionException if any of the invoked tasks throws an exception. if more throw an exception,
     *                                    then the foregroundTask exception will be the cause and the backgroundTask
     *                                    exception will be the suppressed exception (or first of background task
     *                                    exceptions will be used as a base)
     */
    <T> T doParallelWithHandler(
            final Runnable errorHandler, Callable<T> foregroundTask, ThrowingRunnable... backgroundTasks)
            throws ParallelExecutionException;

    /**
     * More concise version of {@link #doParallelWithHandler(Runnable, Callable, ThrowingRunnable...)}, which assumes
     * no-op handler of errors (so exception will be thrown, but no special code will get executed explicitly)
     */
    default <T> T doParallel(final Callable<T> foregroundTask, final ThrowingRunnable... backgroundTasks)
            throws ParallelExecutionException {
        return doParallelWithHandler(() -> {}, foregroundTask, backgroundTasks);
    }
}
