// SPDX-License-Identifier: Apache-2.0
package com.swirlds.common.test.fixtures.threading;

import com.swirlds.common.threading.manager.ThreadManager;
import com.swirlds.common.threading.pool.CachedPoolParallelExecutor;
import com.swirlds.common.threading.pool.ParallelExecutionException;
import com.swirlds.common.threading.pool.ParallelExecutor;
import java.util.concurrent.Callable;

/**
 * Parallel executor that suppressed all exceptions.
 */
public class ExceptionSuppressingParallelExecutor implements ParallelExecutor {

    private final ParallelExecutor executor;

    public ExceptionSuppressingParallelExecutor(final ThreadManager threadManager) {
        executor = new CachedPoolParallelExecutor(threadManager, "sync-phase-thread");
    }

    @Override
    public <T> T doParallel(final Callable<T> task1, final Callable<Void> task2) throws ParallelExecutionException {
        try {
            return executor.doParallel(task1, task2);
        } catch (final ParallelExecutionException e) {
            // suppress exceptions
            return null;
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
