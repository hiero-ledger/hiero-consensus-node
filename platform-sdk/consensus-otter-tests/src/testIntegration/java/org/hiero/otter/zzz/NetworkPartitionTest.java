// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.zzz;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hiero.consensus.model.status.PlatformStatus.CHECKING;

import com.swirlds.common.test.fixtures.WeightGenerators;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Duration;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import org.hiero.otter.fixtures.Network;
import org.hiero.otter.fixtures.Node;
import org.hiero.otter.fixtures.TestEnvironment;
import org.hiero.otter.fixtures.TimeManager;
import org.hiero.otter.fixtures.container.ContainerTestEnvironment;
import org.hiero.otter.fixtures.network.Partition;
import org.hiero.otter.fixtures.result.MultipleNodeLogResults;
import org.hiero.otter.fixtures.turtle.TurtleTestEnvironment;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Tests for the network partition functionality in the Network interface.
 */
class NetworkPartitionTest {

    /**
     * Provides a stream of test environments for the parameterized tests.
     *
     * @return a stream of {@link TestEnvironment} instances
     */
    public static Stream<TestEnvironment> environments() {
        return Stream.of(new TurtleTestEnvironment(), new ContainerTestEnvironment());
    }

    /**
     * Test creating and removing a partition with multiple nodes on all environments.
     *
     * <p>Setting up environments other than Turtle is quite expensive. Therefore, this test covers the full lifecycle
     * of a partition, including creation, verification, and removal. It ensures that the partitioning logic works
     * correctly and that nodes respond appropriately to changes in network topology. It is run in all environments
     * to ensure consistent behavior across different setups.
     *
     * @param env the test environment for this test
     */
    @ParameterizedTest
    @MethodSource("environments")
    void testCreateAndRemovePartition(@NonNull final TestEnvironment env) {
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

            for (int i = 0; i < 3; i++) {

                // Capture logs from all nodes
                final MultipleNodeLogResults logResults = network.newLogResults();
                logResults.clear();

                // Create a partition using varargs syntax
                final Partition partition = network.createNetworkPartition(node0, node1);

                // Verify the partition was created correctly
                assertThat(partition).isNotNull();
                assertThat(partition.nodes()).containsExactlyInAnyOrder(node0, node1);

                // Verify we have exactly 2 partitions
                assertThat(network.networkPartitions()).hasSize(2);
                assertThat(network.networkPartitions()).contains(partition);

                // Verify nodes not in our partition are in the complementary partition
                final Partition complementaryPartition = network.getNetworkPartitionContaining(node3);
                assertThat(complementaryPartition).isNotNull().isNotEqualTo(partition);
                assertThat(complementaryPartition.nodes()).containsExactlyInAnyOrder(node2, node3);

                // Verify each node knows it's in the partition
                assertThat(partition.contains(node0)).isTrue();
                assertThat(partition.contains(node1)).isTrue();
                assertThat(partition.contains(node2)).isFalse();
                assertThat(partition.contains(node3)).isFalse();
                assertThat(complementaryPartition.contains(node0)).isFalse();
                assertThat(complementaryPartition.contains(node1)).isFalse();
                assertThat(complementaryPartition.contains(node2)).isTrue();
                assertThat(complementaryPartition.contains(node3)).isTrue();

                // Verify network knows about the partition
                assertThat(network.getNetworkPartitionContaining(node0)).isEqualTo(partition);
                assertThat(network.getNetworkPartitionContaining(node1)).isEqualTo(partition);
                assertThat(network.getNetworkPartitionContaining(node2)).isEqualTo(complementaryPartition);
                assertThat(network.getNetworkPartitionContaining(node3)).isEqualTo(complementaryPartition);

                // Wait for nodes to become inactive
                timeManager.waitForCondition(
                        () -> network.allNodesInStatus(CHECKING),
                        Duration.ofSeconds(120L),
                        "Not all nodes entered CHECKING status within the expected time after creating partition");

                // check there are socket exceptions in all logs
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

                // Remove the partition
                network.removePartition(partition);

                // Verify all nodes are back in normal connectivity
                assertThat(network.networkPartitions()).isEmpty();
                assertThat(network.getNetworkPartitionContaining(node0)).isNull();
                assertThat(network.getNetworkPartitionContaining(node1)).isNull();
                assertThat(network.getNetworkPartitionContaining(node2)).isNull();
                assertThat(network.getNetworkPartitionContaining(node3)).isNull();

                // The node should be active again
                timeManager.waitForCondition(
                        network::allNodesAreActive,
                        Duration.ofSeconds(120L),
                        "Not all nodes entered ACTIVE status within the expected time after removing partition");
            }
        } finally {
            env.destroy();
        }
    }

    /**
     * Test creating a partition with multiple nodes using {@link java.util.Collection} parameter.
     *
     * <p>This test is similar to {@link #testCreateAndRemovePartition(TestEnvironment)} but specifically
     * verifies the behavior of the {@link Network#createNetworkPartition(Collection)} method. As
     * {@link Network#createNetworkPartition(Node, Node...)} calls through to the collection-based method,
     * it is not necessary to run both tests in all environments.
     */
    @Test
    void testCreatePartitionWithCollection() {
        final TestEnvironment env = new TurtleTestEnvironment();
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

            // Create a partition with nodes 0, 1
            final Set<Node> partitionNodes = Set.of(node0, node1);
            final Partition partition = network.createNetworkPartition(partitionNodes);

            // Verify the partition was created correctly
            assertThat(partition).isNotNull();
            assertThat(partition.nodes()).containsExactlyInAnyOrderElementsOf(partitionNodes);

            // Verify we have exactly 2 partitions
            assertThat(network.networkPartitions()).hasSize(2);
            assertThat(network.networkPartitions()).contains(partition);

            // Verify nodes not in our partition are in the complementary partition
            final Partition complementaryPartition = network.getNetworkPartitionContaining(node3);
            assertThat(complementaryPartition).isNotNull().isNotEqualTo(partition);
            assertThat(complementaryPartition.nodes()).containsExactlyInAnyOrder(node2, node3);

            // Verify each node knows it's in the partition
            assertThat(partition.contains(node0)).isTrue();
            assertThat(partition.contains(node1)).isTrue();
            assertThat(partition.contains(node2)).isFalse();
            assertThat(partition.contains(node3)).isFalse();
            assertThat(complementaryPartition.contains(node0)).isFalse();
            assertThat(complementaryPartition.contains(node1)).isFalse();
            assertThat(complementaryPartition.contains(node2)).isTrue();
            assertThat(complementaryPartition.contains(node3)).isTrue();

            // Verify network knows about the partition
            assertThat(network.getNetworkPartitionContaining(node0)).isEqualTo(partition);
            assertThat(network.getNetworkPartitionContaining(node1)).isEqualTo(partition);
            assertThat(network.getNetworkPartitionContaining(node2)).isEqualTo(complementaryPartition);
            assertThat(network.getNetworkPartitionContaining(node3)).isEqualTo(complementaryPartition);

            // Wait for nodes to become inactive
            timeManager.waitForCondition(
                    () -> network.allNodesInStatus(CHECKING),
                    Duration.ofSeconds(120L),
                    "Not all nodes entered CHECKING status within the expected time after creating partition");

        } finally {
            env.destroy();
        }
    }

    /**
     * Test creating multiple independent partitions.
     */
    @Test
    void testMultiplePartitions() {
        final TestEnvironment env = new TurtleTestEnvironment();
        try {
            final Network network = env.network();
            final TimeManager timeManager = env.timeManager();

            // Setup network with 6 nodes
            final List<Node> nodes = network.addNodes(6);
            final Node node0 = nodes.get(0);
            final Node node1 = nodes.get(1);
            final Node node2 = nodes.get(2);
            final Node node3 = nodes.get(3);
            final Node node4 = nodes.get(4);
            final Node node5 = nodes.get(5);

            network.start();

            // Wait for nodes to stabilize
            timeManager.waitFor(Duration.ofSeconds(5));

            // Create first partition
            final Partition partition1 = network.createNetworkPartition(node0, node1);

            // Verify first partition
            assertThat(partition1.nodes()).containsExactlyInAnyOrder(node0, node1);
            assertThat(network.networkPartitions()).hasSize(2); // partition1 + complementary

            // Verify remaining nodes are in a complementary partition
            final Partition remainingPartition = network.getNetworkPartitionContaining(node2);
            assertThat(remainingPartition).isNotNull();
            assertThat(remainingPartition.nodes()).containsExactlyInAnyOrder(node2, node3, node4, node5);

            // Create second partition from the complementary partition
            final Partition partition2 = network.createNetworkPartition(node2, node3);

            // Verify we now have 3 partitions: {node0, node1}, {node2, node3}, {node4, node5}
            assertThat(network.networkPartitions()).hasSize(3);
            assertThat(remainingPartition.nodes()).containsExactlyInAnyOrder(node4, node5);
            assertThat(network.getNetworkPartitionContaining(node0)).isEqualTo(partition1);
            assertThat(network.getNetworkPartitionContaining(node1)).isEqualTo(partition1);
            assertThat(network.getNetworkPartitionContaining(node2)).isEqualTo(partition2);
            assertThat(network.getNetworkPartitionContaining(node3)).isEqualTo(partition2);
            assertThat(network.getNetworkPartitionContaining(node4)).isEqualTo(remainingPartition);
            assertThat(network.getNetworkPartitionContaining(node5)).isEqualTo(remainingPartition);

            // Verify all partitions are different
            assertThat(partition1).isNotEqualTo(partition2);
            assertThat(partition1).isNotEqualTo(remainingPartition);
            assertThat(partition2).isNotEqualTo(remainingPartition);
        } finally {
            env.destroy();
        }
    }

    /**
     * Test moving a node from one partition to another.
     */
    @Test
    void testMoveNodeBetweenPartitions() {
        final TestEnvironment env = new TurtleTestEnvironment();
        try {
            final Network network = env.network();
            final TimeManager timeManager = env.timeManager();

            // Setup network with 5 nodes
            final List<Node> nodes = network.addNodes(5);
            final Node node0 = nodes.get(0);
            final Node node1 = nodes.get(1);
            final Node node2 = nodes.get(2);
            final Node node3 = nodes.get(3);
            final Node node4 = nodes.get(4);

            network.start();

            // Wait for nodes to stabilize
            timeManager.waitFor(Duration.ofSeconds(5));

            // Create first partition
            final Partition partition1 = network.createNetworkPartition(node0, node1, node2);
            assertThat(partition1.nodes()).containsExactlyInAnyOrder(node0, node1, node2);
            assertThat(network.getNetworkPartitionContaining(node0)).isEqualTo(partition1);

            // Create second partition that includes node0 (should move it from partition1)
            final Partition partition2 = network.createNetworkPartition(node0, node3);

            // Verify node0 moved to partition2
            assertThat(partition2.nodes()).containsExactlyInAnyOrder(node0, node3);
            assertThat(network.getNetworkPartitionContaining(node0)).isEqualTo(partition2);
            assertThat(network.getNetworkPartitionContaining(node3)).isEqualTo(partition2);

            // Verify partition1 no longer contains node0
            assertThat(partition1.nodes()).containsExactlyInAnyOrder(node1, node2);
            assertThat(network.getNetworkPartitionContaining(node1)).isEqualTo(partition1);
            assertThat(network.getNetworkPartitionContaining(node2)).isEqualTo(partition1);

            // Verify node4 is in the remaining partition
            final Partition remainingPartition = network.getNetworkPartitionContaining(node4);
            assertThat(remainingPartition).isNotNull();
            assertThat(remainingPartition).isNotEqualTo(partition1);
            assertThat(remainingPartition).isNotEqualTo(partition2);
            assertThat(remainingPartition.nodes()).containsExactly(node4);
        } finally {
            env.destroy();
        }
    }

    /**
     * Test that creating a partition with all nodes throws an exception.
     */
    @Test
    void testCreatePartitionWithAllNodes() {
        final TestEnvironment env = new TurtleTestEnvironment();
        try {
            final Network network = env.network();
            final TimeManager timeManager = env.timeManager();

            // Setup network with 4 nodes
            final List<Node> nodes = network.addNodes(4);

            network.start();

            // Wait for nodes to stabilize
            timeManager.waitFor(Duration.ofSeconds(5));

            // Try to create a partition with all nodes - should throw exception
            assertThatThrownBy(() -> network.createNetworkPartition(nodes))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Cannot create a partition with all nodes");
        } finally {
            env.destroy();
        }
    }

    /**
     * Test that creating a partition with no nodes throws an exception.
     */
    @Test
    void testCreatePartitionWithNoNodes() {
        final TestEnvironment env = new TurtleTestEnvironment();
        try {
            final Network network = env.network();
            final TimeManager timeManager = env.timeManager();

            // Setup network with 4 nodes
            network.addNodes(4);

            network.start();

            // Wait for nodes to stabilize
            timeManager.waitFor(Duration.ofSeconds(5));

            // Try to create a partition with no nodes - should throw exception
            assertThatThrownBy(() -> network.createNetworkPartition(Collections.emptySet()))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Cannot create a partition with no nodes");
        } finally {
            env.destroy();
        }
    }

    /**
     * Test removing a partition that doesn't exist throws an exception.
     */
    @Test
    void testRemoveNonExistentPartition() {
        final TestEnvironment env = new TurtleTestEnvironment();
        try {
            final Network network = env.network();
            final TimeManager timeManager = env.timeManager();

            // Setup network with 4 nodes
            final List<Node> nodes = network.addNodes(4);

            network.start();

            // Wait for nodes to stabilize
            timeManager.waitFor(Duration.ofSeconds(5));

            // Create and then remove a partition
            final Partition partition = network.createNetworkPartition(nodes.get(0), nodes.get(1));
            network.removePartition(partition);

            // Try to remove the same partition again - should throw exception
            assertThatThrownBy(() -> network.removePartition(partition))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Partition does not exist in the network");
        } finally {
            env.destroy();
        }
    }

    /**
     * Test restoreConnectivity removes all partitions.
     */
    @Test
    void testRestoreConnectivityWithMultiplePartitions() {
        final TestEnvironment env = new TurtleTestEnvironment();
        try {
            final Network network = env.network();
            final TimeManager timeManager = env.timeManager();

            // Setup network with 6 nodes
            final List<Node> nodes = network.addNodes(6);
            final Node node0 = nodes.get(0);
            final Node node1 = nodes.get(1);
            final Node node2 = nodes.get(2);
            final Node node3 = nodes.get(3);
            final Node node4 = nodes.get(4);
            final Node node5 = nodes.get(5);

            network.start();

            // Wait for nodes to stabilize
            timeManager.waitFor(Duration.ofSeconds(5));

            // Create multiple partitions
            final Partition partition1 = network.createNetworkPartition(node0, node1);
            final Partition partition2 = network.createNetworkPartition(node2, node3);
            // This should leave nodes 4 and 5 in a third partition

            // Verify we have 3 partitions
            assertThat(network.networkPartitions()).hasSize(3);
            assertThat(network.getNetworkPartitionContaining(node0)).isEqualTo(partition1);
            assertThat(network.getNetworkPartitionContaining(node2)).isEqualTo(partition2);
            assertThat(network.getNetworkPartitionContaining(node4)).isNotNull();

            // Restore connectivity
            network.restoreConnectivity();

            // Verify all partitions are removed
            assertThat(network.networkPartitions()).isEmpty();
            assertThat(network.getNetworkPartitionContaining(node0)).isNull();
            assertThat(network.getNetworkPartitionContaining(node1)).isNull();
            assertThat(network.getNetworkPartitionContaining(node2)).isNull();
            assertThat(network.getNetworkPartitionContaining(node3)).isNull();
            assertThat(network.getNetworkPartitionContaining(node4)).isNull();
            assertThat(network.getNetworkPartitionContaining(node5)).isNull();

            timeManager.waitForCondition(network::allNodesAreActive, Duration.ofSeconds(120L));
        } finally {
            env.destroy();
        }
    }

    /**
     * Test partition behavior when removing one of two partitions.
     */
    @Test
    void testRemoveOneOfTwoPartitions() {
        final TestEnvironment env = new TurtleTestEnvironment();
        try {
            final Network network = env.network();
            final TimeManager timeManager = env.timeManager();

            // Setup network with 4 nodes
            final List<Node> nodes = network.addNodes(4);
            final Node node0 = nodes.get(0);
            final Node node1 = nodes.get(1);
            final Node node2 = nodes.get(2);
            final Node node3 = nodes.get(3);

            network.start();

            // Wait for nodes to stabilize
            timeManager.waitFor(Duration.ofSeconds(5));

            // Create a partition (this creates 2 partitions total)
            final Partition partition1 = network.createNetworkPartition(node0, node1);
            final Partition partition2 = network.getNetworkPartitionContaining(node2);

            // Verify we have 2 partitions
            assertThat(network.networkPartitions()).hasSize(2);
            assertThat(partition2).isNotNull();
            assertThat(partition2.nodes()).containsExactlyInAnyOrder(node2, node3);

            // Remove one partition - should clear all partitions per implementation
            network.removePartition(partition1);

            // Verify all partitions are cleared
            assertThat(network.networkPartitions()).isEmpty();
            assertThat(network.getNetworkPartitionContaining(node0)).isNull();
            assertThat(network.getNetworkPartitionContaining(node1)).isNull();
            assertThat(network.getNetworkPartitionContaining(node2)).isNull();
            assertThat(network.getNetworkPartitionContaining(node3)).isNull();

            timeManager.waitForCondition(network::allNodesAreActive, Duration.ofSeconds(120L));
        } finally {
            env.destroy();
        }
    }
}
