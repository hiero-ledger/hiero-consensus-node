// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.state.iss.internal;

import static com.swirlds.common.utility.Threshold.MAJORITY;

import com.swirlds.common.utility.Mnemonics;
import com.swirlds.platform.metrics.IssMetrics;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.hiero.base.crypto.Hash;
import org.hiero.consensus.model.node.NodeId;

/**
 * Tracks hash partitions for a particular round and determines the consensus hash.
 */
public class ConsensusHashFinder {

    /**
     * A map of known partitions.
     */
    private final Map<Hash, HashPartition> partitionMap = new HashMap<>();

    /**
     * Nodes that have already reported a hash for this round.
     */
    private final Set<NodeId> reportingNodes = new HashSet<>();

    /**
     * The total weight in the network.
     */
    private final long totalWeight;

    /**
     * The current round.
     */
    private final long round;

    private final IssMetrics issMetrics;

    /**
     * The total weight of nodes that have reported their hash for this round.
     */
    private long hashReportedWeight;

    /**
     * The partition with the largest weight.
     */
    private HashPartition largestPartition;

    /**
     * The current hash agreement status.
     */
    private ConsensusHashStatus status = ConsensusHashStatus.UNDECIDED;

    /**
     * The consensus hash. Null until known.
     */
    private Hash consensusHash;

    /**
     * Create a new object for tracking agreement on the hash of a particular round against the consensus hash
     *
     * @param round       the current round
     * @param totalWeight the total weight contained within the network for this round
     * @param issMetrics  iss related metrics
     */
    public ConsensusHashFinder(final long round, final long totalWeight, @NonNull final IssMetrics issMetrics) {
        this.round = round;
        this.totalWeight = totalWeight;
        this.issMetrics = Objects.requireNonNull(issMetrics);
    }

    /**
     * Get the current status of this round. Status will be {@link ConsensusHashStatus#UNDECIDED UNDECIDED}
     * until sufficient hashes are gathered, and will afterwards never change.
     *
     * @return the current status as determined by added hashes
     */
    public ConsensusHashStatus getStatus() {
        return status;
    }

    /**
     * The consensus hash of this round. Will be null if {@link #getStatus()} returns
     * {@link ConsensusHashStatus#UNDECIDED UNDECIDED} or
     * {@link  ConsensusHashStatus#CATASTROPHIC_ISS CATASTROPHIC_ISS}. If this node computed the same hash
     * as the (non-null) consensus hash then it is "right", otherwise it is "wrong".
     *
     * @return the consensus hash for this round, or null if a consensus hash does not exist or is not yet known
     */
    public Hash getConsensusHash() {
        return consensusHash;
    }

    /**
     * Add a hash reported by a node. If this method returns {@link ConsensusHashStatus#UNDECIDED} then not
     * enough data has been gathered to make a determination for this round. If not
     * {@link ConsensusHashStatus#UNDECIDED} then the status is known and action can be taken.
     *
     * @param nodeId
     * 		the node that provided the hash
     * @param nodeWeight
     * 		the weight of the node
     * @param stateHash
     * 		the hash reported by the node
     */
    public void addHash(@NonNull final NodeId nodeId, final long nodeWeight, @NonNull final Hash stateHash) {
        Objects.requireNonNull(nodeId, "nodeId must not be null");
        Objects.requireNonNull(stateHash, "stateHash must not be null");
        if (!reportingNodes.add(nodeId)) {
            // Prevent the same node from reporting multiple times in the same round.
            return;
        }

        // Find the correct partition, or create a new one if needed.
        final HashPartition partition;
        if (partitionMap.containsKey(stateHash)) {
            partition = partitionMap.get(stateHash);
        } else {
            partition = new HashPartition(stateHash);
            partitionMap.put(stateHash, partition);
        }

        // Add the node to the partition
        partition.addNodeHash(nodeId, nodeWeight);
        hashReportedWeight += nodeWeight;
        if (largestPartition == null || partition.getTotalWeight() > largestPartition.getTotalWeight()) {
            largestPartition = partition;
        }

        if (status != ConsensusHashStatus.UNDECIDED) {
            sendHashValidityDispatch(nodeId, stateHash);
            // Once we know the status, the status never changes.
            return;
        }

        // Now, check and see if we are capable of making a decision
        if (MAJORITY.isSatisfiedBy(largestPartition.getTotalWeight(), totalWeight)) {
            // There exists a partition with a quorum.
            consensusHash = largestPartition.getHash();
            status = ConsensusHashStatus.DECIDED;
            sendHashValidityDispatchForAllNodes();
        } else {
            final long remainingWeight = totalWeight - hashReportedWeight;
            if (!MAJORITY.isSatisfiedBy(largestPartition.getTotalWeight() + remainingWeight, totalWeight)) {
                // There exists no partition with quorum, and there will never exist a partition with a quorum.
                // Heaven help us.
                status = ConsensusHashStatus.CATASTROPHIC_ISS;
                issMetrics.catastrophicIssObserver(round);
            }
        }
    }

