// SPDX-License-Identifier: Apache-2.0
package com.swirlds.common.test.fixtures;

import static com.swirlds.common.threading.manager.AdHocThreadManager.getStaticThreadManager;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.swirlds.common.threading.framework.config.ThreadConfiguration;
import java.time.Duration;
import java.time.Instant;
import java.util.Iterator;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import org.hiero.base.CompareTo;
import org.hiero.base.ValueReference;
import org.hiero.base.concurrent.interrupt.InterruptableRunnable;
import org.hiero.base.concurrent.interrupt.InterruptableSupplier;

/**
 * Contains various useful assertions.
 */
public final class AssertionUtils {

    private AssertionUtils() {}

    /**
     * <p>
     * Assert that a condition eventually becomes true.
     * Sleeps for 1ms between each check of the condition.
     * </p>
     *
     * <p>
     * This method is NOT safe to interrupt. Interrupting this
     * method may case an assertion to be triggered that fails
     * the test.
     * </p>
     *
     * @param condition
     * 		the condition to test
     * @param maxDuration
     * 		the maximum amount of time to wait
     * @param message
     * 		a message that is displayed if the condition does not become true
     * @param cause
     * 		provides the thing that caused this assertion to fail
     */
    public static void assertEventuallyTrue(
            final BooleanSupplier condition,
            final Duration maxDuration,
            final String message,
            final Supplier<Throwable> cause) {

        final Instant start = Instant.now();

        while (true) {
            if (condition.getAsBoolean()) {
                return;
            }

            final Instant now = Instant.now();
            final Duration elapsed = Duration.between(start, now);
            if (CompareTo.isGreaterThan(elapsed, maxDuration)) {
                condition.getAsBoolean();
                if (cause == null) {
                    fail(message);
                } else {
                    fail(message, cause.get());
                }
            }

            try {
                MILLISECONDS.sleep(1);
            } catch (final InterruptedException e) {
                fail("test was interrupted", e);
            }
        }
    }

    /**
     * <p>
     * Assert that a condition eventually becomes true.
     * Sleeps for 1ms between each check of the condition.
     * </p>
     *
     * <p>
     * This method is NOT safe to interrupt. Interrupting this
     * method may case an assertion to be triggered that fails
     * the test.
     * </p>
     *
     * @param condition
     * 		the condition to test
     * @param maxDuration
     * 		the maximum amount of time to wait
     * @param message
     * 		a message that is displayed if the condition does not become true
     */
    public static void assertEventuallyTrue(
            final BooleanSupplier condition, final Duration maxDuration, final String message) {
        assertEventuallyTrue(condition, maxDuration, message, null);
    }

    /**
     * <p>
     * Assert that a condition eventually becomes false.
     * Sleeps for 1ms between each check of the condition.
     * </p>
     *
     * <p>
     * This method is NOT safe to interrupt. Interrupting this
     * method may case an assertion to be triggered that fails
     * the test.
     * </p>
     *
     * @param condition
     * 		the condition to test
     * @param maxDuration
     * 		the maximum amount of time to wait
     * @param message
     * 		a message that is displayed if the condition does not become false
     */
    public static void assertEventuallyFalse(
            final BooleanSupplier condition, final Duration maxDuration, final String message) {
        assertEventuallyTrue(() -> !condition.getAsBoolean(), maxDuration, message);
    }

    /**
     * <p>
     * Assert that a method eventually returns an object equal to the expected value.
     * </p>
     *
     * <p>
     * A common reason for this method to fail is if it attempts to compare two similar but different
     * data types, for example an integer and a long. Ensure that both data types are exactly the same.
     * </p>
     *
     * @param expected
     * 		the expected value
     * @param actual
     * 		a method that returns a value
     * @param maxDuration
     * 		the max amount of time to wait
     * @param message
     * 		a message that is displayed if the expected object is never returned
     * @param <T>
     * 		the type of the object being compared
     */
    public static <T> void assertEventuallyEquals(
            final T expected, final Supplier<T> actual, final Duration maxDuration, final String message) {
        assertEventuallyTrue(() -> Objects.equals(expected, actual.get()), maxDuration, message);
    }

    /**
     * Assert that a method eventually returns an object that is the same as the expected value.
     *
     * @param expected
     * 		the expected value
     * @param actual
     * 		a method that returns a value
     * @param maxDuration
     * 		the max amount of time to wait
     * @param message
     * 		a message that is displayed if the expected object is never returned
     */
    public static void assertEventuallySame(
            final Object expected, final Supplier<Object> actual, final Duration maxDuration, final String message) {
        assertEventuallyTrue(() -> expected == actual.get(), maxDuration, message);
    }

