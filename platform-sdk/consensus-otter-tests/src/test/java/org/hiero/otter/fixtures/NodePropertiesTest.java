// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.hedera.hapi.node.base.SemanticVersion;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.nio.file.Paths;
import java.util.List;
import java.util.stream.Stream;
import org.hiero.otter.fixtures.container.ContainerTestEnvironment;
import org.hiero.otter.fixtures.internal.AbstractNode;
import org.hiero.otter.fixtures.turtle.TurtleTestEnvironment;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Tests for the NodeProperties functionality that enables setting network properties before nodes are added and
 * propagating them to nodes as they are created.
 */
final class NodePropertiesTest {

    /**
     * Provides a stream of test environments for the parameterized tests.
     *
     * @return a stream of {@link TestEnvironment} instances
     */
    @NonNull
    static Stream<TestEnvironment> environments() {
        return Stream.of(new TurtleTestEnvironment(), new ContainerTestEnvironment());
    }

    // ============================================================================
    // 1. Pre-Node Creation Configuration Tests
    // ============================================================================

    /**
     * Test that calling network.nodeWeight() before nodes are added stores the weight in NodeProperties and applies it
     * when nodes are subsequently added.
     */
    @ParameterizedTest
    @MethodSource("environments")
    void testNodeWeightBeforeNodesAreAdded(@NonNull final TestEnvironment env) {
        try {
            final Network network = env.network();

            // Set network weight before adding nodes
            network.nodeWeight(500L);

            // Set network saved state directory before adding nodes
            final String savedStateDirName = "previous-version-state";
            network.savedStateDirectory(Paths.get(savedStateDirName));

            // Set network version before adding nodes
            final SemanticVersion testVersion = SemanticVersion.newBuilder()
                    .major(2)
                    .minor(0)
                    .patch(0)
                    .build();
            network.version(testVersion);

            // Set some configuration properties before adding nodes
            network.withConfigValue("testKey1", "testValue1");
            network.withConfigValue("testKey2", 42);
            network.withConfigValue("testKey3", true);

            // Add nodes - they should inherit the pre-configured weight
            final List<Node> nodes = network.addNodes(3);

            // Include an instrumented node as well
            nodes.add(network.addInstrumentedNode());

            // Verify all nodes have the configured weight
            for (final Node node : nodes) {
                assertThat(node.weight()).isEqualTo(500L);
                assertThat((((AbstractNode) node).savedStateDirectory().toString())).contains(
                        savedStateDirName);
                assertThat(node.version()).isEqualTo(testVersion);
                assertThat(node.configuration().current().getValue("testKey1")).isEqualTo("testValue1");
                assertThat(node.configuration().current().getValue("testKey2")).isEqualTo("42");
                assertThat(node.configuration().current().getValue("testKey3")).isEqualTo("true");
            }

            // Verify total network weight
            assertThat(network.totalWeight()).isEqualTo(1500L);
        } finally {
            env.destroy();
        }
    }

    // ============================================================================
    // 2. Propagation to Newly Added Nodes Tests
    // ============================================================================

    /**
     * Test that nodes added in stages all inherit the pre-configured properties.
     */
    @ParameterizedTest
    @MethodSource("environments")
    void testIncrementalNodeAdditionInheritsProperties(@NonNull final TestEnvironment env) {
        try {
            final Network network = env.network();

            // Set network properties
            network.nodeWeight(900L);

            // Add nodes in stages
            final List<Node> firstBatch = network.addNodes(2);
            firstBatch.add(network.addInstrumentedNode());
            final List<Node> secondBatch = network.addNodes(3);
            secondBatch.add(network.addInstrumentedNode());

            // Verify all nodes from both batches inherit the property
            for (final Node node : firstBatch) {
                assertThat(node.weight()).isEqualTo(900L);
            }
            for (final Node node : secondBatch) {
                assertThat(node.weight()).isEqualTo(900L);
            }

            // Verify total weight
            assertThat(network.totalWeight()).isEqualTo(4500L);

        } finally {
            env.destroy();
        }
    }

    // ============================================================================
    // 3. Per-Node Overrides Tests
    // ============================================================================

