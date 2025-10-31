// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.swirlds.common.test.fixtures.WeightGenerator;
import com.swirlds.common.test.fixtures.WeightGenerators;
import java.util.List;
import org.hiero.otter.fixtures.turtle.TurtleTestEnvironment;
import org.junit.jupiter.api.Test;

/**
 * Tests for weight generator functionality in the Network interface.
 */
class WeightGeneratorTest {

    /**
     * Test that the weight generator can be set before the network starts, and that the correct balanced weights are
     * applied.
     *
     */
    @Test
    void testWeightGeneratorCanBeSetBeforeNetworkStarts() {
        final TurtleTestEnvironment env = new TurtleTestEnvironment();
        try {
            final Network network = env.network();

            // Set the weight generator before adding nodes
            network.weightGenerator(WeightGenerators.BALANCED);

            // Add nodes
            final List<Node> nodes = network.addNodes(4);

            // Start network
            network.start();

            // Verify balanced weights (all equal)
            long firstWeight = nodes.getFirst().weight();
            assertThat(firstWeight).isGreaterThan(0L);

            for (Node node : nodes) {
                assertThat(node.weight()).isEqualTo(firstWeight);
            }
        } finally {
            env.destroy();
        }
    }

    /**
     * Test that weight generator cannot be set when network is running.
     *
     */
    @Test
    void testWeightGeneratorCannotBeSetWhenNetworkIsRunning() {
        final TurtleTestEnvironment env = new TurtleTestEnvironment();
        try {
            final Network network = env.network();

            // Add nodes and start
            network.addNodes(2);
            network.start();

            // Try to set the weight generator while running
            assertThatThrownBy(() -> network.weightGenerator(WeightGenerators.BALANCED))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Cannot set weight generator when the network is running");
        } finally {
            env.destroy();
        }
    }

    /**
     * Test that the default generator is Gaussian, adn that it produces varied weights.
     *
     */
    @Test
    void testGaussianWeightGeneratorProducesVariedWeights() {
        final TurtleTestEnvironment env = new TurtleTestEnvironment();
        try {
            final Network network = env.network();

            // Add multiple nodes to get variation
            final List<Node> nodes = network.addNodes(10);

            // Start network
            network.start();

            // Verify all weights are non-negative
            for (Node node : nodes) {
                assertThat(node.weight()).isGreaterThanOrEqualTo(0L);
            }

            // Verify weights are not all the same (with high probability)
            long firstWeight = nodes.getFirst().weight();
            boolean hasVariation = false;
            for (int i = 1; i < nodes.size(); i++) {
                if (nodes.get(i).weight() != firstWeight) {
                    hasVariation = true;
                    break;
                }
            }
            assertThat(hasVariation).isTrue();
        } finally {
            env.destroy();
        }
    }

    /**
     * Test that the weight generator can be changed before the network starts.
     *
     */
    @Test
    void testWeightGeneratorCanBeChangedBeforeStart() {
        final TurtleTestEnvironment env = new TurtleTestEnvironment();
        try {
            final Network network = env.network();

            // Set one generator
            network.weightGenerator(WeightGenerators.GAUSSIAN);

            // Change to another
            network.weightGenerator(WeightGenerators.BALANCED);

            // Add nodes
            final List<Node> nodes = network.addNodes(4);

            // Start network
            network.start();

            // Verify the second generator was used (balanced = all equal)
            long firstWeight = nodes.getFirst().weight();
            for (Node node : nodes) {
                assertThat(node.weight()).isEqualTo(firstWeight);
            }
        } finally {
            env.destroy();
        }
    }

    /**
     * Test a custom weight generator with incrementing weights.
     *
     */
    @Test
    void testCustomWeightGenerator() {
        final TurtleTestEnvironment env = new TurtleTestEnvironment();
        try {
            final Network network = env.network();

            // Create a custom generator that produces incrementing weights
            WeightGenerator customGenerator = (seed, count) -> {
                List<Long> weights = new java.util.ArrayList<>();
                for (int i = 0; i < count; i++) {
                    weights.add((i + 1) * 100L);
                }
                return weights;
            };

            // Set custom generator
            network.weightGenerator(customGenerator);

            // Add nodes
            final List<Node> nodes = network.addNodes(5);

            // Start network
            network.start();

            // Verify weights match custom generator pattern
            // Note: nodes might not be in order, so we just verify the weights exist
            List<Long> actualWeights = nodes.stream().map(Node::weight).sorted().toList();

            assertThat(actualWeights).containsExactly(100L, 200L, 300L, 400L, 500L);
        } finally {
            env.destroy();
        }
    }

    /**
     * Test weight generator with a single node.
     *
     */
    @Test
    void testWeightGeneratorWithSingleNode() {
        final TurtleTestEnvironment env = new TurtleTestEnvironment();
        try {
            final Network network = env.network();

            // Set balanced generator
            network.weightGenerator(WeightGenerators.BALANCED);

            // Add a single node
            final List<Node> nodes = network.addNodes(1);

            // Start network
            network.start();

            // Verify single node has positive weight
            assertThat(nodes.getFirst().weight()).isGreaterThan(0L);
        } finally {
            env.destroy();
        }
    }
}
