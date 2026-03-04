// SPDX-License-Identifier: Apache-2.0
package com.hedera.statevalidation.validator.util;

import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;

/**
 * Utility class providing assertion-like methods for state validation.
 */
public final class ValidationAssertions {

    private ValidationAssertions() {}

    /**
     * Validates that an object is not null.
     *
     * @param obj the object to check
     * @param validatorName the name of the validator performing the check
     * @param <T> the type of the object
     * @return the non-null object
     * @throws ValidationException if the object is null
     */
    public static <T> T requireNonNull(@Nullable T obj, @NonNull String validatorName) {
        if (obj == null) {
            throw new ValidationException(validatorName, "Expected non-null value but was null");
        }
        return obj;
    }

    /**
     * Validates that a condition is true.
     *
     * @param condition the condition to check
     * @param validatorName the name of the validator performing the check
     * @throws ValidationException if the condition is false
     */
    public static void requireTrue(boolean condition, @NonNull String validatorName) {
        if (!condition) {
            throw new ValidationException(validatorName, "Expected condition to be true but was false");
        }
    }

    /**
     * Validates that a condition is true with a custom message.
     *
     * @param condition the condition to check
     * @param validatorName the name of the validator performing the check
     * @param message custom error message
     * @throws ValidationException if the condition is false
     */
    public static void requireTrue(boolean condition, @NonNull String validatorName, @NonNull String message) {
        if (!condition) {
            throw new ValidationException(validatorName, message);
        }
    }

    /**
     * Validates that two values are equal.
     *
     * @param expected the expected value
     * @param actual the actual value
     * @param validatorName the name of the validator performing the check
     * @throws ValidationException if the values are not equal
     */
    public static void requireEqual(@Nullable Object expected, @Nullable Object actual, @NonNull String validatorName) {
        if (!java.util.Objects.equals(expected, actual)) {
            throw new ValidationException(validatorName, String.format("Expected <%s> but was <%s>", expected, actual));
        }
    }

    /**
     * Validates that two long values are equal.
     * Specialized version for better performance and error messages with numeric values.
     *
     * @param expected the expected value
     * @param actual the actual value
     * @param validatorName the name of the validator performing the check
     * @throws ValidationException if the values are not equal
     */
    public static void requireEqual(long expected, long actual, @NonNull String validatorName) {
        if (expected != actual) {
            throw new ValidationException(validatorName, String.format("Expected <%d> but was <%d>", expected, actual));
        }
    }

    /**
     * Validates that two long values are equal.
     * Specialized version for better performance and error messages with numeric values.
     *
     * @param expected the expected value
     * @param actual the actual value
     * @param validatorName the name of the validator performing the check
     * @throws ValidationException if the values are not equal
     */
    public static void requireEqual(
            long expected, long actual, @NonNull String message, @NonNull String validatorName) {
        if (expected != actual) {
            throw new ValidationException(
                    validatorName, String.format("Expected <%d> but was <%d>. %s", expected, actual, message));
        }
    }

    /**
     * Validates that two values are not equal.
     *
     * @param expected the expected value
     * @param actual the actual value
     * @param validatorName the name of the validator performing the check
     * @throws ValidationException if the values are equal
     */
    public static void requireNotEqual(
            @Nullable Object expected, @Nullable Object actual, @NonNull String validatorName) {
        if (java.util.Objects.equals(expected, actual)) {
            throw new ValidationException(
                    validatorName, String.format("Expected not equal <%s> but was <%s>", expected, actual));
        }
    }

    /**
     * Validates that two long values are not equal.
     * Specialized version for better performance and error messages with numeric values.
     *
     * @param expected the expected value
     * @param actual the actual value
     * @param validatorName the name of the validator performing the check
     * @throws ValidationException if the values are equal
     */
    public static void requireNotEqual(long expected, long actual, @NonNull String validatorName) {
        if (expected == actual) {
            throw new ValidationException(
                    validatorName, String.format("Expected not equal <%d> but was <%d>", expected, actual));
        }
    }
}
