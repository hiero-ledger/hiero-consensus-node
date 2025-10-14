// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hiero.consensus.model.status.PlatformStatus.ACTIVE;
import static org.hiero.otter.fixtures.Constants.RANDOM_SEED;

import com.swirlds.common.test.fixtures.WeightGenerators;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Duration;
import java.util.List;
import java.util.stream.Stream;
import org.hiero.consensus.model.status.PlatformStatus;
import org.hiero.otter.fixtures.container.ContainerTestEnvironment;
import org.hiero.otter.fixtures.turtle.TurtleTestEnvironment;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Comprehensive tests for Node lifecycle operations (start and kill).
 *
 * <p>This test class validates the behavior of individual nodes being started and killed,
 * verifying platform status transitions and network behavior when nodes are added or removed.
 */
class NodeLifecycleTest {

    /**
     * Provides a stream of test environments for the parameterized tests.
     *
     * @return a stream of {@link TestEnvironment} instances
     */
    public static Stream<TestEnvironment> environments() {
        return Stream.of(new TurtleTestEnvironment(RANDOM_SEED), new ContainerTestEnvironment());
    }

    /**
     * Test killing and restarting a single node on all environments.
     *
     * <p>Setting up environments other than Turtle is quite expensive. Therefore, this test covers the full lifecycle
     * of node killing and restarting. It ensures that nodes can be killed, that the remaining nodes continue
     * operating correctly, and that killed nodes can be restarted and rejoin the network. It is run in all
     * environments to ensure consistent behavior across different setups.
     *
     * @param env the test environment for this test
     */
    @ParameterizedTest
    @MethodSource("environments")
    void testKillAndRestartSingleNode(@NonNull final TestEnvironment env) {
        try {
            final Network network = env.network();
            final TimeManager timeManager = env.timeManager();

            // Setup network with 4 nodes
            network.weightGenerator(WeightGenerators.BALANCED);
            final List<Node> nodes = network.addNodes(4);
            final Node nodeToKill = nodes.get(0);
            final Node node1 = nodes.get(1);
            final Node node2 = nodes.get(2);
            final Node node3 = nodes.get(3);

            // Initially, all nodes should not have a platform status (not started)
            for (final Node node : nodes) {
                assertThat(node.platformStatus()).isNull();
            }

            network.start();

            // Verify all nodes are initially active
            for (final Node node : nodes) {
                assertThat(node.platformStatus()).isEqualTo(ACTIVE);
                assertThat(node.isActive()).isTrue();
            }

            // Kill the first node
            nodeToKill.killImmediately();

            // Verify the killed node no longer has a platform status
            assertThat(nodeToKill.platformStatus()).isNull();
            assertThat(nodeToKill.isActive()).isFalse();

            // Verify remaining nodes are still active (network maintains consensus)
            timeManager.waitFor(Duration.ofSeconds(15L));
            assertThat(node1.isActive()).isTrue();
            assertThat(node2.isActive()).isTrue();
            assertThat(node3.isActive()).isTrue();

            // Restart the killed node
            nodeToKill.start();

            // Wait for the restarted node to become active again
            timeManager.waitForCondition(
                    nodeToKill::isActive, Duration.ofSeconds(120L), "Node did not become ACTIVE after restart");

            // Verify all nodes are still active
            assertThat(network.allNodesInStatus(ACTIVE)).isTrue();
        } finally {
            env.destroy();
        }
    }

    /**
     * Test killing and restarting a node multiple times.
     */
    @Test
    void testKillAndRestartNodeMultipleTimes() {
        final TestEnvironment env = new TurtleTestEnvironment(RANDOM_SEED);
        try {
            final Network network = env.network();
            final TimeManager timeManager = env.timeManager();

            // Setup network with 4 nodes
            network.weightGenerator(WeightGenerators.BALANCED);
            final List<Node> nodes = network.addNodes(4);
            final Node volatileNode = nodes.getFirst();

            network.start();

            // Perform kill and restart cycle 3 times
            for (int i = 0; i < 3; i++) {
                // Kill the node
                volatileNode.killImmediately();

                // Verify node is not active
                assertThat(volatileNode.platformStatus()).isNull();

                // Restart the node
                volatileNode.start();

                // Wait for node to become active again
                timeManager.waitForCondition(
                        volatileNode::isActive,
                        Duration.ofSeconds(120L),
                        "Node did not become ACTIVE after restart " + (i + 1));
            }

            // Verify all nodes are active at the end
            for (final Node node : nodes) {
                assertThat(node.isActive()).isTrue();
            }
        } finally {
            env.destroy();
        }
    }

