// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.junit;

import static java.util.Objects.requireNonNull;
import static org.junit.jupiter.api.extension.ConditionEvaluationResult.disabled;
import static org.junit.jupiter.api.extension.ConditionEvaluationResult.enabled;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.concurrent.atomic.AtomicInteger;
import org.hiero.otter.fixtures.FalconTest;
import org.junit.jupiter.api.extension.ConditionEvaluationResult;
import org.junit.jupiter.api.extension.ExecutionCondition;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.TestWatcher;

/**
 * A JUnit 5 extension that stops a Falcon sweep once {@link FalconTest#failureThreshold()} repetitions have failed.
 *
 * <p>One instance is created per repetition, but all instances of a sweep share the same failure counter. Each instance
 * counts the failure of its own repetition and, before its repetition runs, skips it if the repetitions that ran before
 * it have already reached the threshold.
 */
final class FalconFailureThresholdExtension implements TestWatcher, ExecutionCondition {

    private final AtomicInteger failureCount;
    private final int failureThreshold;

    /**
     * Constructor for {@link FalconFailureThresholdExtension}.
     *
     * @param failureCount the number of repetitions of this sweep that have failed so far, shared by all repetitions
     * @param failureThreshold the number of failed repetitions that stops the sweep
     */
    FalconFailureThresholdExtension(@NonNull final AtomicInteger failureCount, final int failureThreshold) {
        this.failureCount = requireNonNull(failureCount);
        this.failureThreshold = failureThreshold;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void testFailed(@NonNull final ExtensionContext context, @NonNull final Throwable cause) {
        failureCount.incrementAndGet();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public ConditionEvaluationResult evaluateExecutionCondition(@NonNull final ExtensionContext context) {
        final int failures = failureCount.get();
        return failures >= failureThreshold
                ? disabled(
                        "Skipped because %d of the preceding repetitions failed, reaching the failure threshold of %d"
                                .formatted(failures, failureThreshold))
                : enabled("Failure threshold of %d not reached yet".formatted(failureThreshold));
    }
}
