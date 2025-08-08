// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.test.turtle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hiero.consensus.model.status.PlatformStatus.ACTIVE;
import static org.hiero.consensus.model.status.PlatformStatus.CHECKING;
import static org.hiero.otter.fixtures.OtterAssertions.assertContinuouslyThat;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import org.hiero.otter.fixtures.Capability;
import org.hiero.otter.fixtures.Network;
import org.hiero.otter.fixtures.Node;
import org.hiero.otter.fixtures.TestEnvironment;
import org.hiero.otter.fixtures.TimeManager;
import org.hiero.otter.fixtures.network.Partition;
import org.hiero.otter.fixtures.turtle.TurtleTestEnvironment;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Tests for the node isolation functionality in the Network interface.
 */
class NetworkIsolationTest {

    private static final long RANDOM_SEED = 0L;

    /**
     * Provides a stream of test environments for the parameterized tests.
     *
     * @return a stream of {@link TestEnvironment} instances
     */
    public static Stream<TestEnvironment> environments() {
        return Stream.of(new TurtleTestEnvironment(RANDOM_SEED));
    }

    /**
     * Test that a single node can be isolated from the network.
     *
     * @param env the test environment for this test
     */
    @ParameterizedTest
    @MethodSource("environments")
    void testIsolateSingleNode(@NonNull final TestEnvironment env) {
        try {
            final Network network = env.network();
            final TimeManager timeManager = env.timeManager();

            // Setup network with 4 nodes
            final List<Node> nodes = network.addNodes(4);
            final Node nodeToIsolate = nodes.getFirst();

            assertContinuouslyThat(network.newLogResults()).haveNoErrorLevelMessages();

            network.start();

            // Wait for nodes to stabilize
            timeManager.waitFor(Duration.ofSeconds(5));

            // Isolate the first node
            final Partition partition = network.isolate(nodeToIsolate);

            // Verify the node is isolated
            assertThat(network.isIsolated(nodeToIsolate)).isTrue();
            assertThat(partition).isNotNull();
            assertThat(partition.nodes()).containsExactly(nodeToIsolate);
            assertThat(partition.size()).isEqualTo(1);

            // Verify the node is in a partition
            assertThat(network.getPartitionContaining(nodeToIsolate)).isEqualTo(partition);
            assertThat(network.partitions()).contains(partition);

            // Let the network run with the isolated node
            timeManager.waitFor(Duration.ofSeconds(15));

            // Other nodes should continue to make progress
            assertThat(nodes.get(0).platformStatus()).isEqualTo(CHECKING);
            assertThat(nodes.get(1).platformStatus()).isEqualTo(ACTIVE);
            assertThat(nodes.get(2).platformStatus()).isEqualTo(ACTIVE);
            assertThat(nodes.get(3).platformStatus()).isEqualTo(ACTIVE);
        } finally {
            env.destroy();
        }
    }

    /**
     * Test that an isolated node can be rejoined with the network.
     *
     * @param env the test environment for this test
     */
    @ParameterizedTest
    @MethodSource("environments")
    void testRejoinIsolatedNode(@NonNull final TestEnvironment env) {
        try {
            final Network network = env.network();
            final TimeManager timeManager = env.timeManager();

            // Setup network with 4 nodes
            final List<Node> nodes = network.addNodes(4);
            final Node nodeToIsolate = nodes.getFirst();

            assertContinuouslyThat(network.newLogResults()).haveNoErrorLevelMessages();

            network.start();

            // Wait for nodes to stabilize
            timeManager.waitFor(Duration.ofSeconds(5));

            // Isolate and then rejoin the node
            final Partition partition = network.isolate(nodeToIsolate);
            assertThat(network.isIsolated(nodeToIsolate)).isTrue();

            timeManager.waitFor(Duration.ofSeconds(15));

            // Rejoin the node
            network.rejoin(nodeToIsolate);

            // Verify the node is no longer isolated
            assertThat(network.isIsolated(nodeToIsolate)).isFalse();
            assertThat(network.getPartitionContaining(nodeToIsolate)).isNull();
            assertThat(network.partitions()).doesNotContain(partition);

            // Let the network run after rejoining
            timeManager.waitFor(Duration.ofSeconds(15));

            // Rejoining a network requires the RECONNECT capability.
            if (env.capabilities().contains(Capability.RECONNECT)) {
                // The node should be active again
                assertThat(nodeToIsolate.platformStatus()).isEqualTo(ACTIVE);
            }

            // All other nodes should be active
            assertThat(nodes.get(1).platformStatus()).isEqualTo(ACTIVE);
            assertThat(nodes.get(2).platformStatus()).isEqualTo(ACTIVE);
            assertThat(nodes.get(3).platformStatus()).isEqualTo(ACTIVE);
        } finally {
            env.destroy();
        }
    }

