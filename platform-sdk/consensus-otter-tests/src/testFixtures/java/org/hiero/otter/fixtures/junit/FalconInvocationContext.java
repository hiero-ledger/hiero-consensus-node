// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.junit;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.List;
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
    private final FalconEnvironmentExtension extension;

    private FalconInvocationContext(final int repetitionCount, final long randomSeed) {
        this.repetitionCount = repetitionCount;
        this.randomSeed = randomSeed;
        this.extension = new FalconEnvironmentExtension(randomSeed);
    }

    /**
     * Creates the context of a single repetition of a sweep.
     *
     * @param repetitionCount the total number of repetitions of the sweep
     * @param randomSeed the seed of this repetition
     * @return the invocation context
     */
    @NonNull
    static FalconInvocationContext sweep(final int repetitionCount, final long randomSeed) {
        return new FalconInvocationContext(repetitionCount, randomSeed);
    }

    /**
     * Creates the context of a replay of a single, pinned seed.
     *
     * @param randomSeed the seed to replay
     * @return the invocation context
     */
    @NonNull
    static FalconInvocationContext replay(final long randomSeed) {
        return new FalconInvocationContext(REPLAY, randomSeed);
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
        return List.of(extension);
    }
}