    /**
     * Assert that an operation eventually stops throwing exceptions.
     *
     * @param operation
     * 		the operation to test
     * @param maxDuration
     * 		the maximum amount of time to wait
     * @param message
     * 		a message that is displayed if the method does not stop throwing
     */
    public static void assertEventuallyDoesNotThrow(
            final Runnable operation, final Duration maxDuration, final String message) {

        final ValueReference<Throwable> mostRecentException = new ValueReference<>();

        assertEventuallyTrue(
                () -> {
                    try {
                        operation.run();
                        return true;
                    } catch (final Throwable e) {
                        mostRecentException.setValue(e);
                        return false;
                    }
                },
                maxDuration,
                message,
                mostRecentException::getValue);
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
     */
    public static void completeBeforeTimeout(
            final InterruptableRunnable operation, final Duration maxDuration, final String message)
            throws InterruptedException {

        final CountDownLatch latch = new CountDownLatch(1);
        final AtomicBoolean error = new AtomicBoolean();

        new ThreadConfiguration(getStaticThreadManager())
                .setComponent("assertion-utils")
                .setThreadName("assert-prompt-completion")
                .setInterruptableRunnable(() -> {
                    operation.run();
                    latch.countDown();
                })
                .setExceptionHandler((final Thread thread, final Throwable exception) -> {
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

        new ThreadConfiguration(getStaticThreadManager())
                .setComponent("assertion-utils")
                .setThreadName("assert-prompt-completion")
                .setInterruptableRunnable(() -> {
                    value.set(operation.get());
                    latch.countDown();
                })
                .setExceptionHandler((final Thread thread, final Throwable exception) -> {
                    error.set(true);
                    exception.printStackTrace();
                })
                .build(true);

        assertFalse(error.get(), "exception encountered while handling operation");
        final boolean completed = latch.await(maxDuration.toMillis(), MILLISECONDS);
        assertTrue(completed, message);

        return value.get();
    }

    /**
     * Run an operation and fail if the operation takes too long to throw an exception
     * or if the exception type is wrong.
     *
     * @param expectedException
     * 		the exception that is expected
     * @param operation
     * 		the operation to run
     * @param maxDuration
     * 		the maximum amount of time to wait for the operation to complete
     * @param message
     * 		an error message if the operation takes too long or if the wrong type is thrown
     */
    public static void throwBeforeTimeout(
            final Class<? extends Throwable> expectedException,
            final Runnable operation,
            final Duration maxDuration,
            final String message)
            throws InterruptedException {

        final CountDownLatch latch = new CountDownLatch(1);
        final AtomicBoolean error = new AtomicBoolean();

        new ThreadConfiguration(getStaticThreadManager())
                .setComponent("assertion-utils")
                .setThreadName("assert-prompt-throw")
                .setRunnable(() -> {
                    assertThrows(expectedException, operation::run, message);
                    latch.countDown();
                })
                .setExceptionHandler((final Thread thread, final Throwable exception) -> {
                    error.set(true);
                    exception.printStackTrace();
                })
                .build(true);

        assertFalse(error.get(), message);

        final boolean completed = latch.await(maxDuration.toMillis(), MILLISECONDS);
        assertTrue(completed, message);
    }

    /**
     * Walk over two iterators and assert that each element returned is equal
     *
     * @param iteratorA
     * 		the first iterator
     * @param iteratorB
     * 		the second iterator
     * @param <T>
     * 		the type of the data returned by the iterator
     */
    public static <T> void assertIteratorEquality(final Iterator<T> iteratorA, final Iterator<T> iteratorB) {
        int count = 0;
        while (iteratorA.hasNext() && iteratorB.hasNext()) {
            assertEquals(iteratorA.next(), iteratorB.next(), "The element at position " + count + " does not match.");
            count++;
        }
        assertFalse(iteratorA.hasNext(), "iterator A is not depleted");
        assertFalse(iteratorB.hasNext(), "iterator B is not depleted");
    }

    /**
     * Validate that two double values are approximately equal. Useful for testing values that may have accumulated
     * floating point errors.
     *
     * @param expected
     * 		the expected value
     * @param actual
     * 		the actual value
     * @param tolerance
     * 		the permitted tolerance
     */
    public static void assertApproximatelyEquals(final double expected, final double actual, final double tolerance) {
        assertTrue(Math.abs(expected - actual) <= tolerance, "expected: " + expected + ", actual: " + actual);
    }

    /**
     * Validate that two double values are approximately equal. Useful for testing values that may have accumulated
     * floating point errors.
     *
     * @param expected
     * 		the expected value
     * @param actual
     * 		the actual value
     */
    public static void assertApproximatelyEquals(final double expected, final double actual) {
        assertApproximatelyEquals(expected, actual, 0.000001);
    }
}