    /**
     * Test the isIsolated method for various scenarios.
     *
     * @param env the test environment for this test
     */
    @ParameterizedTest
    @MethodSource("environments")
    void testIsIsolatedCheck(@NonNull final TestEnvironment env) {
        try {
            final Network network = env.network();
            final TimeManager timeManager = env.timeManager();

            // Setup network with 4 nodes
            final List<Node> nodes = network.addNodes(4);
            final Node node1 = nodes.getFirst();
            final Node node2 = nodes.get(1);
            final Node node3 = nodes.get(2);

            network.start();

            // Wait for nodes to stabilize
            timeManager.waitFor(Duration.ofSeconds(5));

            // Initially no nodes are isolated
            assertThat(network.isIsolated(node1)).isFalse();
            assertThat(network.isIsolated(node2)).isFalse();
            assertThat(network.isIsolated(node3)).isFalse();

            // Isolate node1
            network.isolate(node1);
            assertThat(network.isIsolated(node1)).isTrue();
            assertThat(network.isIsolated(node2)).isFalse();

            // Create a partition with multiple nodes (not isolated)
            network.createPartition(Set.of(node2, node3));
            assertThat(network.isIsolated(node2)).isFalse(); // Part of multi-node partition
            assertThat(network.isIsolated(node3)).isFalse(); // Part of multi-node partition
        } finally {
            env.destroy();
        }
    }

    /**
     * Test isolating multiple nodes independently.
     *
     * @param env the test environment for this test
     */
    @ParameterizedTest
    @MethodSource("environments")
    void testIsolateMultipleNodes(@NonNull final TestEnvironment env) {
        try {
            final Network network = env.network();
            final TimeManager timeManager = env.timeManager();

            // Setup network with 5 nodes
            final List<Node> nodes = network.addNodes(5);
            final Node node1 = nodes.getFirst();
            final Node node2 = nodes.get(1);

            network.start();

            // Wait for nodes to stabilize
            timeManager.waitFor(Duration.ofSeconds(5));

            // Isolate two nodes independently
            final Partition partition1 = network.isolate(node1);
            final Partition partition2 = network.isolate(node2);

            // Verify both nodes are isolated
            assertThat(network.isIsolated(node1)).isTrue();
            assertThat(network.isIsolated(node2)).isTrue();

            // Verify they are in separate partitions
            assertThat(partition1).isNotEqualTo(partition2);
            assertThat(partition1.nodes()).containsExactly(node1);
            assertThat(partition2.nodes()).containsExactly(node2);

            // Verify the network has both partitions
            assertThat(network.partitions()).containsExactlyInAnyOrder(partition1, partition2);

            // Rejoin one node
            network.rejoin(node1);
            assertThat(network.isIsolated(node1)).isFalse();
            assertThat(network.isIsolated(node2)).isTrue();
            assertThat(network.partitions()).containsExactly(partition2);
        } finally {
            env.destroy();
        }
    }

    /**
     * Test that isolating a node that is already in a partition throws an exception.
     *
     * @param env the test environment for this test
     */
    @ParameterizedTest
    @MethodSource("environments")
    void testIsolateAlreadyPartitionedNode(@NonNull final TestEnvironment env) {
        try {
            final Network network = env.network();
            final TimeManager timeManager = env.timeManager();

            // Setup network with 4 nodes
            final List<Node> nodes = network.addNodes(4);
            final Node node1 = nodes.getFirst();

            network.start();

            // Wait for nodes to stabilize
            timeManager.waitFor(Duration.ofSeconds(5));

            // First isolate the node
            network.isolate(node1);

            // Try to isolate the same node again - should throw exception
            assertThatThrownBy(() -> network.isolate(node1))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("already in a partition");
        } finally {
            env.destroy();
        }
    }