    /**
     * Test that starting an already started node throws an exception.
     */
    @Test
    void testStartAlreadyStartedNodeFails() {
        final TestEnvironment env = new TurtleTestEnvironment(RANDOM_SEED);
        try {
            final Network network = env.network();

            // Setup network with 4 nodes
            final List<Node> nodes = network.addNodes(4);
            final Node node = nodes.getFirst();

            network.start();

            // Try starting the already started node - should throw an Exception
            assertThatThrownBy(node::start).isInstanceOf(IllegalStateException.class);

            // Verify node is still active
            assertThat(node.isActive()).isTrue();
        } finally {
            env.destroy();
        }
    }

    /**
     * Test that killing an already killed node is a no-op.
     */
    @Test
    void testKillAlreadyKilledNodeIsNoOp() {
        final TestEnvironment env = new TurtleTestEnvironment(RANDOM_SEED);
        try {
            final Network network = env.network();

            // Setup network with 4 nodes
            final List<Node> nodes = network.addNodes(4);
            final Node node = nodes.getFirst();

            network.start();

            // Kill the node
            node.killImmediately();

            // Try killing the already killed node - should be a no-op
            node.killImmediately();

            // Verify node is still not active
            assertThat(node.platformStatus()).isNull();
            assertThat(node.isActive()).isFalse();
        } finally {
            env.destroy();
        }
    }

    /**
     * Test that killing a node before it is started is a no-op.
     */
    @Test
    void testKillNodeBeforeStartIsNoOp() {
        final TestEnvironment env = new TurtleTestEnvironment(RANDOM_SEED);
        try {
            final Network network = env.network();

            // Setup network with 4 nodes
            final List<Node> nodes = network.addNodes(4);
            final Node node = nodes.getFirst();

            // Kill the node before starting the network
            node.killImmediately();

            // Verify node is still not active
            assertThat(node.platformStatus()).isNull();
            assertThat(node.isActive()).isFalse();
        } finally {
            env.destroy();
        }
    }

    /**
     * Test killing all nodes in a network.
     */
    @Test
    void testKillAllNodes() {
        final TestEnvironment env = new TurtleTestEnvironment(RANDOM_SEED);
        try {
            final Network network = env.network();

            // Setup network with 4 nodes
            final List<Node> nodes = network.addNodes(4);

            network.start();

            // Kill all nodes
            for (final Node node : nodes) {
                node.killImmediately();
            }

            // Verify all nodes have no platform status
            for (final Node node : nodes) {
                assertThat(node.platformStatus()).isNull();
                assertThat(node.isActive()).isFalse();
            }
        } finally {
            env.destroy();
        }
    }

    /**
     * Test killing and restarting nodes with custom timeout.
     */
    @Test
    void testKillAndRestartNodeWithCustomTimeout() {
        final TestEnvironment env = new TurtleTestEnvironment(RANDOM_SEED);
        try {
            final Network network = env.network();
            final TimeManager timeManager = env.timeManager();

            // Setup network with 4 nodes
            network.weightGenerator(WeightGenerators.BALANCED);
            final List<Node> nodes = network.addNodes(4);
            final Node node = nodes.getFirst();

            network.start();

            // Kill the node with custom timeout
            node.withTimeout(Duration.ofSeconds(30)).killImmediately();

            // Verify node is not active
            assertThat(node.platformStatus()).isNull();

            // Restart the node with custom timeout
            node.withTimeout(Duration.ofSeconds(60)).start();

            // Wait for node to become active again
            timeManager.waitForCondition(
                    node::isActive, Duration.ofSeconds(120L), "Node did not become ACTIVE after restart");
        } finally {
            env.destroy();
        }
    }

