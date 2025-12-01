// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.hedera.hapi.node.base.SemanticVersion;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.nio.file.Paths;
import java.util.List;
import org.hiero.otter.fixtures.Network;
import org.hiero.otter.fixtures.Node;
import org.hiero.otter.fixtures.TestEnvironment;
import org.hiero.otter.fixtures.container.ContainerTestEnvironment;
import org.hiero.otter.fixtures.internal.AbstractNode;
import org.hiero.otter.fixtures.turtle.TurtleTestEnvironment;
import org.junit.jupiter.api.Test;

/**
 * Tests for the NetworkConfiguration functionality that enables setting network properties before nodes are added and
 * propagating them to nodes as they are created.
 */
final class NetworkConfigurationTest {

    /**
     * Test that node configuration before nodes are added stores the values in NetworkConfiguration and applies them when nodes
     * are subsequently added (Turtle environment).
     */
    @Test
    void testNetworkConfigurationBeforeNodesAreAddedTurtle() {
        testNetworkConfigurationBeforeNodesAreAdded(new TurtleTestEnvironment());
    }

    /**
     * Test that node configuration before nodes are added stores the values in NetworkConfiguration and applies them when nodes
     * are subsequently added (Container environment).
     */
    @Test
    void testNetworkConfigurationBeforeNodesAreAddedContainer() {
        testNetworkConfigurationBeforeNodesAreAdded(new ContainerTestEnvironment());
    }

