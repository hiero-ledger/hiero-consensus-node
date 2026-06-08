// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.concurrent.pool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hiero.base.utility.test.fixtures.assertions.AssertionUtils.assertEventuallyTrue;
import static org.hiero.consensus.concurrent.manager.AdHocThreadManager.getStaticThreadManager;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class StandardWorkGroupTest {

    private StandardWorkGroup subject;

    @BeforeEach
    void setUp() {
        subject = new StandardWorkGroup(getStaticThreadManager(), "test-group");
    }

    @AfterEach
    void tearDown() {
        subject.close();
    }

    @Test
    void initialStateValid() {
        assertThat(subject.isShutdown()).isFalse();
        assertThat(subject.isTerminated()).isFalse();
    }

    @Test
    void joinWithNoTasksCompletesNormally() throws InterruptedException, ParallelExecutionException {
        subject.join(); // no exception expected

        assertThat(subject.isShutdown()).isFalse(); // close() not called yet
        subject.close();
        assertThat(subject.isTerminated()).isTrue();
        assertThat(subject.isShutdown()).isTrue();
    }

    @Test
    void executingTasksAfterShutdown() {
        subject.shutdown();
        assertThat(subject.isTerminated()).isTrue();
        assertThat(subject.isShutdown()).isTrue();

        final AtomicInteger executedCount = new AtomicInteger();
        assertThrows(RejectedExecutionException.class, () -> subject.execute(executedCount::incrementAndGet));
        assertThat(executedCount.get()).isEqualTo(0);
    }

    @Test
    void executeSingleTask() throws Exception {
        final AtomicInteger executedCount = new AtomicInteger();
        final CyclicBarrier cyclicBarrier = new CyclicBarrier(2);

        subject.execute(() -> {
            executedCount.incrementAndGet();
            try {
                cyclicBarrier.await();
            } catch (final Exception ignored) {
            }
        });
        cyclicBarrier.await(10, TimeUnit.SECONDS);
        assertThat(executedCount.get()).isEqualTo(1);

        assertThat(subject.isTerminated()).isFalse();
        assertThat(subject.isShutdown()).isFalse();
    }

    @Test
    void executeTasksWithName() throws InterruptedException, ParallelExecutionException {
        final AtomicInteger executedCount = new AtomicInteger();
        for (int i = 0; i < 10; i++) {
            subject.execute("task-" + i, executedCount::incrementAndGet);
        }
        subject.join();
        assertThat(executedCount.get()).isEqualTo(10);
    }

    @Test
    void executesMultipleTasksNoExceptions() throws InterruptedException, ParallelExecutionException {
        final AtomicInteger executedCount = new AtomicInteger();
        for (int i = 0; i < 10; i++) {
            subject.execute(executedCount::incrementAndGet);
        }
        subject.join();
        assertThat(executedCount.get()).isEqualTo(10);
    }

    @Test
    void exceptionInterruptsOtherTasks() throws InterruptedException {
        final int tasksCount = 10;
        final AtomicInteger interruptedCount = new AtomicInteger();
        final CountDownLatch tasksStarted = new CountDownLatch(tasksCount);

        for (int i = 0; i < tasksCount; i++) {
            subject.execute(() -> {
                tasksStarted.countDown();
                try {
                    Thread.sleep(Duration.ofSeconds(5).toMillis());
                } catch (InterruptedException e) {
                    interruptedCount.incrementAndGet();
                }
            });
        }

        if (!tasksStarted.await(3, TimeUnit.SECONDS)) {
            throw new AssertionError("Not all tasks started");
        }

        subject.execute(() -> {
            throw new AssertionError("exception");
        });

        assertThatThrownBy(() -> subject.join())
                .isInstanceOf(ParallelExecutionException.class)
                .hasRootCauseInstanceOf(AssertionError.class)
                .hasRootCauseMessage("exception");

        assertThat(interruptedCount.get()).isEqualTo(tasksCount);
    }

    @Test
    void mainThreadInterruptInterruptsTasks() throws InterruptedException {
        final int tasksCount = 10;
        final AtomicInteger interruptedCount = new AtomicInteger();
        final CountDownLatch tasksStarted = new CountDownLatch(tasksCount);

        for (int i = 0; i < tasksCount; i++) {
            subject.execute(() -> {
                tasksStarted.countDown();
                try {
                    Thread.sleep(Duration.ofSeconds(5).toMillis());
                } catch (InterruptedException e) {
                    interruptedCount.incrementAndGet();
                }
            });
        }

        if (!tasksStarted.await(3, TimeUnit.SECONDS)) {
            throw new AssertionError("Not all tasks started");
        }

        final CountDownLatch joinStarted = new CountDownLatch(1);
        final Thread thread = new Thread(() -> {
            joinStarted.countDown();
            try {
                subject.join();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (ParallelExecutionException e) {
                throw new RuntimeException(e);
            }
        });
        thread.start();

        joinStarted.await(2, TimeUnit.SECONDS);
        assertEventuallyTrue(
                () -> thread.getState() == Thread.State.WAITING || thread.getState() == Thread.State.TIMED_WAITING,
                Duration.ofSeconds(5),
                "main thread should enter join");

        thread.interrupt();
        thread.join();

        assertThat(interruptedCount.get()).isEqualTo(tasksCount);
        assertThat(thread.isAlive()).isFalse();
    }

    @Test
    void interruptStatusRestoredAfterJoinIsInterrupted() throws InterruptedException {
        final CountDownLatch taskStarted = new CountDownLatch(1);
        final AtomicBoolean interruptFlagAfterJoin = new AtomicBoolean(false);

        subject.execute(() -> {
            taskStarted.countDown();
            try {
                Thread.sleep(Duration.ofSeconds(10).toMillis());
            } catch (final InterruptedException ignored) {
            }
        });

        taskStarted.await(3, TimeUnit.SECONDS);

        final CountDownLatch joinStarted = new CountDownLatch(1);
        final Thread joinThread = new Thread(() -> {
            joinStarted.countDown();
            try {
                subject.join();
            } catch (final InterruptedException e) {
                interruptFlagAfterJoin.set(Thread.currentThread().isInterrupted());
            } catch (final ParallelExecutionException e) {
                throw new RuntimeException(e);
            }
        });
        joinThread.start();

        joinStarted.await(2, TimeUnit.SECONDS);
        assertEventuallyTrue(
                () -> joinThread.getState() == Thread.State.WAITING
                        || joinThread.getState() == Thread.State.TIMED_WAITING,
                Duration.ofSeconds(5),
                "main thread should enter join");
        joinThread.interrupt();
        joinThread.join(10_000);

        assertThat(interruptFlagAfterJoin.get()).isTrue();
    }

    @Test
    void onlyFirstExceptionIsThrown() {
        final CyclicBarrier allReady = new CyclicBarrier(3);
        final AtomicReference<RuntimeException> thrown = new AtomicReference<>();

        for (int i = 0; i < 3; i++) {
            final RuntimeException error = new RuntimeException("error-" + i);
            subject.execute(() -> {
                try {
                    allReady.await();
                } catch (final Exception e) {
                    return;
                }
                thrown.compareAndSet(null, error);
                throw error;
            });
        }

        assertThatThrownBy(() -> subject.join())
                .isInstanceOf(ParallelExecutionException.class)
                .cause()
                .isSameAs(thrown.get());
    }

    @Test
    void executeWithNameThrowingTaskTriggersShutdown() {
        final AtomicReference<String> threadNameDuringTask = new AtomicReference<>();

        subject.execute("my-task", () -> {
            threadNameDuringTask.set(Thread.currentThread().getName());
            throw new RuntimeException("named task error");
        });

        assertThatThrownBy(() -> subject.join())
                .isInstanceOf(ParallelExecutionException.class)
                .hasRootCauseInstanceOf(RuntimeException.class)
                .hasRootCauseMessage("named task error");

        assertThat(threadNameDuringTask.get()).contains("my-task");
    }

    @Test
    void taskThrowingInterruptedExceptionDoesNotTriggerShutdown()
            throws InterruptedException, ParallelExecutionException {
        // InterruptedException is excluded from handleError — must not trigger shutdownNow().
        final AtomicInteger completedCount = new AtomicInteger();

        subject.execute(() -> sneakyThrow(new InterruptedException("direct IE from task")));
        subject.execute(completedCount::incrementAndGet);
        subject.execute(completedCount::incrementAndGet);

        subject.join();

        assertThat(completedCount.get()).isEqualTo(2);
    }

    @Test
    void closeInterruptsTasksAndTerminates() throws InterruptedException {
        final int tasksCount = 5;
        final AtomicInteger interruptedCount = new AtomicInteger();
        final CountDownLatch tasksStarted = new CountDownLatch(tasksCount);

        for (int i = 0; i < tasksCount; i++) {
            subject.execute(() -> {
                tasksStarted.countDown();
                try {
                    Thread.sleep(Duration.ofSeconds(10).toMillis());
                } catch (final InterruptedException e) {
                    interruptedCount.incrementAndGet();
                }
            });
        }

        tasksStarted.await(3, TimeUnit.SECONDS);
        subject.close();

        assertThat(interruptedCount.get()).isEqualTo(tasksCount);
        assertThat(subject.isTerminated()).isTrue();
        assertThat(subject.isShutdown()).isTrue();
    }

    @Test
    void closeIsIdempotent() throws InterruptedException, ParallelExecutionException {
        subject.execute(() -> {});
        subject.join();

        subject.close();
        subject.close();

        assertThat(subject.isTerminated()).isTrue();
    }

    @Test
    void tryWithResourcesPattern() {
        final AtomicInteger interruptedCount = new AtomicInteger();

        try (final StandardWorkGroup wg = new StandardWorkGroup(getStaticThreadManager(), "try-group")) {
            wg.execute(() -> {
                try {
                    Thread.sleep(Duration.ofSeconds(10).toMillis());
                } catch (final InterruptedException e) {
                    interruptedCount.incrementAndGet();
                }
            });
            wg.execute(() -> {
                throw new RuntimeException("trigger shutdown");
            });

            assertThatThrownBy(wg::join)
                    .isInstanceOf(ParallelExecutionException.class)
                    .hasRootCauseMessage("trigger shutdown");
        } // close() called here — sleeping task must have been interrupted

        assertThat(interruptedCount.get()).isEqualTo(1);
    }

    @Test
    void abortActionCalledOnceWhenFirstTaskFails() throws InterruptedException {
        final AtomicInteger abortCount = new AtomicInteger();
        final CyclicBarrier allReady = new CyclicBarrier(3);

        try (final StandardWorkGroup wg =
                new StandardWorkGroup(getStaticThreadManager(), "abort-test", abortCount::incrementAndGet)) {
            for (int i = 0; i < 3; i++) {
                wg.execute(() -> {
                    try {
                        allReady.await();
                    } catch (final Exception ignored) {
                        return;
                    }
                    throw new RuntimeException("failure");
                });
            }
            assertThatThrownBy(wg::join).isInstanceOf(ParallelExecutionException.class);
        }

        assertThat(abortCount.get()).isEqualTo(1);
    }

    @Test
    void abortActionNotCalledWhenNoTaskFails() throws InterruptedException, ParallelExecutionException {
        final AtomicInteger abortCount = new AtomicInteger();

        try (final StandardWorkGroup wg =
                new StandardWorkGroup(getStaticThreadManager(), "abort-test", abortCount::incrementAndGet)) {
            wg.execute(() -> {});
            wg.execute(() -> {});
            wg.join();
        }

        assertThat(abortCount.get()).isEqualTo(0);
    }

    @SuppressWarnings("unchecked")
    private static <T extends Throwable> void sneakyThrow(final Throwable t) throws T {
        throw (T) t;
    }
}
