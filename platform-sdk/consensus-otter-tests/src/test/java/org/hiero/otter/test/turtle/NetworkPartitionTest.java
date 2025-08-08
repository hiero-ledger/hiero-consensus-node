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
import org.hiero.otter.fixtures.Network;
import org.hiero.otter.fixtures.Node;
import org.hiero.otter.fixtures.TestEnvironment;
import org.hiero.otter.fixtures.TimeManager;
import org.hiero.otter.fixtures.network.Partition;
import org.hiero.otter.fixtures.turtle.TurtleTestEnvironment;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Tests for the network partition functionality in the Network interface.
 */
class NetworkPartitionTest {

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
     * Test that a partition can be created with multiple nodes.
     *
     * @param env the test environment for this test
     */
    @ParameterizedTest
    @MethodSource("environments")
    void testCreateBasicPartition(@NonNull final TestEnvironment env) {
        try {
            final Network network = env.network();
            final TimeManager timeManager = env.timeManager();

            // Setup network with 5 nodes
            final List<Node> nodes = network.addNodes(6);
            final Node node0 = nodes.get(0);
            final Node node1 = nodes.get(1);
            final Node node2 = nodes.get(2);

            assertContinuouslyThat(network.newLogResults()).haveNoErrorLevelMessages();

            network.start();

            // Wait for nodes to become active
            timeManager.waitFor(Duration.ofSeconds(30));

            // Create a partition with two nodes
            final Partition partition = network.createPartition(Set.of(node0, node1));

            // Verify the partition was created correctly
            assertThat(partition).isNotNull();
            assertThat(partition.nodes()).containsExactlyInAnyOrder(node0, node1);
            assertThat(partition.size()).isEqualTo(2);

            // Verify nodes are in the partition
            assertThat(network.getPartitionContaining(node0)).isEqualTo(partition);
            assertThat(network.getPartitionContaining(node1)).isEqualTo(partition);
            assertThat(network.getPartitionContaining(node2)).isNull();

            // Verify partition is tracked by the network
            assertThat(network.partitions()).contains(partition);

            // Verify nodes in partition are not isolated (partition has multiple nodes)
            assertThat(network.isIsolated(node0)).isFalse();
            assertThat(network.isIsolated(node1)).isFalse();
            assertThat(network.isIsolated(node2)).isFalse();

            // Let the network run with the partition
            timeManager.waitFor(Duration.ofSeconds(30));

            // Nodes outside partition should continue to make progress
            assertThat(nodes.get(0).platformStatus()).isEqualTo(CHECKING);
            assertThat(nodes.get(1).platformStatus()).isEqualTo(CHECKING);
            assertThat(nodes.get(2).platformStatus()).isEqualTo(ACTIVE);
            assertThat(nodes.get(3).platformStatus()).isEqualTo(ACTIVE);
            assertThat(nodes.get(4).platformStatus()).isEqualTo(ACTIVE);
            assertThat(nodes.get(5).platformStatus()).isEqualTo(ACTIVE);
        } finally {
            env.destroy();
        }
    }

    /**
     * Test that multiple partitions can be created simultaneously.
     *
     * @param env the test environment for this test
     */
    @ParameterizedTest
    @MethodSource("environments")
    void testCreateMultiplePartitions(@NonNull final TestEnvironment env) {
        try {
            final Network network = env.network();
            final TimeManager timeManager = env.timeManager();

            // Setup network with 6 nodes
            final List<Node> nodes = network.addNodes(6);

            network.start();

            // Wait for nodes to become active
            timeManager.waitFor(Duration.ofSeconds(5));

            // Create two separate partitions
            final Partition partition1 = network.createPartition(Set.of(nodes.get(0), nodes.get(1)));
            final Partition partition2 = network.createPartition(Set.of(nodes.get(2), nodes.get(3)));

            // Verify both partitions exist
            assertThat(network.partitions()).containsExactlyInAnyOrder(partition1, partition2);
            assertThat(partition1).isNotEqualTo(partition2);

            // Verify nodes are in correct partitions
            assertThat(network.getPartitionContaining(nodes.get(0))).isEqualTo(partition1);
            assertThat(network.getPartitionContaining(nodes.get(1))).isEqualTo(partition1);
            assertThat(network.getPartitionContaining(nodes.get(2))).isEqualTo(partition2);
            assertThat(network.getPartitionContaining(nodes.get(3))).isEqualTo(partition2);

            // Verify remaining nodes are not in any partition
            assertThat(network.getPartitionContaining(nodes.get(4))).isNull();
            assertThat(network.getPartitionContaining(nodes.get(5))).isNull();

            // Let the network run with multiple partitions
            timeManager.waitFor(Duration.ofSeconds(10));
        } finally {
            env.destroy();
        }
    }

