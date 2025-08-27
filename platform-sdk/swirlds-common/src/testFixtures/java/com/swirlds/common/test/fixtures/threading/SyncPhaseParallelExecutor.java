// SPDX-License-Identifier: Apache-2.0
package com.swirlds.common.test.fixtures.threading;

import com.swirlds.common.threading.manager.ThreadManager;
import com.swirlds.common.threading.pool.ParallelExecutionException;
import com.swirlds.common.threading.pool.ParallelExecutor;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicReference;
import org.hiero.base.concurrent.ThrowingRunnable;

/**
 * A parallel executor that executes a {@link Runnable} after a specified sync phase (not step). It also has a special
 * behaviour of calling foreground task in separate thread (rather than caller thread) due to compatibility issues with
 * old sync tests.
 * <p>
 * This method assumes that this instance of {@link SyncPhaseParallelExecutor} is used for both the caller and the
 * listener. The caller and listener threads can enter the
 * {@link SyncPhaseParallelExecutor#doParallelWithHandler(Runnable, Callable, ThrowingRunnable...) method at the same
 * time, each with their 2 tasks, resulting in 4 tasks executing at once.
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

    /**
     * {@inheritDoc}
     */
    @Override
    public <T> T doParallelWithHandler(
            final Runnable errorHandler, final Callable<T> foregroundTask, final ThrowingRunnable... backgroundTasks)
            throws ParallelExecutionException {

        if (phase == PHASE_3) {
            beforePhase3.run();
        }

        final List<Future<Void>> futures =
                Arrays.stream(backgroundTasks).map(executor::submit).toList();

        final T result;
        try {
            final Future<T> foregroundFuture = executor.submit(foregroundTask);

            for (final Future<Void> future : futures) {
                future.get();
            }

            result = foregroundFuture.get();
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
