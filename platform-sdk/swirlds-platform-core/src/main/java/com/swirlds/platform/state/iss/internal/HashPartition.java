// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.state.iss.internal;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import org.hiero.consensus.model.crypto.Hash;
import org.hiero.consensus.model.node.NodeId;

/**
 * A group of nodes in agreement about the hash for a particular round. If the network is ISS free then there should
 * be exactly one hash partition per round. If there has been an ISS then there will be multiple hash partitions.
 */
public class HashPartition {

    /**
     * The hash that this group of nodes agrees upon.
     */
    private final Hash hash;

    /**
     * The total weight of the nodes known to agree with a given hash.
     */
    private long totalWeight = 0;

    /**
     * The node IDs that are known to agree with this hash.
     */
    private final Set<NodeId> nodes = new HashSet<>();

    /**
     * Create an object that tracks a group of nodes that agree about the hash on the state.
     *
     * @param hash
     * 		the agreed upon hash
     */
    public HashPartition(final Hash hash) {
        this.hash = hash;
    }

    /**
     * Add hash information for a node.
     *
     * @param nodeId
     * 		the ID of the node
     * @param weight
     * 		the weight held by the node
     */
    public void addNodeHash(@NonNull final NodeId nodeId, final long weight) {
        Objects.requireNonNull(nodeId, "nodeId must not be null");
        final boolean added = nodes.add(nodeId);
        if (!added) {
            throw new IllegalStateException("node " + nodeId + " is already in the partition");
        }
        totalWeight += weight;
    }

    /**
     * Get the agreed upon hash.
     */
    public Hash getHash() {
        return hash;
    }

    /**
     * Get the total weight known to agree with this hash.
     */
    public long getTotalWeight() {
        return totalWeight;
    }

    /**
     * Get a set of nodes that are in agreement with this hash. This set should not be modified externally.
     */
    @NonNull
    public Set<NodeId> getNodes() {
        return nodes;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String toString() {
        final StringBuilder sb = new StringBuilder();
        sb.append("Partition weight =  ")
                .append(totalWeight)
                .append(", hash = ")
                .append(hash)
                .append(", nodes = ");
        for (final NodeId node : nodes) {
            sb.append(node).append(" ");
        }

        return sb.toString();
    }
}