    /**
     * Test that node-level overrides of weight, version, and saved state work correctly after nodes are added.
     */
    @ParameterizedTest
    @MethodSource("environments")
    void testNodeLevelOverridesOfNetworkProperties(@NonNull final TestEnvironment env) {
        try {
            final Network network = env.network();

            // Set network properties
            final SemanticVersion networkVersion = SemanticVersion.newBuilder()
                    .major(1)
                    .minor(0)
                    .patch(0)
                    .build();
            network.nodeWeight(500L);
            network.version(networkVersion);
            network.withConfigValue("testKey1", "testValue1");
            network.withConfigValue("testKey2", 42);
            network.withConfigValue("testKey3", true);
            final String savedStateDirName = "previous-version-state";
            network.savedStateDirectory(Paths.get(savedStateDirName));

            // Add nodes
            final List<Node> nodes = network.addNodes(3);
            final Node node0 = nodes.get(0);
            final Node node1 = nodes.get(1);
            final Node node2 = nodes.get(2);

            // Include an instrumented node as well
            nodes.add(network.addInstrumentedNode());

            // Verify all nodes have network properties
            for (final Node node : nodes) {
                assertThat(node.weight()).isEqualTo(500L);
                assertThat(node.version()).isEqualTo(networkVersion);
                assertThat(node.configuration().current().getValue("testKey1")).isEqualTo("testValue1");
                assertThat(node.configuration().current().getValue("testKey2")).isEqualTo("42");
                assertThat(node.configuration().current().getValue("testKey3")).isEqualTo("true");
                assertThat((((AbstractNode) node).savedStateDirectory().toString())).contains(
                        savedStateDirName);
            }

            // Override node0's weight and node1's version
            node0.weight(1000L);
            final SemanticVersion nodeVersion = SemanticVersion.newBuilder()
                    .major(2)
                    .minor(0)
                    .patch(0)
                    .build();
            node1.version(nodeVersion);
            node2.configuration().set("testKey1", "differentTestValue1");
            node2.configuration().set("testKey2", 27);
            node2.configuration().set("testKey3", false);

            // Verify overrides
            assertThat(node0.weight()).isEqualTo(1000L);
            assertThat(node1.version()).isEqualTo(nodeVersion);
            assertThat(node2.configuration().current().getValue("testKey1")).isEqualTo("differentTestValue1");
            assertThat(node2.configuration().current().getValue("testKey2")).isEqualTo("27");
            assertThat(node2.configuration().current().getValue("testKey3")).isEqualTo("false");

            // Verify node2 still has some network properties
            assertThat(node2.weight()).isEqualTo(500L);
            assertThat(node2.version()).isEqualTo(networkVersion);

            // Verify other properties unchanged
            assertThat(node0.version()).isEqualTo(networkVersion);
            assertThat(node0.configuration().current().getValue("testKey1")).isEqualTo("testValue1");
            assertThat(node0.configuration().current().getValue("testKey2")).isEqualTo("42");
            assertThat(node0.configuration().current().getValue("testKey3")).isEqualTo("true");
            assertThat(node1.weight()).isEqualTo(500L);
            assertThat(node1.configuration().current().getValue("testKey1")).isEqualTo("testValue1");
            assertThat(node1.configuration().current().getValue("testKey2")).isEqualTo("42");
            assertThat(node1.configuration().current().getValue("testKey3")).isEqualTo("true");

        } finally {
            env.destroy();
        }
    }

    // ============================================================================
    // 4. Propagation to Existing Nodes Tests
    // ============================================================================

    /**
     * Test that network property changes after nodes exist propagate to all existing nodes.
     */
    @ParameterizedTest
    @MethodSource("environments")
    void testNetworkPropertyChangesPropagatesToExistingNodes(@NonNull final TestEnvironment env) {
        try {
            final Network network = env.network();

            // Add nodes without setting network properties first
            final List<Node> nodes = network.addNodes(3);

            // Include an instrumented node as well
            nodes.add(network.addInstrumentedNode());

            // Now set network properties on existing nodes
            network.nodeWeight(700L);

            // Verify all existing nodes are updated
            for (final Node node : nodes) {
                assertThat(node.weight()).isEqualTo(700L);
            }

            // Now change the network version
            network.nodeWeight(300L);

            // Verify all nodes are updated with the new weight
            for (final Node node : nodes) {
                assertThat(node.weight()).isEqualTo(300L);
            }

        } finally {
            env.destroy();
        }
    }

    // ============================================================================
    // 5. Edge Cases and Error Condition Tests
    // ============================================================================

    /**
     * Test that invalid weight values (zero and negative) throw appropriate errors at both network and node level.
     */
    @ParameterizedTest
    @MethodSource("environments")
    void testInvalidWeightValuesThrowErrors(@NonNull final TestEnvironment env) {
        try {
            final Network network = env.network();

            // Test network.nodeWeight() with zero
            assertThatThrownBy(() -> network.nodeWeight(0L))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Weight must be positive");

            // Test network.nodeWeight() with negative value
            assertThatThrownBy(() -> network.nodeWeight(-100L))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Weight must be positive");

            // Add nodes to test node-level weight validation
            final List<Node> nodes = network.addNodes(1);

            // Include an instrumented node as well
            nodes.add(network.addInstrumentedNode());

            // Test node.weight() with negative value
            for (final Node node : nodes) {
                assertThatThrownBy(() -> node.weight(-1L))
                        .isInstanceOf(IllegalArgumentException.class)
                        .hasMessageContaining("Weight must be non-negative");
            }

        } finally {
            env.destroy();
        }
    }

