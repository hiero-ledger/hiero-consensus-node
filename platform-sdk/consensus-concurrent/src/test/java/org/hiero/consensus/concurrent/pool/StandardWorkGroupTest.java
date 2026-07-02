// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.concurrent.pool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hiero.base.utility.test.fixtures.assertions.AssertionUtils.assertEventuallyEquals;
import static org.hiero.base.utility.test.fixtures.assertions.AssertionUtils.assertEventuallyTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class StandardWorkGroupTest {

    private StandardWorkGroup subject;

    @BeforeEach
    void setUp() {
        subject = new StandardWorkGroup("test-group");
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
    void joinAfterNoTasksForked() throws InterruptedException, ExecutionException {
        subject.join(); // no exception expected

        assertThat(subject.isShutdown()).isFalse(); // close() not called yet
        subject.close();
        assertThat(subject.isTerminated()).isTrue();
        assertThat(subject.isShutdown()).isTrue();
    }

    @Test
    void forkTasksAfterShutdownThrows() {
        subject.shutdown();
        assertThat(subject.isTerminated()).isTrue();
        assertThat(subject.isShutdown()).isTrue();

        final AtomicInteger executedCount = new AtomicInteger();
        assertThrows(RejectedExecutionException.class, () -> subject.fork(executedCount::incrementAndGet));
        assertThat(executedCount.get()).isEqualTo(0);
    }

    @Test
    void forkedTaskFinishesWithoutJoin() {
        final AtomicInteger executedCount = new AtomicInteger();

        subject.fork(executedCount::incrementAndGet);

        assertEventuallyEquals(1, executedCount::get, Duration.ofSeconds(5), "Task should finish");

        assertThat(subject.isTerminated()).isFalse();
        assertThat(subject.isShutdown()).isFalse();
    }

    @Test
    void forkJoinWithTaskNames() throws InterruptedException, ExecutionException {
        final AtomicInteger executedCount = new AtomicInteger();
        for (int i = 0; i < 10; i++) {
            subject.fork("task-" + i, executedCount::incrementAndGet);
        }

        subject.join();
        assertThat(executedCount.get()).isEqualTo(10);
    }

    @Test
    void forkJoinWithoutTaskNames() throws InterruptedException, ExecutionException {
        final AtomicInteger executedCount = new AtomicInteger();
        for (int i = 0; i < 10; i++) {
            subject.fork(executedCount::incrementAndGet);
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
            subject.fork(() -> {
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

        subject.fork(() -> {
            throw new AssertionError("exception");
        });

        assertThatThrownBy(() -> subject.join())
                .isInstanceOf(ExecutionException.class)
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
            subject.fork(() -> {
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
            } catch (ExecutionException e) {
                throw new RuntimeException(e);
            }
        });
        thread.start();

        joinStarted.await(3, TimeUnit.SECONDS);
        assertEventuallyTrue(
                () -> thread.getState() == Thread.State.WAITING || thread.getState() == Thread.State.TIMED_WAITING,
                Duration.ofSeconds(5),
                "main thread should enter join");

        thread.interrupt();
        thread.join();

        // join() requests cancellation via shutdownNow() and returns without waiting for
        // tasks to finish, so the interrupts are observed asynchronously by the worker tasks.
        assertEventuallyTrue(
                () -> interruptedCount.get() == tasksCount,
                Duration.ofSeconds(5),
                "all tasks should eventually observe the interrupt");
        assertThat(thread.isAlive()).isFalse();
    }

    @Test
    void oneOfTheTasksExceptionsIsThrown() {
        final int tasksCount = 10;
        final CyclicBarrier allReady = new CyclicBarrier(tasksCount);

        for (int i = 0; i < tasksCount; i++) {
            final RuntimeException error = new RuntimeException("error-" + i);
            subject.fork(() -> {
                try {
                    allReady.await();
                } catch (final Exception e) {
                    return;
                }
                throw error;
            });
        }

        assertThatThrownBy(() -> subject.join())
                .isInstanceOf(ExecutionException.class)
                .cause()
                .message()
                .isIn(IntStream.range(0, tasksCount).mapToObj(i -> "error-" + i).collect(Collectors.toSet()));
    }

    @Test
    void forkWithNameThrowingTaskTriggersShutdown() {
        final AtomicReference<String> threadNameDuringTask = new AtomicReference<>();

        subject.fork("my-task", () -> {
            threadNameDuringTask.set(Thread.currentThread().getName());
            throw new RuntimeException("named task error");
        });

        assertThatThrownBy(() -> subject.join())
                .isInstanceOf(ExecutionException.class)
                .hasRootCauseInstanceOf(RuntimeException.class)
                .hasRootCauseMessage("named task error");

        assertThat(threadNameDuringTask.get()).contains("my-task");
    }

    @Test
    void taskThrowingInterruptedExceptionDoesNotTriggerShutdown() throws InterruptedException, ExecutionException {
        // InterruptedException is excluded from handleError — must not trigger shutdownNow().
        final AtomicInteger completedCount = new AtomicInteger();

        subject.fork(() -> sneakyThrow(new InterruptedException("direct IE from task")));
        subject.fork(completedCount::incrementAndGet);
        subject.fork(completedCount::incrementAndGet);

        subject.join();

        assertThat(completedCount.get()).isEqualTo(2);
    }

    @Test
    void closeInterruptsTasksAndTerminates() throws InterruptedException {
        final int tasksCount = 5;
        final AtomicInteger interruptedCount = new AtomicInteger();
        final CountDownLatch tasksStarted = new CountDownLatch(tasksCount);

        for (int i = 0; i < tasksCount; i++) {
            subject.fork(() -> {
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
    void closeIsIdempotent() throws InterruptedException, ExecutionException {
        subject.fork(() -> {});
        subject.join();

        subject.close();
        subject.close();

        assertThat(subject.isTerminated()).isTrue();
    }

    @Test
    void tryWithResourcesPattern() {
        final AtomicInteger interruptedCount = new AtomicInteger();

        try (final StandardWorkGroup wg = new StandardWorkGroup("try-group")) {
            wg.fork(() -> {
                try {
                    Thread.sleep(Duration.ofSeconds(10).toMillis());
                } catch (final InterruptedException e) {
                    interruptedCount.incrementAndGet();
                }
            });
            wg.fork(() -> {
                throw new RuntimeException("trigger shutdown");
            });

            assertThatThrownBy(wg::join).isInstanceOf(ExecutionException.class).hasRootCauseMessage("trigger shutdown");
        } // close() called here — sleeping task must have been interrupted

        assertThat(interruptedCount.get()).isEqualTo(1);
    }

    @Test
    void abortActionCalledOnceWhenFirstTaskFails() {
        final AtomicInteger abortCount = new AtomicInteger();
        final CyclicBarrier allReady = new CyclicBarrier(3);

        try (final StandardWorkGroup wg = new StandardWorkGroup("abort-test", abortCount::incrementAndGet)) {
            for (int i = 0; i < 3; i++) {
                wg.fork(() -> {
                    try {
                        allReady.await();
                    } catch (final Exception ignored) {
                        return;
                    }
                    throw new RuntimeException("failure");
                });
            }
            assertThatThrownBy(wg::join).isInstanceOf(ExecutionException.class);
        }

        assertThat(abortCount.get()).isEqualTo(1);
    }

    @Test
    void abortActionNotCalledWhenNoTaskFails() throws InterruptedException, ExecutionException {
        final AtomicInteger abortCount = new AtomicInteger();

        try (final StandardWorkGroup wg = new StandardWorkGroup("abort-test", abortCount::incrementAndGet)) {
            wg.fork(() -> {});
            wg.fork(() -> {});
            wg.join();
        }

        assertThat(abortCount.get()).isEqualTo(0);
    }

    @SuppressWarnings("unchecked")
    private static <T extends Throwable> void sneakyThrow(final Throwable t) throws T {
        throw (T) t;
    }
}
