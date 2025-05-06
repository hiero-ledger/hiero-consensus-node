// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.gossip;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Set;
import org.hiero.consensus.model.node.NodeId;

public interface FallenBehindManager {
    /**
     * Notify the fallen behind manager that a node has reported that they don't have events we need. This means we have
     * probably fallen behind and will need to reconnect
     *
     * @param id
     * 		the id of the node who says we have fallen behind
     */
    void reportFallenBehind(@NonNull NodeId id);

    /**
     * We have determined that we have not fallen behind, or we have reconnected, so reset everything to the initial
     * state
     */
    void resetFallenBehind();

    /**
     * Have enough nodes reported that they don't have events we need, and that we have fallen behind?
     *
     * @return true if we have fallen behind, false otherwise
     */
    boolean hasFallenBehind();

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

    /**
     * Notify about changes in list of node ids we should be taking into account for falling behind
     * @param added node ids which were added from the roster
     * @param removed node ids which were removed from the roster
     */
    void addRemovePeers(@NonNull Set<NodeId> added, @NonNull Set<NodeId> removed);
}
