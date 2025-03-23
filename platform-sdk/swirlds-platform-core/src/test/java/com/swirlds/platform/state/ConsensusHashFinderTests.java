// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.state;

import static com.swirlds.common.test.fixtures.RandomUtils.getRandomPrintSeed;
import static com.swirlds.common.utility.Threshold.MAJORITY;
import static com.swirlds.platform.state.iss.internal.ConsensusHashStatus.CATASTROPHIC_ISS;
import static com.swirlds.platform.state.iss.internal.ConsensusHashStatus.DECIDED;
import static com.swirlds.platform.state.iss.internal.ConsensusHashStatus.UNDECIDED;
import static com.swirlds.platform.test.fixtures.utils.EqualsVerifier.randomHash;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.swirlds.platform.metrics.IssMetrics;
import com.swirlds.platform.state.iss.internal.ConsensusHashFinder;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.stream.Collectors;
import org.hiero.consensus.model.crypto.Hash;
import org.hiero.consensus.model.node.NodeId;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mockito;

@DisplayName("ConsensusHashFinder Tests")
class ConsensusHashFinderTests {

    @BeforeEach
    void beforeEach() {}

    @AfterEach
    void afterEach() {}

    /**
     * A node to be added to a consensus hash builder.
     */
    private record NodeToAdd(@NonNull NodeId nodeId, long weight, @NonNull Hash stateHash) {}

    /**
     * A description of a desired partition
     */
    private record PartitionDescription(Hash hash, long totalWeight) {}

    /**
     * Generate a list of nodes for a given partition
     *
     * @param random      a source of randomness
     * @param firstNodeId the first node ID. Node IDs are generated sequentially starting with this node ID.
     * @param partition   a description of the partition
     */
    private List<NodeToAdd> getPartitionNodes(
            final Random random,
            final long averageWeight,
            final long standardDeviationWeight,
            final NodeId firstNodeId,
            final PartitionDescription partition) {
        final List<NodeToAdd> nodes = new ArrayList<>();
        if (partition.totalWeight > 0) {
            long remainingWeight = partition.totalWeight;
            NodeId nextNodeId = firstNodeId;

            while (remainingWeight > 0) {
                final long nextNodeWeight = Math.min(remainingWeight, Math.max(1L, (long)
                        (averageWeight + random.nextGaussian() * standardDeviationWeight)));

                nodes.add(new NodeToAdd(nextNodeId, nextNodeWeight, partition.hash));
                nextNodeId = NodeId.of(nextNodeId.id() + 1);
                remainingWeight -= nextNodeWeight;
            }
        }

        return nodes;
    }

    /**
     * Given a list of partitions, return a list of nodes from those partitions.
     *
     * @param random                  a source of randomness
     * @param averageWeight           the average weight per node
     * @param standardDeviationWeight the standard deviation of the weight per node
     * @param partitions              a list of partitions
     */
    private List<NodeToAdd> getNodes(
            final Random random,
            final long averageWeight,
            final long standardDeviationWeight,
            final List<PartitionDescription> partitions) {

        final List<NodeToAdd> nodes = new ArrayList<>();
        NodeId nextNodeId = NodeId.of(0);
        for (final PartitionDescription partition : partitions) {
            final List<NodeToAdd> partitionNodes =
                    getPartitionNodes(random, averageWeight, standardDeviationWeight, nextNodeId, partition);
            nextNodeId = NodeId.of(nextNodeId.id() + partitionNodes.size());
            nodes.addAll(partitionNodes);
        }
        return nodes;
    }

