// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.junit;

import static java.util.Objects.requireNonNull;

import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.ArrayList;
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

    private final ArgumentSet argumentSet;
    private final String repetition;
    private final long randomSeed;
    private final List<Extension> extensions;

    private FalconInvocationContext(
            @NonNull final ArgumentSet argumentSet,
            final int repetitionIndex,
            final int repetitionCount,
            final long randomSeed,
            @Nullable final FalconFailureThresholdExtension failureThresholdExtension) {
        this.argumentSet = requireNonNull(argumentSet);
        this.repetition = repetitionCount == REPLAY ? "replay" : repetitionIndex + "/" + repetitionCount;
        this.randomSeed = randomSeed;
        final List<Extension> extensions = new ArrayList<>();
        if (failureThresholdExtension != null) {
            extensions.add(failureThresholdExtension);
        }
        if (!argumentSet.isEmpty()) {
            extensions.add(new ArgumentSetExtension(argumentSet));
        }
        extensions.add(new FalconEnvironmentExtension(randomSeed, repetition));
        this.extensions = List.copyOf(extensions);
    }

    /**
     * Creates the context of a single repetition of a sweep.
     *
     * @param argumentSet the argument set of the sweep, or {@link ArgumentSet#EMPTY} if the test has no argument source
     * @param repetitionIndex the one-based index of this repetition within the sweep
     * @param repetitionCount the total number of repetitions of the sweep
     * @param randomSeed the seed of this repetition
     * @param failureCount the number of repetitions of this sweep that have failed so far, shared by all repetitions
     * @param failureThreshold the number of failed repetitions that stops the sweep
     * @return the invocation context
     */
    @NonNull
    static FalconInvocationContext sweep(
            @NonNull final ArgumentSet argumentSet,
            final int repetitionIndex,
            final int repetitionCount,
            final long randomSeed,
            @NonNull final AtomicInteger failureCount,
            final int failureThreshold) {
        return new FalconInvocationContext(
                argumentSet,
                repetitionIndex,
                repetitionCount,
                randomSeed,
                new FalconFailureThresholdExtension(failureCount, failureThreshold));
    }

    /**
     * Creates the context of a replay of a single, pinned seed. A replay runs a single repetition, so no failure
     * threshold applies.
     *
     * @param argumentSet the argument set to replay, or {@link ArgumentSet#EMPTY} if the test has no argument source
     * @param randomSeed the seed to replay
     * @return the invocation context
     */
    @NonNull
    static FalconInvocationContext replay(@NonNull final ArgumentSet argumentSet, final long randomSeed) {
        return new FalconInvocationContext(argumentSet, REPLAY, REPLAY, randomSeed, null);
    }

    /**
     * {@inheritDoc}
     *
     * <p>The display name ignores the {@code invocationIndex}, which counts the invocations of all argument sets, and
     * uses the position of the repetition within the sweep of its argument set instead.
     */
    @Override
    @NonNull
    public String getDisplayName(final int invocationIndex) {
        final String arguments = argumentSet.isEmpty() ? "" : "[" + argumentSet.label() + "] ";
        return String.format("%s[%s] seed=%dL", arguments, repetition, randomSeed);
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
