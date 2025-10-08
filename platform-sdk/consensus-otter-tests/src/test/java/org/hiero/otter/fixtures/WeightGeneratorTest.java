// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hiero.otter.fixtures.Constants.RANDOM_SEED;

import com.swirlds.common.test.fixtures.WeightGenerator;
import com.swirlds.common.test.fixtures.WeightGenerators;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.List;
import java.util.stream.Stream;
import org.hiero.otter.fixtures.turtle.TurtleTestEnvironment;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Tests for weight generator functionality in the Network interface.
 */
class WeightGeneratorTest {

    /**
     * Provides a stream of test environments for the parameterized tests. Only uses the Turtle environment because the
     * functionality is in abstract classes, and it is much faster than the container environment.
     *
     * @return a stream of {@link TestEnvironment} instances
     */
    public static Stream<TestEnvironment> environments() {
        return Stream.of(new TurtleTestEnvironment(RANDOM_SEED));
    }

    /**
     * Test that weight generator can be set before network starts.
     *
     * @param env the test environment for this test
     */
    @ParameterizedTest
    @MethodSource("environments")
    void testWeightGeneratorCanBeSetBeforeNetworkStarts(@NonNull final TestEnvironment env) {
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
     * @param env the test environment for this test
     */
    @ParameterizedTest
    @MethodSource("environments")
    void testWeightGeneratorCannotBeSetWhenNetworkIsRunning(@NonNull final TestEnvironment env) {
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
     * Test balanced weight generator produces equal weights.
     *
     * @param env the test environment for this test
     */
    @ParameterizedTest
    @MethodSource("environments")
    void testBalancedWeightGeneratorProducesEqualWeights(@NonNull final TestEnvironment env) {
        try {
            final Network network = env.network();

            // Use balanced generator
            network.weightGenerator(WeightGenerators.BALANCED);

            // Add nodes
            final List<Node> nodes = network.addNodes(5);

            // Start network
            network.start();

            // Verify all weights are equal
            long expectedWeight = nodes.getFirst().weight();
            assertThat(expectedWeight).isGreaterThan(0L);

            for (Node node : nodes) {
                assertThat(node.weight()).isEqualTo(expectedWeight);
            }
        } finally {
            env.destroy();
        }
    }

    /**
     * Test Gaussian weight generator produces varied weights.
     *
     * @param env the test environment for this test
     */
    @ParameterizedTest
    @MethodSource("environments")
    void testGaussianWeightGeneratorProducesVariedWeights(@NonNull final TestEnvironment env) {
        try {
            final Network network = env.network();

            // Use Gaussian generator (default)
            network.weightGenerator(WeightGenerators.GAUSSIAN);

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
     * Test default weight generator is Gaussian.
     *
     * @param env the test environment for this test
     */
    @ParameterizedTest
    @MethodSource("environments")
    void testDefaultWeightGeneratorIsGaussian(@NonNull final TestEnvironment env) {
        try {
            final Network network = env.network();

            // Don't set a weight generator - use default

            // Add multiple nodes
            final List<Node> nodes = network.addNodes(10);

            // Start network
            network.start();

            // Verify all weights are non-negative
            for (Node node : nodes) {
                assertThat(node.weight()).isGreaterThanOrEqualTo(0L);
            }

            // Verify weights show variation (characteristic of Gaussian)
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
     * @param env the test environment for this test
     */
    @ParameterizedTest
    @MethodSource("environments")
    void testWeightGeneratorCanBeChangedBeforeStart(@NonNull final TestEnvironment env) {
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
     * @param env the test environment for this test
     */
    @ParameterizedTest
    @MethodSource("environments")
    void testCustomWeightGenerator(@NonNull final TestEnvironment env) {
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
     * Test that the weight generator is only used when no explicit weights are set.
     *
     * @param env the test environment for this test
     */
    @ParameterizedTest
    @MethodSource("environments")
    void testWeightGeneratorNotUsedWhenExplicitWeightsSet(@NonNull final TestEnvironment env) {
        try {
            final Network network = env.network();

            // Set balanced generator
            network.weightGenerator(WeightGenerators.BALANCED);

            // Add nodes with explicit weights
            final List<Node> nodes = network.addNodes(3);
            nodes.getFirst().weight(100L);
            nodes.get(1).weight(500L);
            nodes.get(2).weight(900L);

            // Start network
            network.start();

            // Verify explicit weights are preserved, not overridden by generator
            assertThat(nodes.getFirst().weight()).isEqualTo(100L);
            assertThat(nodes.get(1).weight()).isEqualTo(500L);
            assertThat(nodes.get(2).weight()).isEqualTo(900L);
        } finally {
            env.destroy();
        }
    }

    /**
     * Test weight generator with a single node.
     *
     * @param env the test environment for this test
     */
    @ParameterizedTest
    @MethodSource("environments")
    void testWeightGeneratorWithSingleNode(@NonNull final TestEnvironment env) {
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
