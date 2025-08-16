// SPDX-License-Identifier: Apache-2.0
package com.swirlds.common.test.fixtures.threading;

import com.swirlds.common.threading.manager.ThreadManager;
import com.swirlds.common.threading.pool.CachedPoolParallelExecutor;
import com.swirlds.common.threading.pool.ParallelExecutionException;
import com.swirlds.common.threading.pool.ParallelExecutor;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Arrays;
import java.util.Objects;
import java.util.concurrent.Callable;
import org.hiero.base.concurrent.ThrowingRunnable;

/**
 * Executes two tasks simultaneously and replacing a specified task at a specified phase. Only one instance of this
 * class should be used at a time, i.e. either the caller or listener in a sync but not both.
 */
public class ReplaceSyncPhaseParallelExecutor implements ParallelExecutor {
    private static final int NUMBER_OF_PHASES = 3;

    private final ParallelExecutor executor;
    private volatile int phase;

    private final int phaseToReplace;
    private final int taskNumToReplace;
    private final ThrowingRunnable replacementTask;

    public ReplaceSyncPhaseParallelExecutor(
            @NonNull final ThreadManager threadManager,
            final int phaseToReplace,
            final int taskNumToReplace,
            @NonNull final ThrowingRunnable replacementTask) {
        this.phaseToReplace = phaseToReplace;
        this.taskNumToReplace = taskNumToReplace;
        this.replacementTask = Objects.requireNonNull(replacementTask);

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
     * {@inheritDoc}
     */
    @Override
    public <T> T doParallelWithHandler(
            final Runnable errorHandler, final Callable<T> foregroundTask, final ThrowingRunnable... backgroundTasks)
            throws ParallelExecutionException {
        try {
            if (phase == phaseToReplace) {
                if (taskNumToReplace == 1) {
                    executor.doParallelWithHandler(errorHandler, replacementTask, backgroundTasks);
                    return null;
                } else {
                    final ThrowingRunnable[] tasksWithReplacement =
                            Arrays.copyOf(backgroundTasks, backgroundTasks.length);
                    tasksWithReplacement[taskNumToReplace - 2] = replacementTask;
                    return executor.doParallelWithHandler(errorHandler, foregroundTask, replacementTask);
                }
            } else {
                return executor.doParallelWithHandler(errorHandler, foregroundTask, backgroundTasks);
            }
        } finally {
            incPhase();
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