    /**
     * Test that rejoining a node that is not isolated throws an exception.
     *
     * @param env the test environment for this test
     */
    @ParameterizedTest
    @MethodSource("environments")
    void testRejoinNonIsolatedNode(@NonNull final TestEnvironment env) {
        try {
            final Network network = env.network();
            final TimeManager timeManager = env.timeManager();

            // Setup network with 4 nodes
            final List<Node> nodes = network.addNodes(4);
            final Node node1 = nodes.getFirst();

            network.start();

            // Wait for nodes to stabilize
            timeManager.waitFor(Duration.ofSeconds(5));

            // Try to rejoin a node that is not isolated - should throw exception
            assertThatThrownBy(() -> network.rejoin(node1))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("not isolated");
        } finally {
            env.destroy();
        }
    }

    /**
     * Test the partition lifecycle with isolation.
     *
     * @param env the test environment for this test
     */
    @ParameterizedTest
    @MethodSource("environments")
    void testPartitionLifecycleWithIsolation(@NonNull final TestEnvironment env) {
        try {
            final Network network = env.network();
            final TimeManager timeManager = env.timeManager();

            // Setup network with 4 nodes
            final List<Node> nodes = network.addNodes(4);
            final Node node1 = nodes.getFirst();
            final Node node2 = nodes.get(1);

            network.start();

            // Wait for nodes to stabilize
            timeManager.waitFor(Duration.ofSeconds(5));

            // Create a regular partition first
            final Partition regularPartition = network.createPartition(Set.of(node1, node2));
            assertThat(network.getPartitionContaining(node1)).isEqualTo(regularPartition);
            assertThat(network.getPartitionContaining(node2)).isEqualTo(regularPartition);
            assertThat(network.isIsolated(node1)).isFalse();
            assertThat(network.isIsolated(node2)).isFalse();

            // Remove the partition
            network.removePartition(regularPartition);
            assertThat(network.getPartitionContaining(node1)).isNull();
            assertThat(network.getPartitionContaining(node2)).isNull();

            // Now isolate node1
            final Partition isolationPartition = network.isolate(node1);
            assertThat(network.isIsolated(node1)).isTrue();
            assertThat(network.getPartitionContaining(node1)).isEqualTo(isolationPartition);

            // Remove the isolation partition using removePartition
            network.removePartition(isolationPartition);
            assertThat(network.isIsolated(node1)).isFalse();
            assertThat(network.getPartitionContaining(node1)).isNull();
        } finally {
            env.destroy();
        }
    }

    /**
     * Test restoreConnectivity with isolated nodes.
     *
     * @param env the test environment for this test
     */
    @ParameterizedTest
    @MethodSource("environments")
    void testRestoreConnectivityWithIsolatedNodes(@NonNull final TestEnvironment env) {
        try {
            final Network network = env.network();
            final TimeManager timeManager = env.timeManager();

            // Setup network with 5 nodes
            final List<Node> nodes = network.addNodes(5);
            final Node node1 = nodes.getFirst();
            final Node node2 = nodes.get(1);
            final Node node3 = nodes.get(2);
            final Node node4 = nodes.get(3);

            network.start();

            // Wait for nodes to stabilize
            timeManager.waitFor(Duration.ofSeconds(5));

            // Create various partitions and isolations
            network.isolate(node1);
            network.isolate(node2);
            final Partition multiNodePartition = network.createPartition(Set.of(node3, node4));

            // Verify the state
            assertThat(network.isIsolated(node1)).isTrue();
            assertThat(network.isIsolated(node2)).isTrue();
            assertThat(network.getPartitionContaining(node3)).isEqualTo(multiNodePartition);
            assertThat(network.getPartitionContaining(node4)).isEqualTo(multiNodePartition);
            assertThat(network.partitions()).hasSize(3);

            // Restore all connectivity
            network.restoreConnectivity();

            // Verify all nodes are no longer isolated or partitioned
            assertThat(network.isIsolated(node1)).isFalse();
            assertThat(network.isIsolated(node2)).isFalse();
            assertThat(network.getPartitionContaining(node1)).isNull();
            assertThat(network.getPartitionContaining(node2)).isNull();
            assertThat(network.getPartitionContaining(node3)).isNull();
            assertThat(network.getPartitionContaining(node4)).isNull();
            assertThat(network.partitions()).isEmpty();
        } finally {
            env.destroy();
        }
    }
}
