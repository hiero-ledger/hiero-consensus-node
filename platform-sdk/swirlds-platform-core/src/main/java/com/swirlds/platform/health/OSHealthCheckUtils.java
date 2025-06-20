// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.health;

import static com.swirlds.common.formatting.StringFormattingUtils.addLine;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/**
 * Utility methods for performing and reporting OS health checks.
 */
public final class OSHealthCheckUtils {

    private OSHealthCheckUtils() {}

    /**
     * The result of timing a supplier's execution.
     *
     * @param result
     * 		the result supplied by the supplier
     * @param duration
     * 		the duration the supplier took to complete
     * @param <R>
     * 		the type supplied by the supplier
     */
    public record SupplierResult<R>(R result, Duration duration) {}

    /**
     * Gets a value from a supplier on a background thread and measures how long it takes to complete. If the timeout is
     * exceeded before it completes, {@code null} is returned. Otherwise, the value provided by the supplier is
     * returned along with how long it took to complete.
     *
     * @param supplierToMeasure
     * 		the supplier to invoke and measure speed on
     * @param timeoutMillis
     * 		the number of milliseconds to wait for the supplier to complete before abandoning it and returning {@code
     * 		null}
     * @param <R>
     * 		the type supplied by the {@code supplierToMeasure}
     * @return {@code null} if the supplier does not complete in time, or a {@link SupplierResult} with the result of
     * 		the supplier and the duration it took to complete
     * @throws InterruptedException
     * 		if this thread is interrupted while waiting for the supplier to complete
     */
    public static <R> SupplierResult<R> timeSupplier(final Supplier<R> supplierToMeasure, final long timeoutMillis)
            throws InterruptedException {
        final CountDownLatch doneLatch = new CountDownLatch(1);
        final AtomicLong start = new AtomicLong();
        final AtomicLong end = new AtomicLong();
        final AtomicReference<R> result = new AtomicReference<>();

        final Runnable runSupplier = () -> {
            start.set(System.nanoTime());
            result.set(supplierToMeasure.get());
            end.set(System.nanoTime());

            doneLatch.countDown();
        };

        ForkJoinPool.commonPool().execute(runSupplier);
        final boolean success = doneLatch.await(timeoutMillis, TimeUnit.MILLISECONDS);
        if (success) {
            return new SupplierResult<>(result.get(), Duration.ofNanos(end.get() - start.get()));
        } else {
            return null;
        }
    }

    /**
     * Formats and writes a report header to a {@link StringBuilder}.
     *
     * @param sb
     * 		the string builder to append to
     * @param name
     * 		the name of the test
     * @param passed
     *        {@code true} if the test passed, {@code false} otherwise
     */
    public static void reportHeader(final StringBuilder sb, final String name, final boolean passed) {
        sb.append(System.lineSeparator());
        addLine(sb, (passed ? "PASSED - " : "FAILED - ") + name);
    }
}
