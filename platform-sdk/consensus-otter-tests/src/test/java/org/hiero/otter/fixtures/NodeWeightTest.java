// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hiero.otter.fixtures.Constants.RANDOM_SEED;

import com.swirlds.common.test.fixtures.WeightGenerators;
import java.util.List;
import org.hiero.otter.fixtures.turtle.TurtleTestEnvironment;
import org.junit.jupiter.api.Test;

/**
 * Tests for node weight functionality in the Network and Node interfaces.
 */
class NodeWeightTest {

    /**
     * Test that individual node weights can be set before the network starts.
     */
    @Test
    void testSetIndividualNodeWeightsBeforeNetworkStarts() {
        final TurtleTestEnvironment env = new TurtleTestEnvironment(RANDOM_SEED);
        try {
            final Network network = env.network();

            // Add nodes
            final List<Node> nodes = network.addNodes(4);
            final Node node0 = nodes.get(0);
            final Node node1 = nodes.get(1);
            final Node node2 = nodes.get(2);
            final Node node3 = nodes.get(3);

            // Set individual weights
            node0.weight(100L);
            node1.weight(200L);
            node2.weight(300L);
            node3.weight(400L);

            // Verify weights are set
            assertThat(node0.weight()).isEqualTo(100L);
            assertThat(node1.weight()).isEqualTo(200L);
            assertThat(node2.weight()).isEqualTo(300L);
            assertThat(node3.weight()).isEqualTo(400L);

            // Start the network and verify weights persist
            network.start();

            assertThat(node0.weight()).isEqualTo(100L);
            assertThat(node1.weight()).isEqualTo(200L);
            assertThat(node2.weight()).isEqualTo(300L);
            assertThat(node3.weight()).isEqualTo(400L);

            // Verify total weight
            assertThat(network.totalWeight()).isEqualTo(1000L);
        } finally {
            env.destroy();
        }
    }

    /**
     * Test that node weight cannot be set while the node is running.
     */
    @Test
    void testCannotSetNodeWeightWhileRunning() {
        final TurtleTestEnvironment env = new TurtleTestEnvironment(RANDOM_SEED);
        try {
            final Network network = env.network();

            // Add nodes
            final List<Node> nodes = network.addNodes(2);
            final Node node0 = nodes.getFirst();

            // Set initial weight
            node0.weight(100L);

            // Start network
            network.start();

            // Try to change weight while running
            assertThatThrownBy(() -> node0.weight(200L))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Cannot set weight while the node is running");
        } finally {
            env.destroy();
        }
    }

    /**
     * Test that Network.nodeWeight() sets all nodes to the same weight.
     */
    @Test
    void testNetworkNodeWeightSetsAllNodes() {
        final TurtleTestEnvironment env = new TurtleTestEnvironment(RANDOM_SEED);
        try {
            final Network network = env.network();

            // Add nodes
            final List<Node> nodes = network.addNodes(5);

            // Set all nodes to the same weight
            network.nodeWeight(500L);

            // Verify all nodes have the same weight
            for (Node node : nodes) {
                assertThat(node.weight()).isEqualTo(500L);
            }

            // Start the network and verify weights persist
            network.start();

            for (Node node : nodes) {
                assertThat(node.weight()).isEqualTo(500L);
            }

            // Verify total weight
            assertThat(network.totalWeight()).isEqualTo(2500L);
        } finally {
            env.destroy();
        }
    }

    /**
     * Test that Network.nodeWeight() throws when no nodes exist.
     */
    @Test
    void testNodeWeightThrowsWhenNoNodes() {
        final TurtleTestEnvironment env = new TurtleTestEnvironment(RANDOM_SEED);
        try {
            final Network network = env.network();

            // Try to set weight without adding nodes
            assertThatThrownBy(() -> network.nodeWeight(100L))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Cannot set node weight when there are no nodes in the network");
        } finally {
            env.destroy();
        }
    }

    /**
     * Test that Network.nodeWeight() throws when weight is zero.
     */
    @Test
    void testNodeWeightThrowsWhenWeightIsZero() {
        final TurtleTestEnvironment env = new TurtleTestEnvironment(RANDOM_SEED);
        try {
            final Network network = env.network();
            network.addNodes(2);

            // Try to set weight to zero
            assertThatThrownBy(() -> network.nodeWeight(0L))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Weight must be positive");
        } finally {
            env.destroy();
        }
    }

    /**
     * Test that Network.nodeWeight() throws when weight is negative.
     */
    @Test
    void testNodeWeightThrowsWhenWeightIsNegative() {
        final TurtleTestEnvironment env = new TurtleTestEnvironment(RANDOM_SEED);
        try {
            final Network network = env.network();
            network.addNodes(2);

            // Try to set weight to negative
            assertThatThrownBy(() -> network.nodeWeight(-100L))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Weight must be positive");
        } finally {
            env.destroy();
        }
    }

