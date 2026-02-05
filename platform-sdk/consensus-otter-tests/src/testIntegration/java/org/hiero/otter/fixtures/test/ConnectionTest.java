// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hiero.consensus.model.status.PlatformStatus.ACTIVE;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Duration;
import java.util.List;
import org.hiero.consensus.test.fixtures.WeightGenerators;
import org.hiero.otter.fixtures.Network;
import org.hiero.otter.fixtures.Node;
import org.hiero.otter.fixtures.TestEnvironment;
import org.hiero.otter.fixtures.TimeManager;
import org.hiero.otter.fixtures.container.ContainerTestEnvironment;
import org.hiero.otter.fixtures.network.BidirectionalConnection;
import org.hiero.otter.fixtures.network.UnidirectionalConnection;
import org.hiero.otter.fixtures.turtle.TurtleTestEnvironment;
import org.junit.jupiter.api.Test;

/**
 * Integration tests for connection operations (connect/disconnect) in the Network interface.
 *
 * <p>These tests verify that the {@link UnidirectionalConnection#connect()} and
 * {@link UnidirectionalConnection#disconnect()} methods work correctly in actual running networks,
 * affecting node communication and consensus behavior.
 */
class ConnectionTest {

    /**
     * Test unidirectional connection disconnect and reconnect (Turtle environment).
     *
     * <p>This test verifies that disconnecting all connections to/from a node causes it to
     * enter CHECKING status, and reconnecting restores it to ACTIVE status.
     */
    @Test
    void testUnidirectionalConnectionLifecycleTurtle() {
        testUnidirectionalConnectionLifecycle(new TurtleTestEnvironment());
    }

    /**
     * Test unidirectional connection disconnect and reconnect (Container environment).
     *
     * <p>This test verifies that disconnecting all connections to/from a node causes it to
     * enter CHECKING status, and reconnecting restores it to ACTIVE status.
     */
    @Test
    void testUnidirectionalConnectionLifecycleContainer() {
        testUnidirectionalConnectionLifecycle(new ContainerTestEnvironment());
    }