    /**
     * Test that properties set before start persist after starting the network.
     */
    @ParameterizedTest
    @MethodSource("environments")
    void testPropertyPersistenceAcrossNetworkLifecycle(@NonNull final TestEnvironment env) {
        try {
            final Network network = env.network();

            // Set properties before starting
            network.nodeWeight(550L);
            final SemanticVersion testVersion = SemanticVersion.newBuilder()
                    .major(1)
                    .minor(3)
                    .patch(0)
                    .build();
            network.version(testVersion);

            // Add nodes
            final List<Node> nodes = network.addNodes(2);
            final Node node0 = nodes.get(0);
            final Node node1 = nodes.get(1);

            // Include an instrumented node as well
            nodes.add(network.addInstrumentedNode());

            // Verify properties before start
            assertThat(node0.weight()).isEqualTo(550L);
            assertThat(node0.version()).isEqualTo(testVersion);
            assertThat(node1.weight()).isEqualTo(550L);
            assertThat(node1.version()).isEqualTo(testVersion);
            // Verify instrumented node also has properties
            assertThat(nodes.get(2).weight()).isEqualTo(550L);
            assertThat(nodes.get(2).version()).isEqualTo(testVersion);

            // Start the network
            network.start();

            // Verify properties persist after start
            assertThat(node0.weight()).isEqualTo(550L);
            assertThat(node0.version()).isEqualTo(testVersion);
            assertThat(node1.weight()).isEqualTo(550L);
            assertThat(node1.version()).isEqualTo(testVersion);
            // Verify instrumented node properties persist
            assertThat(nodes.get(2).weight()).isEqualTo(550L);
            assertThat(nodes.get(2).version()).isEqualTo(testVersion);

        } finally {
            env.destroy();
        }
    }

    // ============================================================================
    // 7. NodeConfiguration OverriddenProperties Tests
    // ============================================================================

    /**
     * Test that configuration properties set before nodes are added are applied to all nodes via the NodeProperties
     * overriddenProperties mechanism.
     */
    @ParameterizedTest
    @MethodSource("environments")
    void testConfigurationPropertiesAppliedToNewNodes(@NonNull final TestEnvironment env) {
        try {
            final Network network = env.network();

            // Set configuration properties before adding nodes
            network.withConfigValue("testKey1", "testValue1");
            network.withConfigValue("testKey2", 42);
            network.withConfigValue("testKey3", true);

            // Add multiple nodes
            final List<Node> nodes = network.addNodes(3);

            // Include an instrumented node as well
            nodes.add(network.addInstrumentedNode());

            // Verify all nodes received the configuration properties
            for (final Node node : nodes) {
                assertThat(node.configuration().current().getValue("testKey1")).isEqualTo("testValue1");
                assertThat(node.configuration().current().getValue("testKey2")).isEqualTo("42");
                assertThat(node.configuration().current().getValue("testKey3")).isEqualTo("true");
            }

        } finally {
            env.destroy();
        }
    }

    /**
     * Test that configuration properties set after nodes are added propagate to all existing nodes via the
     * overriddenProperties mechanism.
     */
    @ParameterizedTest
    @MethodSource("environments")
    void testConfigurationPropertiesPropagatesToExistingNodes(@NonNull final TestEnvironment env) {
        try {
            final Network network = env.network();

            // Add nodes without setting configuration properties first
            final List<Node> nodes = network.addNodes(3);

            // Include an instrumented node as well
            nodes.add(network.addInstrumentedNode());

            // Set configuration properties on existing nodes
            network.withConfigValue("propagatedKey1", "propagatedValue1");
            network.withConfigValue("propagatedKey2", 99);
            network.withConfigValue("propagatedKey3", false);

            // Verify all nodes received the configuration properties
            for (final Node node : nodes) {
                assertThat(node.configuration().current().getValue("propagatedKey1")).isEqualTo("propagatedValue1");
                assertThat(node.configuration().current().getValue("propagatedKey2")).isEqualTo("99");
                assertThat(node.configuration().current().getValue("propagatedKey3")).isEqualTo("false");
            }

        } finally {
            env.destroy();
        }
    }

