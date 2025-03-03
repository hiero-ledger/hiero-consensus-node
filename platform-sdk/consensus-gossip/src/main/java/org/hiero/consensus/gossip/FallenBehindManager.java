// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.gossip;

import com.swirlds.common.platform.NodeId;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.List;

public interface FallenBehindManager {
    /**
     * Notify the fallen behind manager that a node has reported that they don't have events we need. This means we have
     * probably fallen behind and will need to reconnect
     *
     * @param id
     * 		the id of the node who says we have fallen behind
     */
    void reportFallenBehind(NodeId id);

    /**
     * We have determined that we have not fallen behind, or we have reconnected, so reset everything to the initial
     * state
     */
    void resetFallenBehind();

    /**
     * Returns a list of node IDs which need to be contacted to establish if we have fallen behind.
     *
     * @return a list of node IDs, or null if there is no indication we have fallen behind
     */
    @Nullable
    List<NodeId> getNeededForFallenBehind();

    /**
     * Have enough nodes reported that they don't have events we need, and that we have fallen behind?
     *
     * @return true if we have fallen behind, false otherwise
     */
    boolean hasFallenBehind();

    /**
     * Get a list of neighbors to call if we need to do a reconnect
     *
     * @return a list of neighbor IDs
     */
    @Nullable
    List<NodeId> getNeighborsForReconnect();

    /**
     * Should I attempt a reconnect with this neighbor?
     *
     * @param peerId
     * 		the ID of the neighbor
     * @return true if I should attempt a reconnect
     */
    boolean shouldReconnectFrom(@NonNull NodeId peerId);

    /**
     * @return the number of nodes that have told us we have fallen behind
     */
    int numReportedFallenBehind();
}
