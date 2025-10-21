// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hiero.consensus.model.status.PlatformStatus.ACTIVE;

import com.swirlds.common.test.fixtures.WeightGenerators;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Duration;
import java.util.List;
import java.util.stream.Stream;
import org.hiero.consensus.model.status.PlatformStatus;
import org.hiero.otter.fixtures.container.ContainerTestEnvironment;
import org.hiero.otter.fixtures.result.MultipleNodeLogResults;
import org.hiero.otter.fixtures.turtle.TurtleTestEnvironment;
import org.hiero.otter.fixtures.util.TimeoutException;
import org.junit.jupiter.api.Disabled;
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
        return Stream.of(new TurtleTestEnvironment(), new ContainerTestEnvironment());
    }

    /**
     * Test killing and restarting a single node on all environments.
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
                assertThat(node.isChecking()).isFalse();
                assertThat(node.isBehind()).isFalse();
                assertThat(node.isInStatus(PlatformStatus.ACTIVE)).isTrue();
            }

            for (int i = 0; i < 3; i++) {

                // Capture logs from the nodes that will remain active
                final MultipleNodeLogResults logResults =
                        network.newLogResults().suppressingNode(nodeToKill);
                logResults.clear();

                // Kill the first node
                nodeToKill.killImmediately();

                // Verify the killed node no longer has a platform status
                assertThat(nodeToKill.platformStatus()).isNull();
                assertThat(nodeToKill.isActive()).isFalse();
                assertThat(nodeToKill.isChecking()).isFalse();
                assertThat(nodeToKill.isBehind()).isFalse();
                assertThat(nodeToKill.isInStatus(PlatformStatus.ACTIVE)).isFalse();

                // Verify remaining nodes are still active (network maintains consensus)
                timeManager.waitFor(Duration.ofSeconds(5L));
                assertThat(node1.isActive()).isTrue();
                assertThat(node2.isActive()).isTrue();
                assertThat(node3.isActive()).isTrue();

                // check there are socket exceptions in all logs that remained active
                //                if (env.capabilities().contains(Capability.USES_REAL_NETWORK)) {
                //                    for (final SingleNodeLogResult logResult : logResults.results()) {
                //                        final boolean socketExceptionFound = logResult.logs().stream()
                //                                .map(StructuredLog::marker)
                //                                .anyMatch(marker -> marker ==
                // LogMarker.SOCKET_EXCEPTIONS.getMarker());
                //                        assertThat(socketExceptionFound)
                //                                .as(
                //                                        "Expected node %d to have a SOCKET_EXCEPTION, but it did not",
                //                                        logResult.nodeId().id())
                //                                .isTrue();
                //                        logResult.clear();
                //                    }
                //                }

                // Restart the killed node
                nodeToKill.start();

                // Wait for the restarted node to become active again
                timeManager.waitForCondition(
                        nodeToKill::isActive, Duration.ofSeconds(120L), "Node did not become ACTIVE after restart");
            }

            // Verify all nodes are still active
            assertThat(network.allNodesInStatus(ACTIVE)).isTrue();
        } finally {
            env.destroy();
        }
    }

    /**
     * Test that starting an already started node throws an exception.
     */
    @Test
    void testStartAlreadyStartedNodeFails() {
        final TestEnvironment env = new TurtleTestEnvironment();
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
     * Test that killing a node that is not running is a no-op.
     */
    @Test
    void testKillNotRunningNodeIsNoOp() {
        final TestEnvironment env = new TurtleTestEnvironment();
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
     * Test killing all nodes in a network.
     */
    @Test
    void testKillAllNodes() {
        final TestEnvironment env = new TurtleTestEnvironment();
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
        final TestEnvironment env = new TurtleTestEnvironment();
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

            // Verify node is not running
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
     * Test that timeouts are observed in container environment.
     */
    @Test
    @Disabled("Can be enabled once https://github.com/hiero-ledger/hiero-consensus-node/issues/21658 is fixed")
    void testTimeoutAreObservedInContainerEnvironment() {
        final TestEnvironment env = new ContainerTestEnvironment();
        try {
            final Network network = env.network();
            final TimeManager timeManager = env.timeManager();

            // Setup network with 4 nodes
            final List<Node> nodes = network.addNodes(4);
            final Node node = nodes.getFirst();

            assertThatThrownBy(() -> network.withTimeout(Duration.ofNanos(1L)).start())
                    .isInstanceOf(TimeoutException.class);

            // Kill the node with custom timeout
            assertThatThrownBy(() -> node.withTimeout(Duration.ofNanos(1L)).killImmediately())
                    .isInstanceOf(TimeoutException.class);

            // Verify node is not active
            assertThat(node.platformStatus()).isNull();

            // Restart the node with custom timeout
            assertThatThrownBy(() -> node.withTimeout(Duration.ofNanos(1L)).start())
                    .isInstanceOf(TimeoutException.class);
            node.withTimeout(Duration.ofMinutes(2L)).start();

            // Wait for node to become active again
            timeManager.waitForCondition(
                    node::isActive, Duration.ofSeconds(120L), "Node did not become ACTIVE after restart");
        } finally {
            env.destroy();
        }
    }
}
