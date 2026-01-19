// SPDX-License-Identifier: Apache-2.0
package org.hiero.metrics;

import java.time.Duration;
import java.util.concurrent.BrokenBarrierException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.function.IntFunction;

/**
 * Utility methods for working with threads in tests.
 */
public final class ThreadUtils {

    private ThreadUtils() {}

    /**
     * Runs the given tasks concurrently in the specified number of threads.
     *
     * @param threadsCount the number of threads to run
     * @param taskFactory a factory function that creates a Runnable task for each thread index
     * @return a CountDownLatch that can be used to await the completion of all tasks
     */
    public static CountDownLatch runConcurrent(int threadsCount, IntFunction<Runnable> taskFactory) {
        final CyclicBarrier barrier = new CyclicBarrier(threadsCount);
        final CountDownLatch doneLatch = new CountDownLatch(threadsCount);

        for (int i = 0; i < threadsCount; i++) {
            final int threadIndex = i;
            new Thread(() -> {
                        try {
                            barrier.await();
                            taskFactory.apply(threadIndex).run();
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            throw new RuntimeException("Interrupted", e);
                        } catch (BrokenBarrierException e) {
                            throw new RuntimeException(e);
                        } finally {
                            doneLatch.countDown();
                        }
                    })
                    .start();
        }

        return doneLatch;
    }

    /**
     * Runs the given tasks concurrently in the specified number of threads and waits for their completion.
     *
     * @param threadsCount the number of threads to run
     * @param timeout the maximum duration to wait for all tasks to complete
     * @param taskFactory a factory function that creates a Runnable task for each thread index
     * @throws InterruptedException if the current thread is interrupted while waiting
     */
    public static void runConcurrentAndWait(int threadsCount, Duration timeout, IntFunction<Runnable> taskFactory)
            throws InterruptedException {
        CountDownLatch doneLatch = runConcurrent(threadsCount, taskFactory);
        awaitLatch(doneLatch, timeout, "Threads did not finish in time");
    }

    /**
     * Awaits on the given latch for up to 1 second.
     *
     * @param latch the latch to await
     * @param errorMessage the error message for the AssertionError if the latch is not released in time
     * @throws InterruptedException if the current thread is interrupted while waiting
     */
    public static void awaitLatch(CountDownLatch latch, String errorMessage) throws InterruptedException {
        awaitLatch(latch, Duration.ofSeconds(1), errorMessage);
    }

    /**
     * Awaits on the given latch for up to the specified duration.
     *
     * @param latch the latch to await
     * @param duration the maximum duration to wait
     * @param errorMessage the error message for the AssertionError if the latch is not released in time
     * @throws InterruptedException if the current thread is interrupted while waiting
     */
    public static void awaitLatch(CountDownLatch latch, Duration duration, String errorMessage)
            throws InterruptedException {
        if (!latch.await(duration.toMillis(), TimeUnit.MILLISECONDS)) {
            throw new AssertionError(errorMessage);
        }
    }

    /**
     * Joins the given thread for up to 1 second.
     *
     * @param thread the thread to join
     * @param errorMessage the error message for the AssertionError if the thread does not terminate in time
     * @throws InterruptedException if the current thread is interrupted while waiting
     */
    public static void joinThread(Thread thread, String errorMessage) throws InterruptedException {
        joinThread(thread, Duration.ofSeconds(1), errorMessage);
    }

    /**
     * Joins the given thread for up to the specified duration.
     *
     * @param thread the thread to join
     * @param duration the maximum duration to wait
     * @param errorMessage the error message for the AssertionError if the thread does not terminate in time
     * @throws InterruptedException if the current thread is interrupted while waiting
     */
    public static void joinThread(Thread thread, Duration duration, String errorMessage) throws InterruptedException {
        if (!thread.join(duration)) {
            throw new AssertionError(errorMessage);
        }
    }
}