    /**
     * Check to see if this node agrees with the consensus hash. If it does not agree then send a dispatch.
     *
     * @param nodeId
     * 		the ID of the node that disagrees with the consensus hash
     * @param stateHash
     * 		the wrong hash derived by the node
     */
    private void sendHashValidityDispatch(@NonNull final NodeId nodeId, @NonNull final Hash stateHash) {
        Objects.requireNonNull(nodeId, "nodeId must not be null");
        Objects.requireNonNull(stateHash, "stateHash must not be null");
        if (consensusHash != null) {
            issMetrics.stateHashValidityObserver(round, nodeId, stateHash, consensusHash);
        }
    }

    /**
     * For all nodes that have already reported, for every node that disagrees with the consensus hash send a dispatch.
     */
    private void sendHashValidityDispatchForAllNodes() {
        for (final HashPartition partition : partitionMap.values()) {
            for (final NodeId nodeId : partition.getNodes()) {
                sendHashValidityDispatch(nodeId, partition.getHash());
            }
        }
    }

    /**
     * Get a map of known partitions.
     */
    public Map<Hash, HashPartition> getPartitionMap() {
        return partitionMap;
    }

    /**
     * @return true if there is any disagreement between nodes on the hash for this round
     */
    public boolean hasDisagreement() {
        return partitionMap.size() > 1;
    }

    /**
     * Get the total weight in the network.
     */
    public long getTotalWeight() {
        return totalWeight;
    }

    /**
     * Get the amount of weight of nodes that have submitted a hash for this round.
     */
    public long getHashReportedWeight() {
        return hashReportedWeight;
    }

    /**
     * Write information about hash partitions to a string builder.
     */
    public void writePartitionData(final StringBuilder sb) {
        sb.append("Nodes holding ")
                .append(hashReportedWeight)
                .append("/")
                .append(totalWeight)
                .append(" (")
                .append((float) hashReportedWeight / totalWeight * 100)
                .append("%) of total weight have reported a hash for round ")
                .append(round)
                .append(".\n");

        if (consensusHash != null) {
            sb.append("Consensus mnemonic: ")
                    .append(Mnemonics.generateMnemonic(consensusHash))
                    .append("\n");
            sb.append("Consensus hash: ").append(consensusHash).append("\n");
        }

        final List<HashPartition> partitions = new ArrayList<>(partitionMap.size());
        partitions.addAll(partitionMap.values());
        // Sort from highest weight to lowest weight
        partitions.sort((a, b) -> (int) (b.getTotalWeight() - a.getTotalWeight()));

        for (final HashPartition partition : partitions) {
            sb.append("- node");
            if (partition.getNodes().size() != 1) {
                sb.append("s");
            }

            final List<NodeId> nodes = new ArrayList<>(partition.getNodes().size());
            nodes.addAll(partition.getNodes());
            Collections.sort(nodes);

            boolean first = true;
            for (final NodeId node : nodes) {
                if (first) {
                    sb.append(" ");
                    first = false;
                } else {
                    sb.append(", ");
                }
                sb.append(node);
            }
            sb.append("\n");

            final long partitionWeight = partition.getTotalWeight();

            sb.append("  partition weight: ")
                    .append(partitionWeight)
                    .append(" (")
                    .append((float) partitionWeight / totalWeight * 100)
                    .append("% of total weight)\n");
            sb.append("  partition mnemonic: ")
                    .append(Mnemonics.generateMnemonic(partition.getHash()))
                    .append("\n");
            sb.append("  partition hash: ").append(partition.getHash()).append("\n");
        }
    }
}
