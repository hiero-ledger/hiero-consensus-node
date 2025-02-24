// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.test.fixtures.consensus.framework;

import com.swirlds.common.context.PlatformContext;
import com.swirlds.common.test.fixtures.WeightGenerator;
import edu.umd.cs.findbugs.annotations.NonNull;

/**
 * Holds the input to a consensus test.
 * @param platformContext the {@link PlatformContext} to use for the test
 * @param numberOfNodes the number of nodes in the test
 * @param weightGenerator the {@link WeightGenerator} to use for the test
 * @param seed the seed for the random number generator
 * @param eventsToGenerate the number of events to generate
 */
public record TestInput(
        @NonNull PlatformContext platformContext,
        int numberOfNodes,
        @NonNull WeightGenerator weightGenerator,
        long seed,
        int eventsToGenerate) {

    /**
     * Create a copy of the test input with updated number of nodes.
     *
     * @param numberOfNodes the new number of nodes
     * @return a new {@link TestInput} with the updated number of nodes
     */
    public @NonNull TestInput setNumberOfNodes(int numberOfNodes) {
        return new TestInput(platformContext, numberOfNodes, weightGenerator, seed, eventsToGenerate);
    }
}