    /**
     * Test that a partition with a single node behaves like isolation.
     *
     * @param env the test environment for this test
     */
    @ParameterizedTest
    @MethodSource("environments")
    void testSingleNodePartition(@NonNull final TestEnvironment env) {
        try {
            final Network network = env.network();
            final TimeManager timeManager = env.timeManager();

            // Setup network with 4 nodes
            final List<Node> nodes = network.addNodes(4);
            final Node nodeToPartition = nodes.getFirst();

            network.start();

            // Wait for nodes to become active
            timeManager.waitFor(Duration.ofSeconds(5));

            // Create a partition with a single node
            final Partition partition = network.createPartition(Set.of(nodeToPartition));

            // Verify the partition was created
            assertThat(partition.nodes()).containsExactly(nodeToPartition);
            assertThat(partition.size()).isEqualTo(1);
            assertThat(network.getPartitionContaining(nodeToPartition)).isEqualTo(partition);

            // Single node partition should be considered isolated
            assertThat(network.isIsolated(nodeToPartition)).isTrue();

            // Other nodes should not be isolated
            for (int i = 1; i < nodes.size(); i++) {
                assertThat(network.isIsolated(nodes.get(i))).isFalse();
            }
        } finally {
            env.destroy();
        }
    }

    /**
     * Test that partitions can be removed and connectivity restored.
     *
     * @param env the test environment for this test
     */
    @ParameterizedTest
    @MethodSource("environments")
    void testRemovePartition(@NonNull final TestEnvironment env) {
        try {
            final Network network = env.network();
            final TimeManager timeManager = env.timeManager();

            // Setup network with 4 nodes
            final List<Node> nodes = network.addNodes(4);
            final Node node1 = nodes.get(0);
            final Node node2 = nodes.get(1);

            network.start();

            // Wait for nodes to become active
            timeManager.waitFor(Duration.ofSeconds(5));

            // Create a partition
            final Partition partition = network.createPartition(Set.of(node1, node2));
            assertThat(network.partitions()).contains(partition);
            assertThat(network.getPartitionContaining(node1)).isEqualTo(partition);
            assertThat(network.getPartitionContaining(node2)).isEqualTo(partition);

            // Remove the partition
            network.removePartition(partition);

            // Verify partition is removed
            assertThat(network.partitions()).doesNotContain(partition);
            assertThat(network.getPartitionContaining(node1)).isNull();
            assertThat(network.getPartitionContaining(node2)).isNull();

            // Let the network run after partition removal
            timeManager.waitFor(Duration.ofSeconds(10));

            // All nodes should be active again
            for (final Node node : nodes) {
                assertThat(node.platformStatus()).isEqualTo(ACTIVE);
            }
        } finally {
            env.destroy();
        }
    }

    /**
     * Test that creating a partition with empty nodes throws an exception.
     *
     * @param env the test environment for this test
     */
    @ParameterizedTest
    @MethodSource("environments")
    void testCreatePartitionWithEmptyNodes(@NonNull final TestEnvironment env) {
        try {
            final Network network = env.network();

            // Setup network with 4 nodes
            network.addNodes(4);
            network.start();

            // Try to create a partition with no nodes - should throw exception
            assertThatThrownBy(() -> network.createPartition(Set.of()))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Cannot create a partition with no nodes.");
        } finally {
            env.destroy();
        }
    }

