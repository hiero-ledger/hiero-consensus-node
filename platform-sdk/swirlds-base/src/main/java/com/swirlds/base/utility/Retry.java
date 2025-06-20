// SPDX-License-Identifier: Apache-2.0
package com.swirlds.base.utility;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

/**
 * Utility class that provides a method to retry a given operation until a specified condition is met or the maximum
 * number of attempts have been exhausted. Methods may support an optional delay between each retry attempt.
 */
public final class Retry {
    /**
     * The default delay between retry attempts.
     */
    static final Duration DEFAULT_RETRY_DELAY = Duration.ofMillis(500);
    /**
     * The default amount of time to wait for the file, directory, block device, or symlink to be available.
     */
    static final Duration DEFAULT_WAIT_TIME = Duration.ofSeconds(10);

    /**
     * Private constructor to prevent utility class instantiation.
     **/
    private Retry() {}

    /**
     * Evaluates a given {@code value} using the provided {@code checkFn} method until the return value is {@code true}
     * or the {@code maxWaitTime} is exceeded. A delay of {@code retryDelay} will be applied between each call to the
     * {@code checkFn} method.
     *
     * @param checkFn     the function to be called to check the user value.
     * @param value       the user value to be passed to the {@code checkFn} method.
     * @param maxWaitTime the maximum duration to wait for the {@code checkFn} method to return true.
     * @param retryDelay  the delay between retry attempts. must be greater than zero and less than or equal to the
     *                    {@code maxWaitTime}.
     * @param <T>         the type of the value argument.
     * @return true if the {@code checkFn} method returns true before all attempts have been exhausted; false
     * if the retries are exhausted.
     * @throws NullPointerException     if the {@code checkFn}, the {@code value}, the {@code maxWaitTime}, or the
     *                                  {@code retryDelay} arguments are a {@code null} value.
     * @throws IllegalArgumentException if the {@code maxWaitTime} argument is less than or equal to zero (0), the
     *                                  {@code retryDelay} argument is less than or equal to zero (0), or the {@code retryDelay} argument
     *                                  is greater than the {@code maxWaitTime}.
     * @throws InterruptedException     if the thread is interrupted while waiting.
     */
    public static <T> boolean check(
            @NonNull final Function<T, Boolean> checkFn,
            @NonNull final T value,
            @NonNull final Duration maxWaitTime,
            @NonNull final Duration retryDelay)
            throws InterruptedException {
        Objects.requireNonNull(checkFn, "checkFn must not be null");
        Objects.requireNonNull(value, "value must not be null");
        Objects.requireNonNull(maxWaitTime, "maxWaitTime must not be null");
        Objects.requireNonNull(retryDelay, "retryDelay must not be null");

        if (maxWaitTime.isNegative() || maxWaitTime.isZero()) {
            throw new IllegalArgumentException("The maximum wait time must be greater than zero (0)");
        }

        if (retryDelay.isNegative() || retryDelay.isZero()) {
            throw new IllegalArgumentException("The retry delay must be greater than zero (0)");
        }

        if (retryDelay.compareTo(maxWaitTime) > 0) {
            throw new IllegalArgumentException("The retry delay must be less than or equal to the maximum wait time");
        }

        final int maxAttempts = Math.round(maxWaitTime.dividedBy(retryDelay));
        return check(checkFn, value, maxAttempts, retryDelay.toMillis());
    }

    /**
     * Evaluates a given {@code value} using the provided {@code checkFn} method until the return value is {@code true}
     * or the {@code maxAttempts} are exhausted. A delay of {@code delayMs} will be applied between each call to the
     * {@code checkFn} method. Setting the {@code delayMs} parameter to zero (0) will disable the delay mechanism and
     * the {@code checkFn} method will be called as fast as possible.
     *
     * @param checkFn     the function to be called to check the user value.
     * @param value       the user value to be passed to the {@code checkFn} method.
     * @param maxAttempts the maximum number of retry attempts.
     * @param delayMs     the delay between retry attempts. must greater than or equal to zero (0). if a zero value is
     *                    specified then no delay will be applied.
     * @param <T>         the type of the value argument.
     * @return true if the {@code checkFn} method returns true before all attempts have been exhausted; false
     * if the retries are exhausted.
     * @throws NullPointerException     if the {@code checkFn} or the {@code value} arguments are a {@code null} values.
     * @throws IllegalArgumentException if the {@code maxAttempts} argument is less than or equal to zero (0) or the
     *                                  {@code delayMs} argument is less than zero (0).
     * @throws InterruptedException     if the thread is interrupted while waiting.
     */
    public static <T> boolean check(
            @NonNull final Function<T, Boolean> checkFn,
            @NonNull final T value,
            final int maxAttempts,
            final long delayMs)
            throws InterruptedException {
        Objects.requireNonNull(checkFn, "checkFn must not be null");
        Objects.requireNonNull(value, "value must not be null");

        if (maxAttempts <= 0) {
            throw new IllegalArgumentException("The maximum number of attempts must be greater than zero (0)");
        }

        if (delayMs < 0) {
            throw new IllegalArgumentException("The delay must be greater than or equal to zero (0)");
        }

        for (int i = 0; i < maxAttempts; i++) {
            if (checkFn.apply(value)) {
                return true;
            }

            if (delayMs > 0) {
                TimeUnit.MILLISECONDS.sleep(delayMs);
            }
        }

        // If the checkFn failed to resolve to a true value then we should fall
        // through to here and fail the operation.
        return false;
    }

