// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.junit;

import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.extension.Extension;
import org.junit.jupiter.api.extension.TestTemplateInvocationContext;

/**
 * A JUnit 5 invocation context for testing with the Falcon framework.
 */
final class FalconInvocationContext implements TestTemplateInvocationContext {

    /** The repetition count of a replay, which is not part of a sweep. A sweep always has at least one repetition. */
    private static final int REPLAY = 0;

    private final int repetitionCount;
    private final long randomSeed;
    private final List<Extension> extensions;

    private FalconInvocationContext(
            final int repetitionIndex,
            final int repetitionCount,
            final long randomSeed,
            @Nullable final FalconFailureThresholdExtension failureThresholdExtension) {
        this.repetitionCount = repetitionCount;
        this.randomSeed = randomSeed;
        final String repetition = repetitionCount == REPLAY ? "replay" : repetitionIndex + "/" + repetitionCount;
        final FalconEnvironmentExtension environmentExtension = new FalconEnvironmentExtension(randomSeed, repetition);
        this.extensions = failureThresholdExtension == null
                ? List.of(environmentExtension)
                : List.of(failureThresholdExtension, environmentExtension);
    }

    /**
     * Creates the context of a single repetition of a sweep.
     *
     * @param repetitionIndex the one-based index of this repetition within the sweep
     * @param repetitionCount the total number of repetitions of the sweep
     * @param randomSeed the seed of this repetition
     * @param failureCount the number of repetitions of this sweep that have failed so far, shared by all repetitions
     * @param failureThreshold the number of failed repetitions that stops the sweep
     * @return the invocation context
     */
    @NonNull
    static FalconInvocationContext sweep(
            final int repetitionIndex,
            final int repetitionCount,
            final long randomSeed,
            @NonNull final AtomicInteger failureCount,
            final int failureThreshold) {
        return new FalconInvocationContext(
                repetitionIndex,
                repetitionCount,
                randomSeed,
                new FalconFailureThresholdExtension(failureCount, failureThreshold));
    }

    /**
     * Creates the context of a replay of a single, pinned seed. A replay runs a single repetition, so no failure
     * threshold applies.
     *
     * @param randomSeed the seed to replay
     * @return the invocation context
     */
    @NonNull
    static FalconInvocationContext replay(final long randomSeed) {
        return new FalconInvocationContext(REPLAY, REPLAY, randomSeed, null);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public String getDisplayName(final int invocationIndex) {
        return repetitionCount == REPLAY
                ? String.format("[replay] seed=%dL", randomSeed)
                : String.format("[%d/%d] seed=%dL", invocationIndex, repetitionCount, randomSeed);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public List<Extension> getAdditionalExtensions() {
        return extensions;
    }
}