    /**
     * Test that creating a partition with nodes already in a partition throws an exception.
     *
     * @param env the test environment for this test
     */
    @ParameterizedTest
    @MethodSource("environments")
    void testCreatePartitionWithAlreadyPartitionedNodes(@NonNull final TestEnvironment env) {
        try {
            final Network network = env.network();

            // Setup network with 4 nodes
            final List<Node> nodes = network.addNodes(4);
            final Node node1 = nodes.get(0);
            final Node node2 = nodes.get(1);
            final Node node3 = nodes.get(2);

            network.start();

            // Create first partition
            network.createPartition(Set.of(node1, node2));

            // Try to create another partition with node1 - should throw exception
            assertThatThrownBy(() -> network.createPartition(Set.of(node1, node3)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Cannot create a partition with nodes that are already in a partition.");
        } finally {
            env.destroy();
        }
    }

    /**
     * Test that large partitions can be created and managed.
     *
     * @param env the test environment for this test
     */
    @ParameterizedTest
    @MethodSource("environments")
    void testLargePartition(@NonNull final TestEnvironment env) {
        try {
            final Network network = env.network();
            final TimeManager timeManager = env.timeManager();

            // Setup network with 10 nodes
            final List<Node> nodes = network.addNodes(10);

            network.start();

            // Wait for nodes to become active
            timeManager.waitFor(Duration.ofSeconds(5));

            // Create a large partition with 7 nodes
            final Set<Node> partitionNodes = Set.of(
                    nodes.get(0), nodes.get(1), nodes.get(2), nodes.get(3),
                    nodes.get(4), nodes.get(5), nodes.get(6)
            );
            final Partition partition = network.createPartition(partitionNodes);

            // Verify the large partition
            assertThat(partition.nodes()).hasSize(7);
            assertThat(partition.nodes()).containsExactlyInAnyOrderElementsOf(partitionNodes);

            // Verify all nodes in partition are tracked correctly
            for (final Node node : partitionNodes) {
                assertThat(network.getPartitionContaining(node)).isEqualTo(partition);
                assertThat(network.isIsolated(node)).isFalse(); // Multi-node partition
            }

            // Verify remaining nodes are not in partition
            for (int i = 7; i < 10; i++) {
                assertThat(network.getPartitionContaining(nodes.get(i))).isNull();
            }

            // Let the network run with the large partition
            timeManager.waitFor(Duration.ofSeconds(30));

            // Non-partitioned nodes should continue to make progress
            assertThat(nodes.get(0).platformStatus()).isEqualTo(ACTIVE);
            assertThat(nodes.get(1).platformStatus()).isEqualTo(ACTIVE);
            assertThat(nodes.get(2).platformStatus()).isEqualTo(ACTIVE);
            assertThat(nodes.get(3).platformStatus()).isEqualTo(ACTIVE);
            assertThat(nodes.get(4).platformStatus()).isEqualTo(ACTIVE);
            assertThat(nodes.get(5).platformStatus()).isEqualTo(ACTIVE);
            assertThat(nodes.get(6).platformStatus()).isEqualTo(ACTIVE);
            assertThat(nodes.get(7).platformStatus()).isEqualTo(CHECKING);
            assertThat(nodes.get(8).platformStatus()).isEqualTo(CHECKING);
            assertThat(nodes.get(9).platformStatus()).isEqualTo(CHECKING);
        } finally {
            env.destroy();
        }
    }

    /**
     * Test that partitions work correctly with network freeze operations.
     *
     * @param env the test environment for this test
     */
    @Disabled("Investigate how partitions interact with freeze in production")
    @ParameterizedTest
    @MethodSource("environments")
    void testPartitionDuringFreeze(@NonNull final TestEnvironment env) {
        try {
            final Network network = env.network();
            final TimeManager timeManager = env.timeManager();

            // Setup network with 5 nodes
            final List<Node> nodes = network.addNodes(5);
            final Node node0 = nodes.get(0);
            final Node node1 = nodes.get(1);

            assertContinuouslyThat(network.newLogResults()).haveNoErrorLevelMessages();

            network.start();

            // Wait for nodes to become active
            timeManager.waitFor(Duration.ofSeconds(5));

            // Create a partition
            final Partition partition = network.createPartition(Set.of(node0, node1));
            assertThat(network.getPartitionContaining(node0)).isEqualTo(partition);

            // Freeze the network
            network.freeze();

            // The partition should remain intact during freeze
            assertThat(network.getPartitionContaining(node0)).isEqualTo(partition);
            assertThat(network.partitions()).contains(partition);
        } finally {
            env.destroy();
        }
    }

    /**
     * Test that restoreConnectivity removes all partitions.
     *
     * @param env the test environment for this test
     */
    @ParameterizedTest
    @MethodSource("environments")
    void testRestoreConnectivityWithPartitions(@NonNull final TestEnvironment env) {
        try {
            final Network network = env.network();
            final TimeManager timeManager = env.timeManager();

            // Setup network with 8 nodes
            final List<Node> nodes = network.addNodes(8);

            network.start();

            // Wait for nodes to become active
            timeManager.waitFor(Duration.ofSeconds(5));

            // Create multiple partitions
            final Partition partition1 = network.createPartition(Set.of(nodes.get(0), nodes.get(1)));
            final Partition partition2 = network.createPartition(Set.of(nodes.get(2), nodes.get(3), nodes.get(4)));
            final Partition partition3 = network.createPartition(Set.of(nodes.get(5))); // Single node partition

            // Verify partitions exist
            assertThat(network.partitions()).containsExactlyInAnyOrder(partition1, partition2, partition3);
            assertThat(network.isIsolated(nodes.get(5))).isTrue();

            // Restore connectivity
            network.restoreConnectivity();

            // Verify all partitions are removed
            assertThat(network.partitions()).isEmpty();
            for (final Node node : nodes) {
                assertThat(network.getPartitionContaining(node)).isNull();
                assertThat(network.isIsolated(node)).isFalse();
            }

            // Let the network run after connectivity restoration
            timeManager.waitFor(Duration.ofSeconds(10));

            // All nodes should be active
            for (final Node node : nodes) {
                assertThat(node.platformStatus()).isEqualTo(ACTIVE);
            }
        } finally {
            env.destroy();
        }
    }

    /**
     * Test that partition lifecycle works correctly with complex scenarios.
     *
     * @param env the test environment for this test
     */
    @ParameterizedTest
    @MethodSource("environments")
    void testComplexPartitionLifecycle(@NonNull final TestEnvironment env) {
        try {
            final Network network = env.network();
            final TimeManager timeManager = env.timeManager();

            // Setup network with 6 nodes
            final List<Node> nodes = network.addNodes(6);

            network.start();

            // Wait for nodes to become active
            timeManager.waitFor(Duration.ofSeconds(5));

            // Create initial partition
            final Partition partition1 = network.createPartition(Set.of(nodes.get(0), nodes.get(1), nodes.get(2)));
            assertThat(network.partitions()).hasSize(1);

            // Remove the partition
            network.removePartition(partition1);
            assertThat(network.partitions()).isEmpty();

            // Create new partitions with different configurations
            final Partition partition2 = network.createPartition(Set.of(nodes.get(0), nodes.get(3)));
            final Partition partition3 = network.createPartition(Set.of(nodes.get(1))); // Isolated node

            // Verify the new state
            assertThat(network.partitions()).containsExactlyInAnyOrder(partition2, partition3);
            assertThat(network.isIsolated(nodes.get(1))).isTrue();
            assertThat(network.isIsolated(nodes.get(0))).isFalse(); // Part of multi-node partition

            // Remove one partition and verify the other remains
            network.removePartition(partition3);
            assertThat(network.partitions()).containsExactly(partition2);
            assertThat(network.isIsolated(nodes.get(1))).isFalse();
            assertThat(network.getPartitionContaining(nodes.get(0))).isEqualTo(partition2);

            // Final cleanup
            network.removePartition(partition2);
            assertThat(network.partitions()).isEmpty();

            // Let the network run after all partitions are removed
            timeManager.waitFor(Duration.ofSeconds(10));

            // All nodes should be active
            for (final Node node : nodes) {
                assertThat(node.platformStatus()).isEqualTo(ACTIVE);
                assertThat(network.getPartitionContaining(node)).isNull();
                assertThat(network.isIsolated(node)).isFalse();
            }
        } finally {
            env.destroy();
        }
    }

    /**
     * Test interaction between partitions and node isolation.
     *
     * @param env the test environment for this test
     */
    @ParameterizedTest
    @MethodSource("environments")
    void testPartitionAndIsolationInteraction(@NonNull final TestEnvironment env) {
        try {
            final Network network = env.network();
            final TimeManager timeManager = env.timeManager();

            // Setup network with 6 nodes
            final List<Node> nodes = network.addNodes(6);

            network.start();

            // Wait for nodes to become active
            timeManager.waitFor(Duration.ofSeconds(5));

            // Create a multi-node partition
            final Partition multiNodePartition = network.createPartition(Set.of(nodes.get(0), nodes.get(1)));

            // Isolate another node (creates single-node partition)
            final Partition isolationPartition = network.isolate(nodes.get(2));

            // Verify both partitions exist
            assertThat(network.partitions()).containsExactlyInAnyOrder(multiNodePartition, isolationPartition);

            // Verify isolation behavior
            assertThat(network.isIsolated(nodes.get(0))).isFalse(); // Part of multi-node partition
            assertThat(network.isIsolated(nodes.get(1))).isFalse(); // Part of multi-node partition
            assertThat(network.isIsolated(nodes.get(2))).isTrue();  // Isolated node

            // Try to isolate a node that's already in a partition - should throw exception
            assertThatThrownBy(() -> network.isolate(nodes.getFirst()))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("already in a partition");

            // Rejoin the isolated node
            network.rejoin(nodes.get(2));
            assertThat(network.isIsolated(nodes.get(2))).isFalse();
            assertThat(network.partitions()).containsExactly(multiNodePartition);

            // Remove the remaining partition
            network.removePartition(multiNodePartition);
            assertThat(network.partitions()).isEmpty();

            // Let the network run with full connectivity
            timeManager.waitFor(Duration.ofSeconds(10));

            // All nodes should be active
            for (final Node node : nodes) {
                assertThat(node.platformStatus()).isEqualTo(ACTIVE);
            }
        } finally {
            env.destroy();
        }
    }
}