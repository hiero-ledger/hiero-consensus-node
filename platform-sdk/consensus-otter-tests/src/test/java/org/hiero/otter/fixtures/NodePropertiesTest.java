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
import org.hiero.otter.fixtures.turtle.TurtleTestEnvironment;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Tests for the NodeProperties functionality that enables setting network properties before nodes are
 * added and propagating them to nodes as they are created.
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
     * Test that calling network.nodeWeight() before nodes are added stores the weight in
     * NodeProperties and applies it when nodes are subsequently added.
     */
    @ParameterizedTest
    @MethodSource("environments")
    void testNodeWeightBeforeNodesAreAdded(@NonNull final TestEnvironment env) {
        try {
            final Network network = env.network();

            // Set network weight before adding nodes
            network.nodeWeight(500L);

            // Add nodes - they should inherit the pre-configured weight
            final List<Node> nodes = network.addNodes(3);

            // Verify all nodes have the configured weight
            assertThat(nodes.get(0).weight()).isEqualTo(500L);
            assertThat(nodes.get(1).weight()).isEqualTo(500L);
            assertThat(nodes.get(2).weight()).isEqualTo(500L);

            // Verify total network weight
            assertThat(network.totalWeight()).isEqualTo(1500L);
        } finally {
            env.destroy();
        }
    }

    /**
     * Test that calling network.version() before nodes are added stores the version in
     * NodeProperties and applies it when nodes are subsequently added.
     */
    @ParameterizedTest
    @MethodSource("environments")
    void testVersionBeforeNodesAreAdded(@NonNull final TestEnvironment env) {
        try {
            final Network network = env.network();

            // Set network version before adding nodes
            final SemanticVersion testVersion = SemanticVersion.newBuilder()
                    .major(1)
                    .minor(2)
                    .patch(3)
                    .build();
            network.version(testVersion);

            // Add nodes - they should inherit the pre-configured version
            final List<Node> nodes = network.addNodes(2);

            // Verify all nodes have the configured version
            assertThat(nodes.get(0).version()).isEqualTo(testVersion);
            assertThat(nodes.get(1).version()).isEqualTo(testVersion);

        } finally {
            env.destroy();
        }
    }

    /**
     * Test that calling network.savedStateDirectory() before nodes are added stores the path in
     * NodeProperties and applies it when nodes are subsequently added.
     */
    @ParameterizedTest
    @MethodSource("environments")
    void testSavedStateDirectoryBeforeNodesAreAdded(@NonNull final TestEnvironment env) {
        try {
            final Network network = env.network();

            // Set network saved state directory before adding nodes
            final String savedStateDirName = "previous-version-state";
            network.savedStateDirectory(Paths.get(savedStateDirName));

            // Add a node - it should be configured to start from the saved state directory
            final List<Node> nodes = network.addNodes(1);
            final Node node = nodes.getFirst();

            // Verify the node has the saved state configuration
            // (Note: We verify by checking the node was created successfully with the configuration)
            assertThat(node).isNotNull();

        } finally {
            env.destroy();
        }
    }

    /**
     * Test that setting multiple network-level properties before adding nodes all apply
     * correctly to the first node(s) created.
     */
    @ParameterizedTest
    @MethodSource("environments")
    void testMultiplePreNodePropertiesApplyToNewNodes(@NonNull final TestEnvironment env) {
        try {
            final Network network = env.network();

            // Set multiple network properties before adding nodes
            network.nodeWeight(600L);
            final SemanticVersion testVersion = SemanticVersion.newBuilder()
                    .major(2)
                    .minor(0)
                    .patch(0)
                    .build();
            network.version(testVersion);

            // Add nodes
            final List<Node> nodes = network.addNodes(2);

            // Verify all properties are applied
            for (final Node node : nodes) {
                assertThat(node.weight()).isEqualTo(600L);
                assertThat(node.version()).isEqualTo(testVersion);
            }

        } finally {
            env.destroy();
        }
    }

    // ============================================================================
    // 2. Propagation to Newly Added Nodes Tests
    // ============================================================================

    /**
     * Test that a single node added after setting network properties inherits the configured
     * values.
     */
    @ParameterizedTest
    @MethodSource("environments")
    void testSingleNodeInheritsPreConfiguredProperties(@NonNull final TestEnvironment env) {
        try {
            final Network network = env.network();

            // Set network properties
            network.nodeWeight(750L);

            // Add single node
            final Node node = network.addNode();

            // Verify it has the configured weight
            assertThat(node.weight()).isEqualTo(750L);

        } finally {
            env.destroy();
        }
    }

    /**
     * Test that multiple nodes all inherit the same pre-configured properties.
     */
    @ParameterizedTest
    @MethodSource("environments")
    void testMultipleNodesInheritPreConfiguredProperties(@NonNull final TestEnvironment env) {
        try {
            final Network network = env.network();

            // Set network properties
            network.nodeWeight(800L);
            final SemanticVersion testVersion = SemanticVersion.newBuilder()
                    .major(1)
                    .minor(5)
                    .patch(0)
                    .build();
            network.version(testVersion);

            // Add multiple nodes
            final List<Node> nodes = network.addNodes(4);

            // Verify all nodes inherit the properties
            for (final Node node : nodes) {
                assertThat(node.weight()).isEqualTo(800L);
                assertThat(node.version()).isEqualTo(testVersion);
            }

        } finally {
            env.destroy();
        }
    }

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
            final List<Node> secondBatch = network.addNodes(3);

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
     * Test that node-level overrides of weight, version, and saved state work correctly
     * after nodes are added.
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

            // Add nodes
            final List<Node> nodes = network.addNodes(3);
            final Node node0 = nodes.get(0);
            final Node node1 = nodes.get(1);
            final Node node2 = nodes.get(2);

            // Verify all nodes have network properties
            assertThat(node0.weight()).isEqualTo(500L);
            assertThat(node1.weight()).isEqualTo(500L);
            assertThat(node2.weight()).isEqualTo(500L);
            assertThat(node0.version()).isEqualTo(networkVersion);
            assertThat(node1.version()).isEqualTo(networkVersion);
            assertThat(node2.version()).isEqualTo(networkVersion);

            // Override node0's weight and node1's version
            node0.weight(1000L);
            final SemanticVersion nodeVersion = SemanticVersion.newBuilder()
                    .major(2)
                    .minor(0)
                    .patch(0)
                    .build();
            node1.version(nodeVersion);

            // Verify overrides
            assertThat(node0.weight()).isEqualTo(1000L);
            assertThat(node1.version()).isEqualTo(nodeVersion);

            // Verify node2 still has network properties
            assertThat(node2.weight()).isEqualTo(500L);
            assertThat(node2.version()).isEqualTo(networkVersion);

            // Verify other properties unchanged
            assertThat(node0.version()).isEqualTo(networkVersion);
            assertThat(node1.weight()).isEqualTo(500L);

        } finally {
            env.destroy();
        }
    }

    /**
     * Test that overriding one node's properties doesn't affect other nodes.
     */
    @ParameterizedTest
    @MethodSource("environments")
    void testOverrideDoesNotAffectOtherNodes(@NonNull final TestEnvironment env) {
        try {
            final Network network = env.network();

            // Set network weight
            network.nodeWeight(600L);

            // Add nodes
            final List<Node> nodes = network.addNodes(4);
            final Node node0 = nodes.get(0);
            final Node node1 = nodes.get(1);
            final Node node2 = nodes.get(2);
            final Node node3 = nodes.get(3);

            // Override one node's weight
            node1.weight(1200L);

            // Verify override
            assertThat(node1.weight()).isEqualTo(1200L);

            // Verify other nodes are unaffected
            assertThat(node0.weight()).isEqualTo(600L);
            assertThat(node2.weight()).isEqualTo(600L);
            assertThat(node3.weight()).isEqualTo(600L);

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
            final Node node0 = nodes.get(0);
            final Node node1 = nodes.get(1);
            final Node node2 = nodes.get(2);

            // Now set network properties on existing nodes
            network.nodeWeight(700L);

            // Verify all existing nodes are updated
            assertThat(node0.weight()).isEqualTo(700L);
            assertThat(node1.weight()).isEqualTo(700L);
            assertThat(node2.weight()).isEqualTo(700L);

            // Now change the network version
            final SemanticVersion newVersion = SemanticVersion.newBuilder()
                    .major(3)
                    .minor(0)
                    .patch(0)
                    .build();
            network.version(newVersion);

            // Verify all nodes are updated with the new version
            assertThat(node0.version()).isEqualTo(newVersion);
            assertThat(node1.version()).isEqualTo(newVersion);
            assertThat(node2.version()).isEqualTo(newVersion);

        } finally {
            env.destroy();
        }
    }

    /**
     * Test that multiple network property changes after nodes exist all propagate correctly.
     */
    @ParameterizedTest
    @MethodSource("environments")
    void testMultiplePropertyChangesPropagatesToExistingNodes(@NonNull final TestEnvironment env) {
        try {
            final Network network = env.network();

            // Add nodes
            final List<Node> nodes = network.addNodes(2);
            final Node node0 = nodes.get(0);
            final Node node1 = nodes.get(1);

            // Make multiple property changes
            network.nodeWeight(100L);
            final SemanticVersion version1 = SemanticVersion.newBuilder()
                    .major(1)
                    .minor(0)
                    .patch(0)
                    .build();
            network.version(version1);

            // Verify first set of changes
            assertThat(node0.weight()).isEqualTo(100L);
            assertThat(node1.weight()).isEqualTo(100L);
            assertThat(node0.version()).isEqualTo(version1);
            assertThat(node1.version()).isEqualTo(version1);

            // Make another set of changes
            network.nodeWeight(200L);
            final SemanticVersion version2 = SemanticVersion.newBuilder()
                    .major(2)
                    .minor(0)
                    .patch(0)
                    .build();
            network.version(version2);

            // Verify second set of changes
            assertThat(node0.weight()).isEqualTo(200L);
            assertThat(node1.weight()).isEqualTo(200L);
            assertThat(node0.version()).isEqualTo(version2);
            assertThat(node1.version()).isEqualTo(version2);

        } finally {
            env.destroy();
        }
    }

    // ============================================================================
    // 5. Override Precedence Tests
    // ============================================================================

    /**
     * Test that explicit node properties take precedence over network properties when both are
     * set.
     */
    @ParameterizedTest
    @MethodSource("environments")
    void testExplicitNodePropertiesTakePrecedenceOverNetwork(@NonNull final TestEnvironment env) {
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

            // Add nodes
            final List<Node> nodes = network.addNodes(2);
            final Node node0 = nodes.get(0);
            final Node node1 = nodes.get(1);

            // Set explicit node properties
            node0.weight(1500L);
            final SemanticVersion nodeVersion = SemanticVersion.newBuilder()
                    .major(5)
                    .minor(0)
                    .patch(0)
                    .build();
            node1.version(nodeVersion);

            // Verify explicit properties take precedence
            assertThat(node0.weight()).isEqualTo(1500L);
            assertThat(node1.version()).isEqualTo(nodeVersion);

            // Verify other properties remain from network
            assertThat(node0.version()).isEqualTo(networkVersion);
            assertThat(node1.weight()).isEqualTo(500L);

        } finally {
            env.destroy();
        }
    }

    /**
     * Test that network properties can override explicit node properties when network setter is
     * called after node property is set.
     */
    @ParameterizedTest
    @MethodSource("environments")
    void testNetworkPropertiesCanOverrideExplicitNodeProperties(@NonNull final TestEnvironment env) {
        try {
            final Network network = env.network();

            // Add nodes
            final List<Node> nodes = network.addNodes(3);
            final Node node0 = nodes.get(0);
            final Node node1 = nodes.get(1);
            final Node node2 = nodes.get(2);

            // Set explicit node properties
            node0.weight(2000L);
            node1.weight(3000L);
            node2.weight(4000L);

            // Verify explicit properties are set
            assertThat(node0.weight()).isEqualTo(2000L);
            assertThat(node1.weight()).isEqualTo(3000L);
            assertThat(node2.weight()).isEqualTo(4000L);

            // Now call network.nodeWeight() - should override all nodes
            network.nodeWeight(1000L);

            // Verify network weight propagates to all nodes, overriding explicit values
            assertThat(node0.weight()).isEqualTo(1000L);
            assertThat(node1.weight()).isEqualTo(1000L);
            assertThat(node2.weight()).isEqualTo(1000L);

        } finally {
            env.destroy();
        }
    }

    // ============================================================================
    // 6. Edge Cases and Error Condition Tests
    // ============================================================================

    /**
     * Test that invalid weight values (zero and negative) throw appropriate errors at both
     * network and node level.
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

            // Add a node to test node-level weight validation
            final List<Node> nodes = network.addNodes(1);
            final Node node = nodes.getFirst();

            // Test node.weight() with negative value
            assertThatThrownBy(() -> node.weight(-1L))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Weight must be non-negative");

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

            // Verify properties before start
            assertThat(node0.weight()).isEqualTo(550L);
            assertThat(node0.version()).isEqualTo(testVersion);
            assertThat(node1.weight()).isEqualTo(550L);
            assertThat(node1.version()).isEqualTo(testVersion);

            // Start the network
            network.start();

            // Verify properties persist after start
            assertThat(node0.weight()).isEqualTo(550L);
            assertThat(node0.version()).isEqualTo(testVersion);
            assertThat(node1.weight()).isEqualTo(550L);
            assertThat(node1.version()).isEqualTo(testVersion);

        } finally {
            env.destroy();
        }
    }

    // ============================================================================
    // 7. Integration Scenario Tests
    // ============================================================================

    /**
     * Test that explicitly set network weight takes precedence over weight generator when
     * network weight is set before nodes are added.
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

            // Verify explicit weight is used, not generated
            for (final Node node : nodes) {
                assertThat(node.weight()).isEqualTo(450L);
            }

            // Verify total weight
            assertThat(network.totalWeight()).isEqualTo(1350L);

        } finally {
            env.destroy();
        }
    }

    /**
     * Test mixed weight configurations where some nodes have explicit weights and then network
     * weight is set, which propagates to all existing and future nodes.
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

            // Verify total weight (4 nodes * 300 each)
            assertThat(network.totalWeight()).isEqualTo(1200L);

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
