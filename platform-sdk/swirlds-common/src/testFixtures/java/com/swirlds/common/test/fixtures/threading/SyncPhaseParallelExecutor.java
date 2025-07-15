// SPDX-License-Identifier: Apache-2.0
package com.swirlds.common.test.fixtures.threading;

import com.swirlds.common.threading.manager.ThreadManager;
import com.swirlds.common.threading.pool.ParallelExecutionException;
import com.swirlds.common.threading.pool.ParallelExecutor;
import java.time.Duration;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicReference;
import org.hiero.base.concurrent.ThrowingRunnable;

/**
 * A parallel executor that executes a {@link Runnable} after a specified sync phase (not step).
 */
public class SyncPhaseParallelExecutor implements ParallelExecutor {

    private static final int PHASE_1 = 1;
    private static final int PHASE_2 = 2;
    private static final int PHASE_3 = 3;

    private static final Runnable NOOP_RUNNABLE = () -> {};

    private static final int NUMBER_OF_PHASES = 3;
    private static final Duration WAIT_TIME = Duration.ofSeconds(60);

    private final ExecutorService executor;
    private final AtomicReference<ThreadStatus> threadStatus;
    private volatile int phase;

    private final Runnable afterPhase1;
    private final Runnable afterPhase2;
    private final Runnable beforePhase3;

    private final boolean waitForSecondThread;

    public SyncPhaseParallelExecutor(
            final ThreadManager threadManager, final Runnable afterPhase1, final Runnable afterPhase2) {

        this(threadManager, afterPhase1, afterPhase2, null, true);
    }

    public SyncPhaseParallelExecutor(
            final ThreadManager threadManager,
            final Runnable afterPhase1,
            final Runnable afterPhase2,
            final boolean waitForSecondThread) {

        this(threadManager, afterPhase1, afterPhase2, null, waitForSecondThread);
    }

    public SyncPhaseParallelExecutor(
            final ThreadManager threadManager,
            final Runnable afterPhase1,
            final Runnable afterPhase2,
            final Runnable beforePhase3,
            final boolean waitForSecondThread) {

        this.afterPhase1 = noopIfNull(afterPhase1);
        this.afterPhase2 = noopIfNull(afterPhase2);
        this.beforePhase3 = noopIfNull(beforePhase3);
        this.waitForSecondThread = waitForSecondThread;

        executor = Executors.newCachedThreadPool(threadManager.createThreadFactory("SyncPhase", "SyncPhase"));
        threadStatus = new AtomicReference<>(ThreadStatus.WAITING_FOR_FIRST_THREAD);
        phase = PHASE_1;
    }

    private void incPhase() {
        phase = phase % NUMBER_OF_PHASES + 1;
    }

    public int getPhase() {
        return phase;
    }

    @Override
    public <T> T doParallel(final Callable<T> task1, final Callable<Void> backgroundTask, final Runnable onThrow)
            throws ParallelExecutionException {
        return doParallel(task1, backgroundTask);
    }

    /**
     * Execute two tasks in parallel, then executes a {@link Runnable} after {@link SyncPhaseParallelExecutor#phase}.
     *
     * This method assumes that this instance of {@link SyncPhaseParallelExecutor} is used for both the caller and the
     * listener. The caller and listener threads can enter the {@link SyncPhaseParallelExecutor#doParallel(Callable,
     * Callable)} method at the same time, each with their 2 tasks, resulting in 4 tasks executing at once.
     *
     * @param task1
     * 		a task to execute in parallel
     * @param backgroundTask
     * 		a task to execute in parallel
     * @throws ParallelExecutionException
     * 		if anything goes wrong
     */
    @Override
    public <T> T doParallel(final Callable<T> task1, final Callable<Void> backgroundTask)
            throws ParallelExecutionException {

        if (phase == PHASE_3) {
            beforePhase3.run();
        }

        // Executes the 2 caller or 2 listener tasks simultaneously.
        T result = null;
        try {
            final Future<T> future1 = executor.submit(task1);
            backgroundTask.call();
            result = future1.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ParallelExecutionException(e);
        } catch (Exception e) {
            throw new ParallelExecutionException(e);
        }

        // Both task1 and task2 are complete. However, either the caller's 2 tasks or the listener's 2 tasks will
        // complete first. The first of the two (caller or listener) must wait for the other to complete so that the
        // runnable to execute after a certain phase executes before the first thread that completes moves on to the
        // next phase.

        try {
            // here we wait for both the caller thread and listener thread to finish their tasks
            // only if waitForSecondThread is enabled, otherwise we just continue
            synchronized (threadStatus) {
                if (waitForSecondThread && threadStatus.get() == ThreadStatus.WAITING_FOR_FIRST_THREAD) {
                    // if im the first thread, then I wait for the second one
                    threadStatus.set(ThreadStatus.WAITING_FOR_SECOND_THREAD);
                    // caller or listener thread waits, releasing the threadStatus lock
                    while (threadStatus.get() != ThreadStatus.SECOND_THREAD_DONE) {
                        // sonar says we should always wait in a while loop
                        threadStatus.wait(WAIT_TIME.toMillis());
                    }
                    // once the second thread wakes me up, we're done, so we reset the status to the initial value
                    threadStatus.set(ThreadStatus.WAITING_FOR_FIRST_THREAD);
                    return result;
                }

                // once both threads are done, we can execute the custom method
                if (phase == PHASE_1) {
                    afterPhase1.run();
                } else if (phase == PHASE_2) {
                    afterPhase2.run();
                } else {
                    // nothing to do after phase 3
                }

                incPhase();
                // The thread not waiting above notifies the waiting thread
                threadStatus.set(ThreadStatus.SECOND_THREAD_DONE);
                threadStatus.notifyAll();
            }
            return result;

        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ParallelExecutionException(e);
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

    private static Runnable noopIfNull(Runnable runnable) {
        if (runnable == null) {
            return NOOP_RUNNABLE;
        }
        return runnable;
    }

    @Override
    public boolean isImmutable() {
        return false;
    }

    @Override
    public void start() {}

    private enum ThreadStatus {
        WAITING_FOR_FIRST_THREAD,
        WAITING_FOR_SECOND_THREAD,
        SECOND_THREAD_DONE
    }
}