    @ParameterizedTest
    @ValueSource(longs = {1, 2, 3, 7, 8, 9, 10, 99, 100, 101, 999, 1000, 1001, 1024, 1025, Long.MAX_VALUE})
    @DisplayName("Single Partition Test")
    void singlePartitionTest(final long totalWeight) {

        final Random random = getRandomPrintSeed();
        final long averageWeight = totalWeight / 100;
        final long standardDeviationWeight = totalWeight / 200;
        final Hash hash = randomHash(random);

        final ConsensusHashFinder hashFinder = new ConsensusHashFinder(0, totalWeight, Mockito.mock(IssMetrics.class));
        assertEquals(totalWeight, hashFinder.getTotalWeight(), "unexpected total weight");
        assertEquals(0, hashFinder.getHashReportedWeight(), "no weight should be accumulated yet");
        assertEquals(0, hashFinder.getPartitionMap().size(), "there shouldn't be any partitions yet");

        // Add weight up until >1/2, but as soon as we meet or exceed 1/2 exit the loop
        NodeId nextNodeId = NodeId.of(0L);
        while (!MAJORITY.isSatisfiedBy(hashFinder.getHashReportedWeight(), totalWeight)) {
            assertEquals(UNDECIDED, hashFinder.getStatus(), "status should not yet be decided");

            final long nextNodeWeight =
                    (long) Math.max(1, averageWeight + random.nextGaussian() * standardDeviationWeight);
            hashFinder.addHash(nextNodeId, nextNodeWeight, hash);

            // adding the same node again should have no effect
            final long currentAccumulatedWeight = hashFinder.getHashReportedWeight();
            hashFinder.addHash(nextNodeId, nextNodeWeight, hash);
            assertEquals(currentAccumulatedWeight, hashFinder.getHashReportedWeight(), "duplicates should be no-ops");

            // adding the same node with a different hash should have no effect
            hashFinder.addHash(nextNodeId, nextNodeWeight, randomHash(random));
            assertEquals(currentAccumulatedWeight, hashFinder.getHashReportedWeight(), "duplicates should be no-ops");

            nextNodeId = NodeId.of(nextNodeId.id() + 1L);

            assertEquals(1, hashFinder.getPartitionMap().size(), "there should only be 1 partition");
            assertTrue(hashFinder.getPartitionMap().containsKey(hash), "invalid partition map");
            assertEquals(
                    nextNodeId.id(),
                    hashFinder.getPartitionMap().get(hash).getNodes().size(),
                    "incorrect partition size");
            assertTrue(
                    hashFinder.getPartitionMap().get(hash).getNodes().contains(NodeId.of(nextNodeId.id() - 1)),
                    "could not find node that was just added");
        }

        // At this point, we should have added enough weight to reach a threshold of >= 1/2
        assertEquals(DECIDED, hashFinder.getStatus(), "should now be decided");
        assertEquals(hash, hashFinder.getConsensusHash(), "incorrect hash");
    }

    @ParameterizedTest
    @ValueSource(longs = {99, 100, 101, 999, 1000, 1001, 1024, 1025, Long.MAX_VALUE})
    @DisplayName("Single Barely Valid Partition Test")
    void singleBarelyValidPartitionTest(final long totalWeight) {
        final Random random = getRandomPrintSeed();
        final long averageWeight = totalWeight / 100;
        final long standardDeviationWeight = totalWeight / 200;
        final Hash expectedConsensusHash = randomHash(random);

        final Set<NodeId> expectedDisagreeingNodes = new HashSet<>();

        final ConsensusHashFinder hashFinder = new ConsensusHashFinder(0, totalWeight, Mockito.mock(IssMetrics.class));

        long remainingWeight = totalWeight;

        final List<PartitionDescription> partitions = new LinkedList<>();

        // A partition with just enough weight to be complete
        final long weightInConsensusPartition = totalWeight / 2 + 1;
        remainingWeight -= weightInConsensusPartition;
        partitions.add(new PartitionDescription(expectedConsensusHash, weightInConsensusPartition));

        // Create a bunch of partitions that are incomplete
        while (remainingWeight > 0) {
            final long partitionWeight = Math.min(remainingWeight, totalWeight / 10);
            remainingWeight -= partitionWeight;
            partitions.add(new PartitionDescription(randomHash(random), partitionWeight));
        }

        // Add the nodes in a random order
        final List<NodeToAdd> nodes = getNodes(random, averageWeight, standardDeviationWeight, partitions);
        Collections.shuffle(nodes, random);

        for (final NodeToAdd nodeToAdd : nodes) {
            if (!nodeToAdd.stateHash().equals(expectedConsensusHash)) {
                expectedDisagreeingNodes.add(nodeToAdd.nodeId());
            }
            hashFinder.addHash(nodeToAdd.nodeId, nodeToAdd.weight, nodeToAdd.stateHash);
        }

        assertEquals(DECIDED, hashFinder.getStatus(), "should be decided by now");
        assertEquals(expectedConsensusHash, hashFinder.getConsensusHash(), "incorrect hash chosen");
        assertEquals(
                expectedDisagreeingNodes,
                hashFinder.getPartitionMap().entrySet().stream()
                        .filter(entry -> !entry.getKey().equals(expectedConsensusHash))
                        .flatMap(entry -> entry.getValue().getNodes().stream())
                        .collect(Collectors.toSet()),
                "disagreeing node set incorrect");
    }

