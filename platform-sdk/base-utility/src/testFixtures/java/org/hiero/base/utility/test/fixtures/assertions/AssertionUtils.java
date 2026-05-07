// SPDX-License-Identifier: Apache-2.0
package org.hiero.base.utility.test.fixtures.assertions;

import static java.util.concurrent.TimeUnit.MILLISECONDS;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import org.hiero.base.CompareTo;
import org.hiero.base.ValueReference;
import org.junit.jupiter.api.Assertions;

public class AssertionUtils {
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
                    Assertions.fail(message);
                } else {
                    Assertions.fail(message, cause.get());
                }
            }

            try {
                MILLISECONDS.sleep(1);
            } catch (final InterruptedException e) {
                Assertions.fail("test was interrupted", e);
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
}