    /**
     * Test unidirectional connection disconnect and reconnect in all environments.
     *
     * <p>This test verifies that:
     * <ul>
     *   <li>Disconnecting all connections to/from a node causes it to enter CHECKING status</li>
     *   <li>Other nodes remain ACTIVE when they can still communicate with the majority</li>
     *   <li>Reconnecting the node causes it to return to ACTIVE status</li>
     *   <li>Multiple disconnect/reconnect cycles work correctly</li>
     * </ul>
     *
     * @param env the test environment for this test
     */
    private void testUnidirectionalConnectionLifecycle(@NonNull final TestEnvironment env) {
        try {
            final Network network = env.network();
            final TimeManager timeManager = env.timeManager();

            // Setup network with 4 nodes
            network.weightGenerator(WeightGenerators.BALANCED);
            final List<Node> nodes = network.addNodes(4);
            final Node node0 = nodes.get(0);
            final Node node1 = nodes.get(1);
            final Node node2 = nodes.get(2);
            final Node node3 = nodes.get(3);

            network.start();

            // Wait for nodes to stabilize
            timeManager.waitFor(Duration.ofSeconds(5));
            assertThat(network.allNodesAreActive()).isTrue();

            // Perform multiple disconnect/reconnect cycles
            for (int cycle = 0; cycle < 3; cycle++) {

                // Verify initial connection state - all connections should be connected
                assertThat(network.unidirectionalConnection(node0, node1).isConnected())
                        .isTrue();
                assertThat(network.unidirectionalConnection(node0, node2).isConnected())
                        .isTrue();
                assertThat(network.unidirectionalConnection(node0, node3).isConnected())
                        .isTrue();
                assertThat(network.unidirectionalConnection(node1, node0).isConnected())
                        .isTrue();
                assertThat(network.unidirectionalConnection(node2, node0).isConnected())
                        .isTrue();
                assertThat(network.unidirectionalConnection(node3, node0).isConnected())
                        .isTrue();

                // Disconnect all connections FROM node0 (outgoing)
                final UnidirectionalConnection conn0to1 = network.unidirectionalConnection(node0, node1);
                final UnidirectionalConnection conn0to2 = network.unidirectionalConnection(node0, node2);
                final UnidirectionalConnection conn0to3 = network.unidirectionalConnection(node0, node3);
                conn0to1.disconnect();
                conn0to2.disconnect();
                conn0to3.disconnect();

                // Disconnect all connections TO node0 (incoming)
                final UnidirectionalConnection conn1to0 = network.unidirectionalConnection(node1, node0);
                final UnidirectionalConnection conn2to0 = network.unidirectionalConnection(node2, node0);
                final UnidirectionalConnection conn3to0 = network.unidirectionalConnection(node3, node0);
                conn1to0.disconnect();
                conn2to0.disconnect();
                conn3to0.disconnect();

                // Verify all connections to/from node0 are disconnected
                assertThat(conn0to1.isConnected()).isFalse();
                assertThat(conn0to2.isConnected()).isFalse();
                assertThat(conn0to3.isConnected()).isFalse();
                assertThat(conn1to0.isConnected()).isFalse();
                assertThat(conn2to0.isConnected()).isFalse();
                assertThat(conn3to0.isConnected()).isFalse();

                // Wait for node0 to detect the disconnection and enter CHECKING status
                timeManager.waitForCondition(
                        node0::isChecking,
                        Duration.ofSeconds(120L),
                        "Node0 did not enter CHECKING status after disconnecting (cycle " + cycle + ")");

                // Verify other nodes remain ACTIVE (they can still communicate with each other)
                timeManager.waitFor(Duration.ofSeconds(5));
                assertThat(node1.platformStatus())
                        .as("Node1 should remain ACTIVE during cycle " + cycle)
                        .isEqualTo(ACTIVE);
                assertThat(node2.platformStatus())
                        .as("Node2 should remain ACTIVE during cycle " + cycle)
                        .isEqualTo(ACTIVE);
                assertThat(node3.platformStatus())
                        .as("Node3 should remain ACTIVE during cycle " + cycle)
                        .isEqualTo(ACTIVE);

                // Reconnect all connections
                conn0to1.connect();
                conn0to2.connect();
                conn0to3.connect();
                conn1to0.connect();
                conn2to0.connect();
                conn3to0.connect();

                // Verify all connections are reconnected
                assertThat(conn0to1.isConnected()).isTrue();
                assertThat(conn0to2.isConnected()).isTrue();
                assertThat(conn0to3.isConnected()).isTrue();
                assertThat(conn1to0.isConnected()).isTrue();
                assertThat(conn2to0.isConnected()).isTrue();
                assertThat(conn3to0.isConnected()).isTrue();

                // Wait for all nodes to return to ACTIVE status
                timeManager.waitForCondition(
                        network::allNodesAreActive,
                        Duration.ofSeconds(120L),
                        "Not all nodes returned to ACTIVE status after reconnecting (cycle " + cycle + ")");
            }
        } finally {
            env.destroy();
        }
    }

    /**
     * Test bidirectional connection disconnect and reconnect (Turtle environment).
     *
     * <p>This test verifies that bidirectional connection operations correctly affect
     * both directions simultaneously and cause nodes to enter/exit CHECKING status.
     */
    @Test
    void testBidirectionalConnectionLifecycleTurtle() {
        testBidirectionalConnectionLifecycle(new TurtleTestEnvironment());
    }

    /**
     * Test bidirectional connection disconnect and reconnect (Container environment).
     *
     * <p>This test verifies that bidirectional connection operations correctly affect
     * both directions simultaneously and cause nodes to enter/exit CHECKING status.
     */
    @Test
    void testBidirectionalConnectionLifecycleContainer() {
        testBidirectionalConnectionLifecycle(new ContainerTestEnvironment());
    }