    /**
     * Test the platform status transitions when a node is killed.
     */
    @Test
    void testPlatformStatusTransitionsOnKill() {
        final TestEnvironment env = new TurtleTestEnvironment(RANDOM_SEED);
        try {
            final Network network = env.network();
            final TimeManager timeManager = env.timeManager();

            // Setup network with 4 nodes
            network.weightGenerator(WeightGenerators.BALANCED);
            final List<Node> nodes = network.addNodes(4);
            final Node node = nodes.getFirst();

            network.start();

            // Verify node is active before kill
            assertThat(node.platformStatus()).isEqualTo(ACTIVE);
            assertThat(node.isActive()).isTrue();
            assertThat(node.isChecking()).isFalse();
            assertThat(node.isBehind()).isFalse();
            assertThat(node.isInStatus(PlatformStatus.ACTIVE)).isTrue();

            // Kill the node
            node.killImmediately();

            // Verify node no longer has a platform status
            assertThat(node.platformStatus()).isNull();
            assertThat(node.isActive()).isFalse();
            assertThat(node.isChecking()).isFalse();
            assertThat(node.isBehind()).isFalse();
            assertThat(node.isInStatus(PlatformStatus.ACTIVE)).isFalse();
        } finally {
            env.destroy();
        }
    }

    /**
     * Test restarting multiple killed nodes simultaneously.
     */
    @Test
    void testRestartMultipleNodesInMinority() {
        final TestEnvironment env = new TurtleTestEnvironment(RANDOM_SEED);
        try {
            final Network network = env.network();
            final TimeManager timeManager = env.timeManager();

            // Setup network with 5 nodes
            network.weightGenerator(WeightGenerators.BALANCED);
            final List<Node> nodes = network.addNodes(7);
            final Node node0 = nodes.get(0);
            final Node node1 = nodes.get(1);

            network.start();

            // Kill two nodes
            node0.killImmediately();
            node1.killImmediately();

            // Verify killed nodes have no platform status
            assertThat(node0.platformStatus()).isNull();
            assertThat(node1.platformStatus()).isNull();

            // Wait for network to stabilize
            timeManager.waitFor(Duration.ofSeconds(20L));

            // Verify remaining nodes are still active
            for (int i = 2; i < nodes.size(); i++) {
                assertThat(nodes.get(i).isActive()).isTrue();
            }

            // Restart both nodes
            node0.start();
            node1.start();

            // Wait for both nodes to become active again
            timeManager.waitForCondition(
                    () -> node0.isActive() && node1.isActive(),
                    Duration.ofSeconds(120L),
                    "Killed nodes did not become ACTIVE after restart");

            // Verify all nodes are active
            assertThat(network.allNodesInStatus(ACTIVE)).isTrue();
        } finally {
            env.destroy();
        }
    }

    /**
     * Test killing nodes until the network loses consensus.
     */
    @Test
    void testRestartStrongMinority() {
        final TestEnvironment env = new TurtleTestEnvironment(RANDOM_SEED);
        try {
            final Network network = env.network();
            final TimeManager timeManager = env.timeManager();

            // Setup network with 4 nodes (need 3 for consensus)
            network.weightGenerator(WeightGenerators.BALANCED);
            final List<Node> nodes = network.addNodes(4);
            final Node node0 = nodes.get(0);
            final Node node1 = nodes.get(1);
            final Node node2 = nodes.get(2);
            final Node node3 = nodes.get(3);

            network.start();

            // Kill two nodes, leaving only 2 active (below 2/3 threshold)
            node0.killImmediately();
            node1.killImmediately();

            // Verify killed nodes have no platform status
            assertThat(node0.platformStatus()).isNull();
            assertThat(node1.platformStatus()).isNull();

            timeManager.waitForCondition(
                    () -> !node2.isActive() && !node3.isActive(),
                    Duration.ofSeconds(120L),
                    "Remaining nodes did not lose ACTIVE status after consensus loss");

            // Restart killed nodes to restore consensus
            node0.start();
            node1.start();

            // Wait for all nodes to become active again
            timeManager.waitForCondition(
                    network::allNodesAreActive, Duration.ofSeconds(120L), "Not all nodes became ACTIVE after restart");
        } finally {
            env.destroy();
        }
    }
}
