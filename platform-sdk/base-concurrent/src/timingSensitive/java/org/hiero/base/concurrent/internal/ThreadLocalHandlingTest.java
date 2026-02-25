// SPDX-License-Identifier: Apache-2.0
package org.hiero.base.concurrent.internal;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.lang.Thread.UncaughtExceptionHandler;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;
import org.assertj.core.api.Assertions;
import org.assertj.core.api.Fail;
import org.hiero.base.concurrent.ExecutorFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

public class ThreadLocalHandlingTest {

    public static final String TEST_VALUE = "test";

    List<Runnable> shutdownCalls = new ArrayList<>();

    @AfterEach
    void tearDown() {
        shutdownCalls.forEach(Runnable::run);
        shutdownCalls.clear();
    }

    @ParameterizedTest
    @CsvSource({"100,1", "1,1", "100,10", "100,100"})
    void testExecutorService(final int taskCount, final int threadCount) {
        // given
        final List<String> errors = new ArrayList<>();
        final CountDownLatch latch = new CountDownLatch(taskCount);
        final ThreadLocal<String> threadLocal = new ThreadLocal<>();
        final UncaughtExceptionHandler exceptionHandler = (t, e) -> e.printStackTrace();
        final Runnable onStartup = () -> threadLocal.set(TEST_VALUE);
        final ExecutorFactory executorFactory =
                DefaultExecutorFactory.create("test-group", onStartup, exceptionHandler);
        final ExecutorService executorService = executorFactory.createExecutorService(threadCount);
        addShutdown(executorService);
        final Runnable task = createCheckThreadLocalTask(latch, threadLocal, errors);

        // when
        IntStream.range(0, taskCount).forEach(i -> executorService.execute(task));
        waitForLatch(latch);

        // then
        Assertions.assertThat(errors).isEmpty();
    }

    @ParameterizedTest
    @CsvSource({"100,1", "1,1", "100,10", "100,100"})
    void testScheduledExecutorService(final int taskCount, final int threadCount) {
        // given
        final List<String> errors = new ArrayList<>();
        final CountDownLatch latch = new CountDownLatch(taskCount * 2);
        final ThreadLocal<String> threadLocal = new ThreadLocal<>();
        final UncaughtExceptionHandler exceptionHandler = (t, e) -> e.printStackTrace();
        final Runnable onStartup = () -> threadLocal.set(TEST_VALUE);
        final ExecutorFactory executorFactory =
                DefaultExecutorFactory.create("test-group", onStartup, exceptionHandler);
        final ScheduledExecutorService executorService = executorFactory.createScheduledExecutorService(threadCount);
        addShutdown(executorService);
        final Runnable task = createCheckThreadLocalTask(latch, threadLocal, errors);
        final CountDownLatch latchForFixedRate = new CountDownLatch(taskCount);
        final Runnable taskForFixedRate = createCheckThreadLocalTask(latchForFixedRate, threadLocal, errors);

        // when
        IntStream.range(0, taskCount).forEach(i -> {
            executorService.execute(task);
            executorService.schedule(task, 50, TimeUnit.MILLISECONDS);
        });
        executorService.scheduleAtFixedRate(taskForFixedRate, 10, 10, TimeUnit.MILLISECONDS);
        waitForLatch(latchForFixedRate);
        waitForLatch(latch);

        // then
        Assertions.assertThat(errors).isEmpty();
    }

    @ParameterizedTest
    @CsvSource({"100,1", "1,1", "100,10", "100,100"})
    void testForkJoinPool(final int taskCount, final int threadCount) {
        // given
        final List<String> errors = new ArrayList<>();
        final CountDownLatch latch = new CountDownLatch(taskCount);
        final ThreadLocal<String> threadLocal = new ThreadLocal<>();
        final UncaughtExceptionHandler exceptionHandler = (t, e) -> e.printStackTrace();
        final Runnable onStartup = () -> threadLocal.set(TEST_VALUE);
        final ExecutorFactory executorFactory =
                DefaultExecutorFactory.create("test-group", onStartup, exceptionHandler);
        final ForkJoinPool forkJoinPool = executorFactory.createForkJoinPool(threadCount);
        addShutdown(forkJoinPool);
        final Runnable task = createCheckThreadLocalTask(latch, threadLocal, errors);

        // when
        IntStream.range(0, taskCount).forEach(i -> forkJoinPool.execute(task));
        waitForLatch(latch);

        // then
        Assertions.assertThat(errors).isEmpty();
    }

    @Test
    void testThread() {
        // given
        final List<String> errors = new ArrayList<>();
        final CountDownLatch latch = new CountDownLatch(1);
        final ThreadLocal<String> threadLocal = new ThreadLocal<>();
        final UncaughtExceptionHandler exceptionHandler = (t, e) -> e.printStackTrace();
        final Runnable onStartup = () -> threadLocal.set(TEST_VALUE);
        final ExecutorFactory executorFactory =
                DefaultExecutorFactory.create("test-group", onStartup, exceptionHandler);
        final Runnable task = createCheckThreadLocalTask(latch, threadLocal, errors);
        final Thread thread = executorFactory.createThread(task);
        addShutdown(thread);

        // when
        thread.start();
        waitForLatch(latch);

        // then
        Assertions.assertThat(errors).isEmpty();
    }

    private void addShutdown(@NonNull final Thread thread) {
        final Runnable shutdown = () -> {
            try {
                thread.join(10_000);
            } catch (Exception e) {
                Fail.fail("Interrupted while waiting for thread to terminate");
            }
        };
        shutdownCalls.add(shutdown);
    }

    private void addShutdown(@NonNull final ExecutorService executorService) {
        final Runnable shutdown = () -> {
            try {
                executorService.shutdown();
                executorService.awaitTermination(10, TimeUnit.SECONDS);
            } catch (Exception e) {
                Fail.fail("Interrupted while waiting for executor to terminate");
            }
        };
        shutdownCalls.add(shutdown);
    }

    private Runnable createCheckThreadLocalTask(
            @NonNull final CountDownLatch latch,
            @NonNull final ThreadLocal<String> threadLocal,
            @NonNull final List<String> errors) {
        return () -> {
            final String value = threadLocal.get();
            if (value == null) {
                errors.add("ThreadLocal value is null");
            } else if (!value.equals(TEST_VALUE)) {
                errors.add("ThreadLocal value is not " + TEST_VALUE + " but " + value);
            }
            latch.countDown();
        };
    }

    private void waitForLatch(@NonNull final CountDownLatch latch) {
        try {
            boolean done = latch.await(10, TimeUnit.SECONDS);
            if (!done) {
                Fail.fail("Task did not complete within 10 seconds");
            }
        } catch (InterruptedException e) {
            Fail.fail("Interrupted while waiting for tasks to complete");
        }
    }
}
