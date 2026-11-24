// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.network.simulation;

import edu.umd.cs.findbugs.annotations.NonNull;
import org.hiero.consensus.model.node.NodeId;

/**
 * The state of a simulated network that a {@link NetworkObserver} reads, without changing it. Implemented by
 * {@link SimulatedNetworkConnectivity}.
 *
 * <p>Nodes are identified by their rank, which is their index in the roster.
 */
public interface NetworkView {

    /**
     * @param nodeId the id of a node
     * @return the rank of the node, or {@code -1} if it is not part of the network
     */
    int rankOf(@NonNull NodeId nodeId);
}
