// SPDX-License-Identifier: Apache-2.0
package org.hiero.metrics;

import java.time.Duration;
import java.util.Queue;
import java.util.concurrent.BrokenBarrierException;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.IntFunction;

/**
 * Utility methods for working with threads in tests.
 */
public final class ThreadUtils {

    private ThreadUtils() {}

    /**
     * Runs the given tasks concurrently in the specified number of threads, started at the same time,
     * and waits for their completion.
     *
     * @param threadsCount the number of threads to run
     * @param timeout the maximum duration to wait for all tasks to complete
     * @param taskFactory a factory function that creates a Runnable task for each thread index
     * @throws InterruptedException if the current thread is interrupted while waiting
     * @throws AssertionError if a task fails or the threads do not finish in time
     */
    public static void runConcurrentAndWait(int threadsCount, Duration timeout, IntFunction<Runnable> taskFactory)
            throws InterruptedException {
        final CyclicBarrier barrier = new CyclicBarrier(threadsCount);
        final CountDownLatch doneLatch = new CountDownLatch(threadsCount);
        // failures are added before counting down the latch, so they are visible after awaiting it
        final Queue<Throwable> failures = new ConcurrentLinkedQueue<>();

        for (int i = 0; i < threadsCount; i++) {
            final int threadIndex = i;
            new Thread(() -> {
                        try {
                            barrier.await();
                            taskFactory.apply(threadIndex).run();
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            failures.add(e);
                        } catch (BrokenBarrierException | RuntimeException | Error e) {
                            failures.add(e);
                        } finally {
                            doneLatch.countDown();
                        }
                    })
                    .start();
        }

        if (!doneLatch.await(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
            throw new AssertionError("Threads did not finish in time");
        }
        if (!failures.isEmpty()) {
            final AssertionError error = new AssertionError("Concurrent task failed", failures.poll());
            failures.forEach(error::addSuppressed);
            throw error;
        }
    }

    /**
     * Runs the given tasks concurrently like {@link #runConcurrentAndWait(int, Duration, IntFunction)},
     * while another thread runs the given check in a loop until the tasks finish.
     * Use it to verify invariants, which must hold at any time during concurrent modifications,
     * because a broken invariant may be fixed again by later modifications before the tasks finish.
     *
     * @param threadsCount the number of threads to run
     * @param timeout the maximum duration to wait for all tasks to complete
     * @param taskFactory a factory function that creates a Runnable task for each thread index
     * @param check the check to run in a loop, failing by throwing an exception or error
     * @throws InterruptedException if the current thread is interrupted while waiting
     * @throws AssertionError if a task or the check fails, or the threads do not finish in time
     */
    public static void runConcurrentAndCheck(
            int threadsCount, Duration timeout, IntFunction<Runnable> taskFactory, Runnable check)
            throws InterruptedException {
        final AtomicBoolean running = new AtomicBoolean(true);
        final AtomicReference<Throwable> checkFailure = new AtomicReference<>();
        final Thread checkThread = Thread.ofPlatform().start(() -> {
            try {
                while (running.get()) {
                    check.run();
                }
            } catch (Throwable t) {
                checkFailure.set(t);
            }
        });

        try {
            runConcurrentAndWait(threadsCount, timeout, taskFactory);
        } finally {
            running.set(false);
            if (!checkThread.join(Duration.ofSeconds(1))) {
                throw new AssertionError("Check thread did not finish in time");
            }
        }

        if (checkFailure.get() != null) {
            throw new AssertionError("Check failed while concurrent tasks were running", checkFailure.get());
        }
    }
}