    /**
     * Test that node configuration before nodes are added stores the values in NetworkConfiguration and applies them when nodes
     * are subsequently added.
     */
    private void testNetworkConfigurationBeforeNodesAreAdded(@NonNull final TestEnvironment env) {
        try {
            final Network network = env.network();

            // Set network weight before adding nodes
            network.nodeWeight(500L);

            // Set network saved state directory before adding nodes
            final String savedStateDirName = "previous-version-state";
            network.savedStateDirectory(Paths.get(savedStateDirName));

            // Set network version before adding nodes
            final SemanticVersion testVersion =
                    SemanticVersion.newBuilder().major(2).minor(0).patch(0).build();
            network.version(testVersion);

            // Set some configuration properties before adding nodes
            network.withConfigValue("testKey1", "testValue1");
            network.withConfigValue("testKey2", 42);
            network.withConfigValue("testKey3", true);

            // Add nodes - they should inherit the pre-configured weight
            network.addNodes(2);
            // Include an instrumented node as well
            network.addInstrumentedNode();

            final List<Node> nodes = network.nodes();

            // Verify all nodes have the configured weight
            for (final Node node : nodes) {
                assertThat(node.weight()).isEqualTo(500L);
                assertThat((((AbstractNode) node).savedStateDirectory().toString()))
                        .contains(savedStateDirName);
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

    /**
     * Test that nodes added in stages all inherit the pre-configured properties.
     */
    @Test
    void testIncrementalNodeAdditionInheritsProperties() {
        final TestEnvironment env = new TurtleTestEnvironment();
        try {
            final Network network = env.network();

            // Set network properties
            network.nodeWeight(900L);

            // Add nodes in stages
            network.addNodes(2);
            network.addInstrumentedNode();
            final List<Node> firstBatch = network.nodes();
            final List<Node> secondBatch = network.addNodes(3);

            // Verify all nodes from both batches inherit the property
            for (final Node node : firstBatch) {
                assertThat(node.weight()).isEqualTo(900L);
            }
            for (final Node node : secondBatch) {
                assertThat(node.weight()).isEqualTo(900L);
            }

            // Verify total weight
            assertThat(network.totalWeight()).isEqualTo(5400L);

        } finally {
            env.destroy();
        }
    }

    /**
     * Test that node-level overrides of weight, version, and saved state work correctly after nodes are added.
     */
    @Test
    void testNodeLevelOverridesOfNetworkProperties() {
        final TestEnvironment env = new TurtleTestEnvironment();
        try {
            final Network network = env.network();

            // Set network properties
            final SemanticVersion networkVersion =
                    SemanticVersion.newBuilder().major(1).minor(0).patch(0).build();
            network.nodeWeight(500L);
            network.version(networkVersion);
            network.withConfigValue("testKey1", "testValue1");
            network.withConfigValue("testKey2", 42);
            network.withConfigValue("testKey3", true);
            final String savedStateDirName = "previous-version-state";
            network.savedStateDirectory(Paths.get(savedStateDirName));

            // Add nodes
            network.addNodes(3);
            // Include an instrumented node as well
            network.addInstrumentedNode();

            final List<Node> nodes = network.nodes();
            final Node node0 = nodes.get(0);
            final Node node1 = nodes.get(1);
            final Node node2 = nodes.get(2);

            // Verify all nodes have network properties
            for (final Node node : nodes) {
                assertThat(node.weight()).isEqualTo(500L);
                assertThat(node.version()).isEqualTo(networkVersion);
                assertThat(node.configuration().current().getValue("testKey1")).isEqualTo("testValue1");
                assertThat(node.configuration().current().getValue("testKey2")).isEqualTo("42");
                assertThat(node.configuration().current().getValue("testKey3")).isEqualTo("true");
                assertThat((((AbstractNode) node).savedStateDirectory().toString()))
                        .contains(savedStateDirName);
            }

            // Override node0's weight and node1's version
            node0.weight(1000L);
            final SemanticVersion nodeVersion =
                    SemanticVersion.newBuilder().major(2).minor(0).patch(0).build();
            node1.version(nodeVersion);
            node2.configuration().withConfigValue("testKey1", "differentTestValue1");
            node2.configuration().withConfigValue("testKey2", 27);
            node2.configuration().withConfigValue("testKey3", false);

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

    /**
     * Test that network property changes after nodes exist propagate to all existing nodes.
     */
    @Test
    void testNetworkPropertyChangesPropagatesToExistingNodes() {
        final TestEnvironment env = new TurtleTestEnvironment();
        try {
            final Network network = env.network();

            // Add nodes without setting network properties first
            network.addNodes(3);
            // Include an instrumented node as well
            network.addInstrumentedNode();

            final List<Node> nodes = network.nodes();

            // Now set network properties on existing nodes
            network.nodeWeight(700L);

            // Verify all existing nodes are updated
            for (final Node node : nodes) {
                assertThat(node.weight()).isEqualTo(700L);
            }

            // Now change the network version
            network.nodeWeight(300L);
            network.version(SemanticVersion.newBuilder().major(2).minor(1).build());
            network.withConfigValue("testKey1", "testValue1");
            network.withConfigValue("testKey2", 42);
            network.withConfigValue("testKey3", true);

            // Verify all nodes are updated with the new weight
            for (final Node node : nodes) {
                assertThat(node.weight()).isEqualTo(300L);
                assertThat(node.version())
                        .isEqualTo(
                                SemanticVersion.newBuilder().major(2).minor(1).build());
                assertThat(node.configuration().current().getValue("testKey1")).isEqualTo("testValue1");
                assertThat(node.configuration().current().getValue("testKey2")).isEqualTo("42");
                assertThat(node.configuration().current().getValue("testKey3")).isEqualTo("true");
            }

        } finally {
            env.destroy();
        }
    }

    /**
     * Test that invalid weight values (zero and negative) throw appropriate errors at both network and node level.
     */
    @Test
    void testInvalidWeightValuesThrowErrors() {
        final TestEnvironment env = new TurtleTestEnvironment();
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
            network.addNodes(1);
            // Include an instrumented node as well
            network.addInstrumentedNode();

            final List<Node> nodes = network.nodes();

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

    // ============================================================================
    // 9. Lifecycle and State Tests
    // ============================================================================

    /**
     * Test that setting NetworkConfiguration fields on the network throws an exception when the network is in the RUNNING
     * lifecycle phase.
     */
    @Test
    void testNetworkConfigurationThrowExceptionWhenNetworkIsRunning() {
        final TestEnvironment env = new TurtleTestEnvironment();
        try {
            final Network network = env.network();

            // Add nodes
            network.addNodes(2);

            // Start the network
            network.start();

            // Verify that attempting to set nodeWeight throws an exception
            assertThatThrownBy(() -> network.nodeWeight(700L))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Cannot set weight");

            // Verify that attempting to set version throws an exception
            final SemanticVersion newVersion =
                    SemanticVersion.newBuilder().major(2).minor(0).patch(0).build();
            assertThatThrownBy(() -> network.version(newVersion))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Cannot set version");

            // Verify that attempting to set configuration values throws an exception
            assertThatThrownBy(() -> network.withConfigValue("testKey", "testValue"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("running");

        } finally {
            env.destroy();
        }
    }
}
