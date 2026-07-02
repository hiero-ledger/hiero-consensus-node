// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.concurrent.test.fixtures.assertions;

import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.hiero.base.concurrent.interrupt.InterruptableRunnable;
import org.hiero.base.concurrent.interrupt.InterruptableSupplier;
import org.hiero.consensus.concurrent.framework.config.ThreadConfiguration;

public class AssertionUtils {

    private AssertionUtils() {}

    /**
     * Run an operation and throw an exception if it takes too long.
     *
     * @param operation
     * 		the operation to run
     * @param maxDuration
     * 		the maximum amount of time to wait for the operation to complete
     * @param message
     * 		an error message if the operation takes too long
     */
    public static void completeBeforeTimeout(
            final InterruptableRunnable operation, final Duration maxDuration, final String message)
            throws InterruptedException {

        final CountDownLatch latch = new CountDownLatch(1);
        final AtomicBoolean error = new AtomicBoolean();

        new ThreadConfiguration()
                .setComponent("assertion-utils")
                .setThreadName("assert-prompt-completion")
                .setInterruptableRunnable(() -> {
                    operation.run();
                    latch.countDown();
                })
                .setExceptionHandler((final Thread _, final Throwable exception) -> {
                    error.set(true);
                    exception.printStackTrace();
                })
                .build(true);

        assertFalse(error.get(), "exception encountered while handling operation");
        final boolean completed = latch.await(maxDuration.toMillis(), MILLISECONDS);
        assertTrue(completed, message);
    }

    /**
     * Run an operation and throw an exception if it takes too long.
     *
     * @param operation
     * 		the operation to run
     * @param maxDuration
     * 		the maximum amount of time to wait for the operation to complete
     * @param message
     * 		an error message if the operation takes too long
     * @return the value returned by the operation
     */
    public static <T> T completeBeforeTimeout(
            final InterruptableSupplier<T> operation, final Duration maxDuration, final String message)
            throws InterruptedException {

        final CountDownLatch latch = new CountDownLatch(1);
        final AtomicBoolean error = new AtomicBoolean();
        final AtomicReference<T> value = new AtomicReference<>();

        new ThreadConfiguration()
                .setComponent("assertion-utils")
                .setThreadName("assert-prompt-completion")
                .setInterruptableRunnable(() -> {
                    value.set(operation.get());
                    latch.countDown();
                })
                .setExceptionHandler((final Thread _, final Throwable exception) -> {
                    error.set(true);
                    exception.printStackTrace();
                })
                .build(true);

        assertFalse(error.get(), "exception encountered while handling operation");
        final boolean completed = latch.await(maxDuration.toMillis(), MILLISECONDS);
        assertTrue(completed, message);

        return value.get();
    }
}
