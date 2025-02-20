// SPDX-License-Identifier: Apache-2.0
package com.swirlds.base.test.fixtures.concurrent;

import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeoutException;

/**
 * A test executor that can be used to run tasks in parallel. The executor is responsible for managing the threads that
 * are used to run the tasks. The executor is also responsible for waiting for the tasks to complete. All threads that
 * are used are daemon threads. All blocking methods calls will block for a maximum duration that is defined when
 * creating the executor.
 */
public interface TestExecutor {

    /**
     * Executes the given runnables in parallel. This method will block until all the callables have completed. If the
     * callables do not return within the maximum wait time, then the callables will be cancelled and a
     * {@link TimeoutException} will be thrown.
     *
     * @param runnables the runnables to execute
     */
    void executeAndWait(@NonNull Collection<Runnable> runnables);

    /**
     * Executes the given runnables in parallel. This method will block until all the callables have completed. If the
     * callables do not return within the maximum wait time, then the callables will be cancelled and a
     * {@link TimeoutException} will be thrown.
     *
     * @param runnables the runnables to execute
     */
    void executeAndWait(@NonNull Runnable... runnables);

    /**
     * Executes the given callables in parallel. This method will block until all the callables have completed. If the
     * callables do not return within the maximum wait time, then the callables will be cancelled and a
     * {@link TimeoutException} will be thrown.
     *
     * @param callables the callables to execute
     * @param <V>       the type of the callable result
     * @return the results of the callables
     */
    <V> @NonNull List<V> submitAndWait(Collection<Callable<V>> callables);

    /**
     * Executes the given callable in parallel. This method will block until all the callable have completed. If the
     * callable do not return within the maximum wait time, then the callable will be cancelled and a
     * {@link TimeoutException} will be thrown.
     *
     * @param callable a callable array to execute
     * @param <V>      the type of the callable result
     * @return the result of the callable
     */
    <V> @Nullable List<V> submitAndWait(@NonNull Callable<V>... callable);
}