    /**
     * Test that Node.weight() accepts zero as valid weight.
     */
    @Test
    void testNodeWeightCanBeZero() {
        final TurtleTestEnvironment env = new TurtleTestEnvironment(RANDOM_SEED);
        try {
            final Network network = env.network();
            final List<Node> nodes = network.addNodes(1);
            final Node node = nodes.getFirst();

            // Set weight to zero
            node.weight(0L);

            // Verify weight is zero
            assertThat(node.weight()).isEqualTo(0L);
        } finally {
            env.destroy();
        }
    }

    /**
     * Test that Node.weight() throws when weight is negative.
     */
    @Test
    void testNodeWeightThrowsForNegative() {
        final TurtleTestEnvironment env = new TurtleTestEnvironment(RANDOM_SEED);
        try {
            final Network network = env.network();
            final List<Node> nodes = network.addNodes(1);
            final Node node = nodes.getFirst();

            // Try to set a negative weight
            assertThatThrownBy(() -> node.weight(-1L))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Weight must be non-negative");
        } finally {
            env.destroy();
        }
    }

    /**
     * Test that node weight can be updated multiple times before starting.
     */
    @Test
    void testNodeWeightCanBeUpdatedMultipleTimes() {
        final TurtleTestEnvironment env = new TurtleTestEnvironment(RANDOM_SEED);
        try {
            final Network network = env.network();
            final List<Node> nodes = network.addNodes(1);
            final Node node = nodes.getFirst();

            // Update weight multiple times
            node.weight(100L);
            assertThat(node.weight()).isEqualTo(100L);

            node.weight(200L);
            assertThat(node.weight()).isEqualTo(200L);

            node.weight(300L);
            assertThat(node.weight()).isEqualTo(300L);

            // Start and verify final weight
            network.start();
            assertThat(node.weight()).isEqualTo(300L);
        } finally {
            env.destroy();
        }
    }

    /**
     * Test that explicit node weights take precedence over weight generator.
     */
    @Test
    void testExplicitWeightsTakePrecedenceOverGenerator() {
        final TurtleTestEnvironment env = new TurtleTestEnvironment(RANDOM_SEED);
        try {
            final Network network = env.network();

            // Set a weight generator
            network.weightGenerator(WeightGenerators.BALANCED);

            // Add nodes and set explicit weights
            final List<Node> nodes = network.addNodes(3);
            nodes.get(0).weight(1000L);
            nodes.get(1).weight(2000L);
            nodes.get(2).weight(3000L);

            // Start network
            network.start();

            // Verify explicit weights are used, not the generator
            assertThat(nodes.get(0).weight()).isEqualTo(1000L);
            assertThat(nodes.get(1).weight()).isEqualTo(2000L);
            assertThat(nodes.get(2).weight()).isEqualTo(3000L);
            assertThat(network.totalWeight()).isEqualTo(6000L);
        } finally {
            env.destroy();
        }
    }

    /**
     * Test that weight generator is used when no explicit weights are set.
     */
    @Test
    void testWeightGeneratorUsedWhenNoExplicitWeights() {
        final TurtleTestEnvironment env = new TurtleTestEnvironment(RANDOM_SEED);
        try {
            final Network network = env.network();

            // Set a balanced weight generator
            network.weightGenerator(WeightGenerators.BALANCED);

            // Add nodes without setting explicit weights
            final List<Node> nodes = network.addNodes(4);

            // Start network - weights should be generated
            network.start();

            // Verify all nodes have non-zero weight
            for (Node node : nodes) {
                assertThat(node.weight()).isGreaterThan(0L);
            }

            // Verify balanced distribution (all weights should be equal)
            long firstWeight = nodes.getFirst().weight();
            for (Node node : nodes) {
                assertThat(node.weight()).isEqualTo(firstWeight);
            }
        } finally {
            env.destroy();
        }
    }

    /**
     * Test mixed scenario: some nodes with explicit weights, some without.
     */
    @Test
    void testMixedExplicitAndGeneratedWeights() {
        final TurtleTestEnvironment env = new TurtleTestEnvironment(RANDOM_SEED);
        try {
            final Network network = env.network();

            // Add nodes
            final List<Node> nodes = network.addNodes(4);

            // Set explicit weights for some nodes only
            nodes.get(0).weight(1000L);
            nodes.get(2).weight(3000L);

            // Start network
            network.start();

            // Since some nodes have explicit weights, all must have explicit weights
            // The nodes without explicit weights should have zero weight.
            assertThat(nodes.get(0).weight()).isEqualTo(1000L);
            assertThat(nodes.get(1).weight()).isEqualTo(0L);
            assertThat(nodes.get(2).weight()).isEqualTo(3000L);
            assertThat(nodes.get(3).weight()).isEqualTo(0L);

        } finally {
            env.destroy();
        }
    }
}