    /**
     * Evaluates a given {@code value} using the provided {@code resolveFn} method until a non-null result is returned
     * and no exceptions are thrown.
     *
     * @param resolveFn   the function to be called to resolve the value to a result.
     * @param value       the user value to be passed to the {@code checkFn} method.
     * @param maxWaitTime the maximum duration to wait for the {@code checkFn} method to return true.
     * @param retryDelay  the delay between retry attempts. must be greater than zero and less than or equal to the
     *                    {@code maxWaitTime}.
     * @param <T>         the type of the value argument.
     * @param <R>         the type of the return value.
     * @return the resolved value.
     * @throws NullPointerException     if the {@code resolveFn} or the {@code value} arguments are a {@code null} values.
     * @throws IllegalArgumentException if the {@code maxAttempts} argument is less than or equal to zero (0) or the
     * @throws ExecutionException     if the resolve function fails to return a non-null value after the maximum number of attempts.
     * @throws InterruptedException if the thread is interrupted while waiting.
     */
    public static <T, R> R resolve(
            @NonNull final ThrowableFunction<T, R> resolveFn,
            @NonNull final T value,
            @NonNull final Duration maxWaitTime,
            @NonNull final Duration retryDelay)
            throws InterruptedException, ExecutionException {
        Objects.requireNonNull(resolveFn, "resolveFn must not be null");
        Objects.requireNonNull(value, "value must not be null");
        Objects.requireNonNull(maxWaitTime, "maxWaitTime must not be null");
        Objects.requireNonNull(retryDelay, "retryDelay must not be null");

        if (maxWaitTime.isNegative() || maxWaitTime.isZero()) {
            throw new IllegalArgumentException("The maximum wait time must be greater than zero (0)");
        }

        if (retryDelay.isNegative() || retryDelay.isZero()) {
            throw new IllegalArgumentException("The retry delay must be greater than zero (0)");
        }

        if (retryDelay.compareTo(maxWaitTime) > 0) {
            throw new IllegalArgumentException("The retry delay must be less than or equal to the maximum wait time");
        }

        final int maxAttempts = Math.round(maxWaitTime.dividedBy(retryDelay));
        return resolve(resolveFn, value, maxAttempts, retryDelay.toMillis());
    }

    /**
     * Evaluates a given {@code value} using the provided {@code resolveFn} method until a non-null result is returned
     * and no exceptions are thrown.
     *
     * @param resolveFn   the function to be called to resolve the value to a result.
     * @param value       the user value to be passed to the {@code checkFn} method.
     * @param maxAttempts the maximum number of retry attempts.
     * @param delayMs     the delay between retry attempts. must greater than or equal to zero (0). if a zero value is
     *                    specified then no delay will be applied.
     * @param <T>         the type of the value argument.
     * @param <R>         the type of the return value.
     * @return the resolved value.
     * @throws NullPointerException     if the {@code resolveFn} or the {@code value} arguments are a {@code null} values.
     * @throws IllegalArgumentException if the {@code maxAttempts} argument is less than or equal to zero (0) or the
     * @throws ExecutionException     if the resolve function fails to return a non-null value after the maximum number of attempts.
     * @throws InterruptedException if the thread is interrupted while waiting.
     */
    public static <T, R> R resolve(
            @NonNull final ThrowableFunction<T, R> resolveFn,
            @NonNull final T value,
            final int maxAttempts,
            final long delayMs)
            throws InterruptedException, ExecutionException {
        Objects.requireNonNull(resolveFn, "resolveFn must not be null");
        Objects.requireNonNull(value, "value must not be null");

        if (maxAttempts <= 0) {
            throw new IllegalArgumentException("The maximum number of attempts must be greater than zero (0)");
        }

        if (delayMs < 0) {
            throw new IllegalArgumentException("The delay must be greater than or equal to zero (0)");
        }

        Throwable lastException = null;
        for (int i = 0; i < maxAttempts; i++) {
            try {
                final R result = resolveFn.apply(value);

                if (result != null) {
                    return result;
                }
            } catch (final Throwable ex) {
                // Ignore the exception and continue to retry while saving it for later.
                lastException = ex;
            }

            if (delayMs > 0) {
                TimeUnit.MILLISECONDS.sleep(delayMs);
            }
        }

        throw new ExecutionException(
                "The resolve function failed to return a non-null value after " + maxAttempts + " attempts",
                lastException);
    }
}