    @ParameterizedTest
    @ValueSource(longs = {99, 100, 101, 999, 1000, 1001, 1024, 1025, Long.MAX_VALUE})
    @DisplayName("Almost Complete Partition Test")
    void almostCompletePartitionTest(final long totalWeight) {
        final Random random = getRandomPrintSeed();
        final long averageWeight = totalWeight / 100;
        final long standardDeviationWeight = totalWeight / 200;

        final ConsensusHashFinder hashFinder = new ConsensusHashFinder(0, totalWeight, Mockito.mock(IssMetrics.class));

        long remainingWeight = totalWeight;

        final List<PartitionDescription> partitions = new LinkedList<>();

        // A partition with almost enough weight to be complete
        final long weightInBigPartition = totalWeight / 2 - 1;
        remainingWeight -= weightInBigPartition;
        final Hash bigPartitionHash = randomHash(random);
        partitions.add(new PartitionDescription(bigPartitionHash, weightInBigPartition));

        // Create a bunch of smaller partitions that are incomplete
        while (remainingWeight > 0) {
            final long partitionWeight = Math.min(remainingWeight, totalWeight / 10);
            remainingWeight -= partitionWeight;
            partitions.add(new PartitionDescription(randomHash(random), partitionWeight));
        }

        // Add the nodes in a random order
        final List<NodeToAdd> nodes = getNodes(random, averageWeight, standardDeviationWeight, partitions);
        Collections.shuffle(nodes, random);

        for (final NodeToAdd nodeToAdd : nodes) {
            hashFinder.addHash(nodeToAdd.nodeId, nodeToAdd.weight, nodeToAdd.stateHash);
        }

        assertTrue(hashFinder.getPartitionMap().size() > 1, "there should be multiple partitions");
        assertEquals(CATASTROPHIC_ISS, hashFinder.getStatus(), "should have ISSed");
        assertNull(hashFinder.getConsensusHash(), "no consensus hash should have been chosen");
    }

    @ParameterizedTest
    @ValueSource(longs = {99, 100, 101, 999, 1000, 1001, 1024, 1025, Long.MAX_VALUE})
    @DisplayName("Lots Of Small Partitions Test")
    void lotsOfSmallPartitionsTest(final long totalWeight) {
        final Random random = getRandomPrintSeed();
        final long averageWeight = totalWeight / 100;
        final long standardDeviationWeight = totalWeight / 200;

        final ConsensusHashFinder hashFinder = new ConsensusHashFinder(0, totalWeight, Mockito.mock(IssMetrics.class));

        long remainingWeight = totalWeight;

        final List<PartitionDescription> partitions = new LinkedList<>();

        // Create a bunch of small partitions that are incomplete
        while (remainingWeight > 0) {
            final long partitionWeight = Math.min(remainingWeight, totalWeight / 10);
            remainingWeight -= partitionWeight;
            partitions.add(new PartitionDescription(randomHash(random), partitionWeight));
        }

        // Add the nodes in a random order
        final List<NodeToAdd> nodes = getNodes(random, averageWeight, standardDeviationWeight, partitions);
        Collections.shuffle(nodes, random);

        for (final NodeToAdd nodeToAdd : nodes) {
            hashFinder.addHash(nodeToAdd.nodeId, nodeToAdd.weight, nodeToAdd.stateHash);
        }

        assertTrue(hashFinder.getPartitionMap().size() > 1, "there should be multiple partitions");
        assertEquals(CATASTROPHIC_ISS, hashFinder.getStatus(), "should have ISSed");
        assertNull(hashFinder.getConsensusHash(), "no consensus hash should have been chosen");
    }

