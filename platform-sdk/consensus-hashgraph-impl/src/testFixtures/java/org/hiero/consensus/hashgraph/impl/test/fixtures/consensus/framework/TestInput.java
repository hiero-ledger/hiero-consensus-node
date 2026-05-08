// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.hashgraph.impl.test.fixtures.consensus.framework;

import com.swirlds.base.time.Time;
import com.swirlds.config.api.Configuration;
import com.swirlds.metrics.api.Metrics;
import edu.umd.cs.findbugs.annotations.NonNull;
import org.hiero.consensus.test.fixtures.WeightGenerator;

/**
 * Holds the input to a consensus test.
 *
 * @param configuration the {@link Configuration} to use for the test
 * @param metrics the {@link Metrics} to use for the test
 * @param time the {@link Time} to use for the test
 * @param numberOfNodes the number of nodes in the test
 * @param weightGenerator the {@link WeightGenerator} to use for the test
 * @param seed the seed for the random number generator
 * @param eventsToGenerate the number of events to generate
 */
public record TestInput(
        @NonNull Configuration configuration,
        @NonNull Metrics metrics,
        @NonNull Time time,
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
        return new TestInput(configuration, metrics, time, numberOfNodes, weightGenerator, seed, eventsToGenerate);
    }
}
