// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.network.simulation;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.hiero.consensus.model.node.NodeId;

/**
 * The nodes of a simulated network and their ranks. A node's rank is its position in node id order, so walking the
 * ranks in order visits the nodes in node id order. The simulation keeps its per-node and per-connection state in
 * arrays indexed by rank.
 */
final class NodeRanks {

    /**
     * The ranks of a network that has not been set up yet, which has no nodes.
     */
    static final NodeRanks NONE = new NodeRanks(List.of());

    /**
     * Every node in node id order. A node's position in this list is its rank.
     */
    private final List<NodeId> nodeIds;

    /**
     * The rank of every node, which is its position in {@link #nodeIds}.
     */
    private final Map<NodeId, Integer> ranks = new HashMap<>();

    /**
     * Constructor.
     *
     * @param nodeIds the ids of all nodes of the network, in any order and without duplicates
     */
    NodeRanks(@NonNull final Collection<NodeId> nodeIds) {
        this.nodeIds = nodeIds.stream().sorted().toList();
        for (int rank = 0; rank < this.nodeIds.size(); rank++) {
            ranks.put(this.nodeIds.get(rank), rank);
        }
    }

    /**
     * @return the number of nodes
     */
    int size() {
        return nodeIds.size();
    }

    /**
     * @return every node in node id order, which is rank order
     */
    @NonNull
    List<NodeId> nodeIds() {
        return nodeIds;
    }

    /**
     * Gets the node of a rank.
     *
     * @param rank the rank of the node
     * @return the id of the node
     */
    @NonNull
    NodeId idOf(final int rank) {
        return nodeIds.get(rank);
    }

    /**
     * Gets the rank of a node.
     *
     * @param nodeId the id of the node
     * @return the rank of the node
     * @throws IllegalArgumentException if the node is not part of the network
     */
    int rankOf(@NonNull final NodeId nodeId) {
        final Integer rank = ranks.get(nodeId);
        if (rank == null) {
            throw new IllegalArgumentException("Node " + nodeId + " is not part of the network");
        }
        return rank;
    }
}
