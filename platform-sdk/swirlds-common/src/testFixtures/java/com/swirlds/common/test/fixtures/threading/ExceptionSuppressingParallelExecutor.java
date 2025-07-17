// SPDX-License-Identifier: Apache-2.0
package com.swirlds.common.test.fixtures.threading;

import com.swirlds.common.threading.manager.ThreadManager;
import com.swirlds.common.threading.pool.CachedPoolParallelExecutor;
import com.swirlds.common.threading.pool.ParallelExecutionException;
import com.swirlds.common.threading.pool.ParallelExecutor;
import java.util.concurrent.Callable;
import org.hiero.base.concurrent.ThrowingRunnable;

/**
 * Parallel executor that suppressed all exceptions.
 */
public class ExceptionSuppressingParallelExecutor implements ParallelExecutor {

    private final ParallelExecutor executor;

    public ExceptionSuppressingParallelExecutor(final ThreadManager threadManager) {
        executor = new CachedPoolParallelExecutor(threadManager, "sync-phase-thread");
    }

    @Override
    public <T> T doParallel(final Callable<T> task1, final Callable<Void> backgroundTask)
            throws ParallelExecutionException {
        try {
            return executor.doParallel(task1, backgroundTask);
        } catch (final ParallelExecutionException e) {
            // suppress exceptions
            return null;
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void doParallel(
            final Runnable onThrow, final ThrowingRunnable foregroundTask, final ThrowingRunnable... backgroundTasks)
            throws ParallelExecutionException {
        try {
            executor.doParallel(onThrow, foregroundTask, backgroundTasks);
        } catch (final ParallelExecutionException e) {
            // suppress exceptions
        }
    }
    /**
     * {@inheritDoc}
     */
    @Override
    public boolean isImmutable() {
        return executor.isImmutable();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void start() {
        executor.start();
    }
}
