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
     * Run two tasks in parallel
     *
     * @param foregroundTask a task to execute in parallel, utilizing caller thread
     * @param backgroundTask a task to execute in parallel, using pool thread
     * @throws MutabilityException        if executed prior to object being started
     * @throws ParallelExecutionException if anything goes wrong
     */
    <T> T doParallel(Callable<T> foregroundTask, Callable<Void> backgroundTask) throws ParallelExecutionException;

    /**
     * Run number of backgroundTasks in parallel. See {@link #doParallel(Callable, Callable, Runnable)} for details of behaviour
     *
     * @param foregroundTask  a task to execute in parallel, utilizing caller thread
     * @param backgroundTasks number of backgroundTasks to execute in parallel, using pool threads
     * @throws MutabilityException        if executed prior to object being started
     * @throws ParallelExecutionException if anything goes wrong
     */
    void doParallel(final Runnable onThrow, ThrowingRunnable foregroundTask, ThrowingRunnable... backgroundTasks)
            throws ParallelExecutionException;

    /**
     * Same as {@link #doParallel(Callable, Callable, Runnable)} but without a return type
     */
    default void doParallel(
            final ThrowingRunnable foregroundTask, final ThrowingRunnable backgroundTask, final Runnable onThrow)
            throws ParallelExecutionException {
        doParallel(foregroundTask, (Callable<Void>) backgroundTask, onThrow);
    }

    /**
     * Run two tasks in parallel, the first one in the current thread, and the second in a background thread.
     *
     * <p>
     * This method returns only after both have finished.
     * </p>
     *
     * @param foregroundTask a task to execute in parallel, utilizing caller thread
     * @param backgroundTask a task to execute in parallel, using pool thread
     * @param onThrow        a cleanup task to be executed if an exception gets thrown. if the foreground task throws an
     *                       exception, this could be used to stop the background task, but not vice versa
     * @throws MutabilityException        if executed prior to object being started
     * @throws ParallelExecutionException if either of the invoked tasks throws an exception. if both throw an
     *                                    exception, then the foregroundTask exception will be the cause and the
     *                                    backgroundTask exception will be the suppressed exception
     */
    default <T> T doParallel(
            final Callable<T> foregroundTask, final Callable<Void> backgroundTask, final Runnable onThrow)
            throws ParallelExecutionException {
        throw new UnsupportedOperationException("not implemented");
    }
}
