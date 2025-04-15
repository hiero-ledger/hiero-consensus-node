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
     * @param task1 a task to execute in parallel
     * @param task2 a task to execute in parallel
     * @throws MutabilityException        if executed prior to object being started
     * @throws ParallelExecutionException if anything goes wrong
     */
    <T> T doParallel(Callable<T> task1, Callable<Void> task2) throws ParallelExecutionException;

    /**
     * Same as {@link #doParallel(Callable, Callable, Runnable)} but without a return type
     */
    default void doParallel(final ThrowingRunnable task1, final ThrowingRunnable task2, final Runnable onThrow)
            throws ParallelExecutionException {
        doParallel(task1, (Callable<Void>) task2, onThrow);
    }

    /**
     * Run two tasks in parallel
     *
     * @param task1
     * 		a task to execute in parallel
     * @param task2
     * 		a task to execute in parallel
     * @param onThrow
     * 		a task to run if an exception gets thrown
     * @throws MutabilityException
     * 		if executed prior to object being started
     * @throws ParallelExecutionException
     * 		if anything goes wrong
     */
    default <T> T doParallel(final Callable<T> task1, final Callable<Void> task2, final Runnable onThrow)
            throws ParallelExecutionException {
        throw new UnsupportedOperationException("not implemented");
    }
}