    /**
     * Test bidirectional connection disconnect and reconnect in all environments.
     *
     * <p>This test verifies that:
     * <ul>
     *   <li>Bidirectional disconnect() affects both directions simultaneously</li>
     *   <li>Disconnecting all connections causes a node to enter CHECKING status</li>
     *   <li>Bidirectional connect() restores both directions</li>
     *   <li>Multiple cycles work correctly with bidirectional connections</li>
     * </ul>
     *
     * @param env the test environment for this test
     */
    private void testBidirectionalConnectionLifecycle(@NonNull final TestEnvironment env) {
        try {
            final Network network = env.network();
            final TimeManager timeManager = env.timeManager();

            // Setup network with 4 nodes
            network.weightGenerator(WeightGenerators.BALANCED);
            final List<Node> nodes = network.addNodes(4);
            final Node node0 = nodes.get(0);
            final Node node1 = nodes.get(1);
            final Node node2 = nodes.get(2);
            final Node node3 = nodes.get(3);

            network.start();

            // Wait for nodes to stabilize
            timeManager.waitFor(Duration.ofSeconds(5));
            assertThat(network.allNodesAreActive()).isTrue();

            // Perform multiple disconnect/reconnect cycles using bidirectional connections
            for (int cycle = 0; cycle < 3; cycle++) {

                // Get bidirectional connections
                final BidirectionalConnection conn0and1 = network.bidirectionalConnection(node0, node1);
                final BidirectionalConnection conn0and2 = network.bidirectionalConnection(node0, node2);
                final BidirectionalConnection conn0and3 = network.bidirectionalConnection(node0, node3);

                // Verify initial state - all connections should be connected
                assertThat(conn0and1.isConnected()).isTrue();
                assertThat(conn0and2.isConnected()).isTrue();
                assertThat(conn0and3.isConnected()).isTrue();

                // Disconnect all bidirectional connections involving node0
                conn0and1.disconnect();
                conn0and2.disconnect();
                conn0and3.disconnect();

                // Verify bidirectional disconnection affects both directions
                assertThat(conn0and1.isConnected()).isFalse();
                assertThat(conn0and2.isConnected()).isFalse();
                assertThat(conn0and3.isConnected()).isFalse();
                assertThat(network.unidirectionalConnection(node0, node1).isConnected())
                        .isFalse();
                assertThat(network.unidirectionalConnection(node1, node0).isConnected())
                        .isFalse();
                assertThat(network.unidirectionalConnection(node0, node2).isConnected())
                        .isFalse();
                assertThat(network.unidirectionalConnection(node2, node0).isConnected())
                        .isFalse();
                assertThat(network.unidirectionalConnection(node0, node3).isConnected())
                        .isFalse();
                assertThat(network.unidirectionalConnection(node3, node0).isConnected())
                        .isFalse();

                // Wait for node0 to enter CHECKING status
                timeManager.waitForCondition(
                        node0::isChecking,
                        Duration.ofSeconds(120L),
                        "Node0 did not enter CHECKING status after disconnecting all bidirectional connections (cycle "
                                + cycle + ")");

                // Verify other nodes remain ACTIVE
                timeManager.waitFor(Duration.ofSeconds(5));
                assertThat(node1.platformStatus())
                        .as("Node1 should remain ACTIVE during cycle " + cycle)
                        .isEqualTo(ACTIVE);
                assertThat(node2.platformStatus())
                        .as("Node2 should remain ACTIVE during cycle " + cycle)
                        .isEqualTo(ACTIVE);
                assertThat(node3.platformStatus())
                        .as("Node3 should remain ACTIVE during cycle " + cycle)
                        .isEqualTo(ACTIVE);

                // Reconnect all bidirectional connections
                conn0and1.connect();
                conn0and2.connect();
                conn0and3.connect();

                // Verify bidirectional reconnection affects both directions
                assertThat(conn0and1.isConnected()).isTrue();
                assertThat(conn0and2.isConnected()).isTrue();
                assertThat(conn0and3.isConnected()).isTrue();
                assertThat(network.unidirectionalConnection(node0, node1).isConnected())
                        .isTrue();
                assertThat(network.unidirectionalConnection(node1, node0).isConnected())
                        .isTrue();
                assertThat(network.unidirectionalConnection(node0, node2).isConnected())
                        .isTrue();
                assertThat(network.unidirectionalConnection(node2, node0).isConnected())
                        .isTrue();
                assertThat(network.unidirectionalConnection(node0, node3).isConnected())
                        .isTrue();
                assertThat(network.unidirectionalConnection(node3, node0).isConnected())
                        .isTrue();

                // Wait for all nodes to return to ACTIVE status
                timeManager.waitForCondition(
                        network::allNodesAreActive,
                        Duration.ofSeconds(120L),
                        "Not all nodes returned to ACTIVE status after reconnecting bidirectional connections (cycle "
                                + cycle + ")");
            }
        } finally {
            env.destroy();
        }
    }

