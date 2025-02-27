// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.spec.utilops.streams.assertions;

import static java.util.Objects.requireNonNull;

import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.time.Duration;

/**
 * Represents the result of an assertion. The result can be either a success, a failure, or a timeout.
 */
public class AssertionResult {
    private static final AssertionResult SUCCESS = new AssertionResult(null, Outcome.SUCCESS);

    /**
     * The possible outcomes of an assertion.
     */
    public enum Outcome {
        SUCCESS,
        FAILURE,
        TIMEOUT,
    }

    /**
     * The details of the error that caused the assertion to fail, or null if the assertion passed.
     */
    @Nullable
    private final String errorDetails;
    /**
     * The outcome of the assertion.
     */
    private final Outcome outcome;

    /**
     * Creates a new {@link AssertionResult} with the given error details and outcome.
     * @param errorDetails the details of the error that caused the assertion to fail
     * @param outcome the outcome of the assertion
     */
    public AssertionResult(@Nullable final String errorDetails, @NonNull final Outcome outcome) {
        this.outcome = requireNonNull(outcome);
        this.errorDetails = errorDetails;
    }

    /**
     * Returns an {@link AssertionResult} that represents a successful assertion.
     * @return the successful assertion result
     */
    public static AssertionResult newSuccess() {
        return SUCCESS;
    }

    /**
     * Returns an {@link AssertionResult} that represents a timed out assertion.
     * @param timeout the duration of the timeout
     * @return the timed out assertion result
     */
    public static AssertionResult newTimeout(@NonNull final Duration timeout) {
        requireNonNull(timeout);
        return new AssertionResult("Timed out in " + timeout, Outcome.TIMEOUT);
    }

    /**
     * Returns an {@link AssertionResult} that represents a failed assertion.
     * @param failureDetails the details of the error that caused the assertion to fail
     * @return the failed assertion result
     */
    public static AssertionResult failure(@NonNull final String failureDetails) {
        requireNonNull(failureDetails);
        return new AssertionResult(failureDetails, Outcome.FAILURE);
    }

    /**
     * Returns true if the assertion passed.
     * @return true if the assertion passed
     */
    public boolean passed() {
        return outcome == Outcome.SUCCESS;
    }

    /**
     * Returns the details of the error that caused the assertion to fail, or null if the assertion passed.
     * @return the details of the error that caused the assertion to fail
     */
    public @Nullable String getErrorDetails() {
        return errorDetails;
    }
}