    /**
     * Test that multiple configuration properties set before nodes are added are all applied correctly to each node via
     * overriddenProperties.
     */
    @ParameterizedTest
    @MethodSource("environments")
    void testMultipleConfigurationPropertiesAppliedToNodes(@NonNull final TestEnvironment env) {
        try {
            final Network network = env.network();

            // Set network weight and version
            network.nodeWeight(700L);
            final SemanticVersion testVersion = SemanticVersion.newBuilder()
                    .major(2)
                    .minor(1)
                    .patch(0)
                    .build();
            network.version(testVersion);

            // Set multiple configuration properties
            network.withConfigValue("configKey1", "configValue1");
            network.withConfigValue("configKey2", 123);
            network.withConfigValue("configKey3", true);

            // Add multiple nodes
            final List<Node> nodes = network.addNodes(3);

            // Include an instrumented node as well
            nodes.add(network.addInstrumentedNode());

            // Verify all properties are applied to each node
            for (final Node node : nodes) {
                assertThat(node.weight()).isEqualTo(700L);
                assertThat(node.version()).isEqualTo(testVersion);
                assertThat(node.configuration().current().getValue("configKey1")).isEqualTo("configValue1");
                assertThat(node.configuration().current().getValue("configKey2")).isEqualTo("123");
                assertThat(node.configuration().current().getValue("configKey3")).isEqualTo("true");
            }

        } finally {
            env.destroy();
        }
    }

    // ============================================================================
    // 8. Integration Scenario Tests
    // ============================================================================

    /**
     * Test that explicitly set network weight takes precedence over weight generator when network weight is set before
     * nodes are added.
     */
    @ParameterizedTest
    @MethodSource("environments")
    void testExplicitNetworkWeightTakesPrecedenceOverGenerator(@NonNull final TestEnvironment env) {
        try {
            final Network network = env.network();

            // Set weight generator
            network.weightGenerator(com.swirlds.common.test.fixtures.WeightGenerators.BALANCED);

            // Set explicit network weight before adding nodes
            network.nodeWeight(450L);

            // Add nodes
            final List<Node> nodes = network.addNodes(3);

            // Include an instrumented node as well
            nodes.add(network.addInstrumentedNode());

            // Verify explicit weight is used, not generated
            for (final Node node : nodes) {
                assertThat(node.weight()).isEqualTo(450L);
            }

            // Verify total weight (4 nodes * 450 each)
            assertThat(network.totalWeight()).isEqualTo(1800L);

        } finally {
            env.destroy();
        }
    }

    /**
     * Test mixed weight configurations where some nodes have explicit weights and then network weight is set, which
     * propagates to all existing and future nodes.
     */
    @ParameterizedTest
    @MethodSource("environments")
    void testMixedWeightConfigurationWithPreAndPostNodeAddition(@NonNull final TestEnvironment env) {
        try {
            final Network network = env.network();

            // Add first batch of nodes without network weight set
            final List<Node> firstBatch = network.addNodes(2);
            firstBatch.get(0).weight(100L);
            firstBatch.get(1).weight(200L);

            // Verify initial explicit weights
            assertThat(firstBatch.get(0).weight()).isEqualTo(100L);
            assertThat(firstBatch.get(1).weight()).isEqualTo(200L);

            // Now set network weight - this applies to all existing nodes
            network.nodeWeight(300L);

            // Verify network weight propagated to existing nodes, overriding their explicit weights
            assertThat(firstBatch.get(0).weight()).isEqualTo(300L);
            assertThat(firstBatch.get(1).weight()).isEqualTo(300L);

            // Add second batch - should also get network weight
            final List<Node> secondBatch = network.addNodes(2);

            // Verify second batch got the network weight
            assertThat(secondBatch.get(0).weight()).isEqualTo(300L);
            assertThat(secondBatch.get(1).weight()).isEqualTo(300L);

            // Include an instrumented node as well
            secondBatch.add(network.addInstrumentedNode());

            // Verify instrumented node got the network weight
            assertThat(secondBatch.get(2).weight()).isEqualTo(300L);

            // Verify total weight (5 nodes * 300 each)
            assertThat(network.totalWeight()).isEqualTo(1500L);

        } finally {
            env.destroy();
        }
    }

    /**
     * Test that both regular and instrumented nodes properly inherit network properties.
     */
    @ParameterizedTest
    @MethodSource("environments")
    void testBothRegularAndInstrumentedNodesInheritNetworkProperties(@NonNull final TestEnvironment env) {
        try {
            final Network network = env.network();

            // Set network properties
            network.nodeWeight(650L);
            final SemanticVersion testVersion = SemanticVersion.newBuilder()
                    .major(1)
                    .minor(4)
                    .patch(0)
                    .build();
            network.version(testVersion);

            // Add regular nodes
            final List<Node> regularNodes = network.addNodes(2);

            // Add instrumented nodes
            final InstrumentedNode instrumentedNode = network.addInstrumentedNode();

            // Verify regular nodes have network properties
            for (final Node node : regularNodes) {
                assertThat(node.weight()).isEqualTo(650L);
                assertThat(node.version()).isEqualTo(testVersion);
            }

            // Verify instrumented node has network properties
            assertThat(instrumentedNode.weight()).isEqualTo(650L);
            assertThat(instrumentedNode.version()).isEqualTo(testVersion);

        } finally {
            env.destroy();
        }
    }
}
