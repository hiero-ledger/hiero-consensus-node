// SPDX-License-Identifier: Apache-2.0
package com.swirlds.common.test.fixtures.threading;

import com.swirlds.common.threading.manager.ThreadManager;
import com.swirlds.common.threading.pool.CachedPoolParallelExecutor;
import com.swirlds.common.threading.pool.ParallelExecutionException;
import com.swirlds.common.threading.pool.ParallelExecutor;
import java.util.concurrent.Callable;
import org.hiero.base.concurrent.ThrowingRunnable;

/**
 * Executes two tasks simultaneously and replacing a specified task at a specified phase. Only
 * one instance of this class should be used at a time, i.e. either the caller or listener in a sync but not both.
 */
public class ReplaceSyncPhaseParallelExecutor implements ParallelExecutor {
    private static final int NUMBER_OF_PHASES = 3;

    private final ParallelExecutor executor;
    private volatile int phase;

    private final int phaseToReplace;
    private final int taskNumToReplace;
    private Callable<Void> replacementTask;

    public ReplaceSyncPhaseParallelExecutor(
            final ThreadManager threadManager,
            final int phaseToReplace,
            final int taskNumToReplace,
            final Callable<Void> replacementTask) {
        this.phaseToReplace = phaseToReplace;
        this.taskNumToReplace = taskNumToReplace;
        this.replacementTask = replacementTask;

        executor = new CachedPoolParallelExecutor(threadManager, "sync-phase-thread");
        phase = 1;
    }

    private void incPhase() {
        phase = phase % NUMBER_OF_PHASES + 1;
    }

    public int getPhase() {
        return phase;
    }

    /**
     * Executes two tasks in parallel, skipping one of the tasks if it is the phase defined in {@link
     * ReplaceSyncPhaseParallelExecutor#phaseToReplace}.
     *
     * @param task1
     * 		a task to execute in parallel
     * @param backgroundTask
     * 		a task to execute in parallel
     */
    @Override
    public <T> T doParallel(final Callable<T> task1, final Callable<Void> backgroundTask)
            throws ParallelExecutionException {
        try {
            if (phase == phaseToReplace) {
                if (taskNumToReplace == 1) {
                    executor.doParallel(replacementTask, backgroundTask);
                    return null;
                } else {
                    return executor.doParallel(task1, replacementTask);
                }
            } else {
                return executor.doParallel(task1, backgroundTask);
            }
        } finally {
            incPhase();
        }
    }

    /**
     * {@inheritDoc}
     * Not implemented
     */
    @Override
    public void doParallel(
            final Runnable onThrow, final ThrowingRunnable foregroundTask, final ThrowingRunnable... backgroundTasks)
            throws ParallelExecutionException {
        throw new UnsupportedOperationException();
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
