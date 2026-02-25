// SPDX-License-Identifier: Apache-2.0
package com.swirlds.common.test.fixtures.threading;

import java.util.concurrent.Callable;
import org.hiero.base.concurrent.ThrowingRunnable;
import org.hiero.consensus.concurrent.manager.ThreadManager;
import org.hiero.consensus.concurrent.pool.CachedPoolParallelExecutor;
import org.hiero.consensus.concurrent.pool.ParallelExecutionException;
import org.hiero.consensus.concurrent.pool.ParallelExecutor;

/**
 * Parallel executor that suppresses all exceptions.
 */
public class ExceptionSuppressingParallelExecutor implements ParallelExecutor {

    private final ParallelExecutor executor;

    public ExceptionSuppressingParallelExecutor(final ThreadManager threadManager) {
        executor = new CachedPoolParallelExecutor(threadManager, "sync-phase-thread");
    }

    @Override
    public <T> T doParallelWithHandler(
            final Runnable errorHandler, final Callable<T> foregroundTask, final ThrowingRunnable... backgroundTasks)
            throws ParallelExecutionException {
        try {
            return executor.doParallelWithHandler(errorHandler, foregroundTask, backgroundTasks);
        } catch (final ParallelExecutionException e) {
            // suppress exceptions
        }
        return null;
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