    /**
     * Test restoreConnectivity with manually disconnected connections (Turtle environment).
     *
     * <p>This test verifies that {@link Network#restoreConnectivity()} restores
     * connections that were manually disconnected.
     */
    @Test
    void testRestoreConnectivityWithDisconnectedConnectionsTurtle() {
        testRestoreConnectivityWithDisconnectedConnections(new TurtleTestEnvironment());
    }

    /**
     * Test restoreConnectivity with manually disconnected connections (Container environment).
     *
     * <p>This test verifies that {@link Network#restoreConnectivity()} restores
     * connections that were manually disconnected.
     */
    @Test
    void testRestoreConnectivityWithDisconnectedConnectionsContainer() {
        testRestoreConnectivityWithDisconnectedConnections(new ContainerTestEnvironment());
    }

    /**
     * Test restoreConnectivity with manually disconnected connections in all environments.
     *
     * <p>This test verifies that:
     * <ul>
     *   <li>Manual connection disconnections cause nodes to enter CHECKING status</li>
     *   <li>{@link Network#restoreConnectivity()} restores all manually disconnected connections</li>
     *   <li>Nodes return to ACTIVE status after restoreConnectivity()</li>
     * </ul>
     *
     * @param env the test environment for this test
     */
    private void testRestoreConnectivityWithDisconnectedConnections(@NonNull final TestEnvironment env) {
        try {
            final Network network = env.network();
            final TimeManager timeManager = env.timeManager();

            // Setup network with 4 nodes
            network.weightGenerator(WeightGenerators.BALANCED);
            final List<Node> nodes = network.addNodes(4);
            final Node node0 = nodes.get(0);
            final Node node1 = nodes.get(1);
            final Node node2 = nodes.get(2);
            final Node node3 = nodes.get(3);

            network.start();

            // Wait for nodes to stabilize
            timeManager.waitFor(Duration.ofSeconds(5));
            assertThat(network.allNodesAreActive()).isTrue();

            // Disconnect all connections to/from node0 using both unidirectional and bidirectional APIs
            network.unidirectionalConnection(node0, node1).disconnect();
            network.unidirectionalConnection(node1, node0).disconnect();
            network.bidirectionalConnection(node0, node2).disconnect();
            network.unidirectionalConnection(node0, node3).disconnect();
            network.unidirectionalConnection(node3, node0).disconnect();

            // Verify connections are disconnected
            assertThat(network.unidirectionalConnection(node0, node1).isConnected())
                    .isFalse();
            assertThat(network.unidirectionalConnection(node1, node0).isConnected())
                    .isFalse();
            assertThat(network.unidirectionalConnection(node0, node2).isConnected())
                    .isFalse();
            assertThat(network.unidirectionalConnection(node2, node0).isConnected())
                    .isFalse();
            assertThat(network.unidirectionalConnection(node0, node3).isConnected())
                    .isFalse();
            assertThat(network.unidirectionalConnection(node3, node0).isConnected())
                    .isFalse();

            // Wait for node0 to enter CHECKING status
            timeManager.waitForCondition(
                    node0::isChecking,
                    Duration.ofSeconds(120L),
                    "Node0 did not enter CHECKING status after disconnecting all connections");

            // Verify other nodes remain ACTIVE
            timeManager.waitFor(Duration.ofSeconds(5));
            assertThat(node1.platformStatus()).isEqualTo(ACTIVE);
            assertThat(node2.platformStatus()).isEqualTo(ACTIVE);
            assertThat(node3.platformStatus()).isEqualTo(ACTIVE);

            // Use restoreConnectivity to reconnect everything
            network.restoreConnectivity();

            // Verify all connections are restored
            assertThat(network.unidirectionalConnection(node0, node1).isConnected())
                    .isTrue();
            assertThat(network.unidirectionalConnection(node1, node0).isConnected())
                    .isTrue();
            assertThat(network.unidirectionalConnection(node0, node2).isConnected())
                    .isTrue();
            assertThat(network.unidirectionalConnection(node2, node0).isConnected())
                    .isTrue();
            assertThat(network.unidirectionalConnection(node0, node3).isConnected())
                    .isTrue();
            assertThat(network.unidirectionalConnection(node3, node0).isConnected())
                    .isTrue();

            // Wait for all nodes to return to ACTIVE status
            timeManager.waitForCondition(
                    network::allNodesAreActive,
                    Duration.ofSeconds(120L),
                    "Not all nodes returned to ACTIVE status after restoreConnectivity()");
        } finally {
            env.destroy();
        }
    }
}