    @ParameterizedTest
    @ValueSource(longs = {99, 100, 101, 999, 1000, 1001, 1024, 1025, Long.MAX_VALUE})
    @DisplayName("Early ISS Detection Test")
    void earlyIssDetectionTest(final long totalWeight) {
        final Random random = getRandomPrintSeed();
        final long averageWeight = totalWeight / 100;
        final long standardDeviationWeight = totalWeight / 200;

        final ConsensusHashFinder hashFinder = new ConsensusHashFinder(0, totalWeight, Mockito.mock(IssMetrics.class));

        long remainingWeight = totalWeight;

        // A partition with almost enough weight to be complete
        final long weightInBigPartition = totalWeight / 2 - 1;
        remainingWeight -= weightInBigPartition;
        final PartitionDescription bigPartition = new PartitionDescription(randomHash(random), weightInBigPartition);

        // Create a bunch of partitions that are incomplete
        final List<PartitionDescription> smallPartitions = new LinkedList<>();
        while (remainingWeight > 0) {
            final long partitionWeight = Math.min(remainingWeight, totalWeight / 10);
            remainingWeight -= partitionWeight;
            smallPartitions.add(new PartitionDescription(randomHash(random), partitionWeight));
        }

        NodeId nextNodeId = NodeId.of(0);
        final List<NodeToAdd> nodes = new ArrayList<>();

        // Add the nodes from the small partitions
        for (final PartitionDescription partition : smallPartitions) {
            final List<NodeToAdd> partitionNodes =
                    getPartitionNodes(random, averageWeight, standardDeviationWeight, nextNodeId, partition);
            nextNodeId = NodeId.of(nextNodeId.id() + partitionNodes.size());
            nodes.addAll(partitionNodes);
        }

        // Add in the almost complete partition.
        nodes.addAll(getPartitionNodes(random, averageWeight, standardDeviationWeight, nextNodeId, bigPartition));

        // Now, feed in the nodes in order.
        for (final NodeToAdd nodeToAdd : nodes) {
            hashFinder.addHash(nodeToAdd.nodeId, nodeToAdd.weight, nodeToAdd.stateHash);
        }

        assertEquals(CATASTROPHIC_ISS, hashFinder.getStatus(), "should be an iss");
        assertNull(hashFinder.getConsensusHash(), "no consensus hash should have been chosen");
    }

    @ParameterizedTest
    @ValueSource(longs = {99, 100, 101, 999, 1000, 1001, 1024, 1025, Long.MAX_VALUE})
    @DisplayName("Complete Partition Is Last Test Test")
    void completePartitionIsLastTest(final long totalWeight) {
        final Random random = getRandomPrintSeed();
        final long averageWeight = totalWeight / 100;
        final long standardDeviationWeight = totalWeight / 200;
        final Hash expectedConsensusHash = randomHash(random);

        final Set<NodeId> expectedDisagreeingNodes = new HashSet<>();

        final ConsensusHashFinder hashFinder = new ConsensusHashFinder(0, totalWeight, Mockito.mock(IssMetrics.class));

        long remainingWeight = totalWeight;

        // A partition with enough weight to be complete
        final long weightInBigPartition = totalWeight / 2L + 1L;
        remainingWeight -= weightInBigPartition;
        final PartitionDescription bigPartition = new PartitionDescription(expectedConsensusHash, weightInBigPartition);

        // Create a bunch of partitions that are incomplete
        final List<PartitionDescription> smallPartitions = new LinkedList<>();
        while (remainingWeight > 0) {
            final long partitionWeight = Math.min(remainingWeight, totalWeight / 10);
            remainingWeight -= partitionWeight;
            smallPartitions.add(new PartitionDescription(randomHash(random), partitionWeight));
        }

        NodeId nextNodeId = NodeId.of(0);
        final List<NodeToAdd> nodes = new ArrayList<>();

        // Add the nodes from the small partitions
        for (final PartitionDescription partition : smallPartitions) {
            final List<NodeToAdd> partitionNodes =
                    getPartitionNodes(random, averageWeight, standardDeviationWeight, nextNodeId, partition);
            nextNodeId = NodeId.of(nextNodeId.id() + partitionNodes.size());
            nodes.addAll(partitionNodes);
        }

        // Add in the big partition
        nodes.addAll(getPartitionNodes(random, averageWeight, standardDeviationWeight, nextNodeId, bigPartition));

        // Now, feed in the nodes in order.
        for (final NodeToAdd nodeToAdd : nodes) {
            if (!nodeToAdd.stateHash().equals(expectedConsensusHash)) {
                expectedDisagreeingNodes.add(nodeToAdd.nodeId());
            }
            hashFinder.addHash(nodeToAdd.nodeId, nodeToAdd.weight, nodeToAdd.stateHash);
        }

        assertEquals(DECIDED, hashFinder.getStatus(), "should be decided by now");
        assertEquals(expectedConsensusHash, hashFinder.getConsensusHash(), "incorrect hash chosen");
        assertEquals(
                expectedDisagreeingNodes,
                hashFinder.getPartitionMap().entrySet().stream()
                        .filter(entry -> !entry.getKey().equals(expectedConsensusHash))
                        .flatMap(entry -> entry.getValue().getNodes().stream())
                        .collect(Collectors.toSet()),
                "disagreeing node set incorrect");
        assertTrue(hashFinder.hasDisagreement(), "should have a disagreement");
    }
}
