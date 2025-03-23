// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.network.topology;

import java.util.Set;
import org.hiero.consensus.model.node.NodeId;

/**
 * Holds information about the topology of the network
 */
public interface NetworkTopology {
    /**
     * Should this node be connecting to this peer?
     *
     * @param nodeId
     * 		the peer ID
     * @return true if this connection is in line with the network topology
     */
    boolean shouldConnectTo(NodeId nodeId);

    /**
     * Should this peer be connecting to this node?
     *
     * @param nodeId
     * 		the peer ID
     * @return true if this connection is in line with the network topology
     */
    boolean shouldConnectToMe(NodeId nodeId);

    /**
     * @return a Set of all peers this node should be connected to
     */
    Set<NodeId> getNeighbors();
}
