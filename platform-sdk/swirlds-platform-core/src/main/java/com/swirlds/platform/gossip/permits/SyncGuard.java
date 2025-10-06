// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.gossip.permits;

import com.swirlds.platform.network.PeerInfo;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.List;
import org.hiero.consensus.model.node.NodeId;

/**
 * Control for making sure that in case of limited amount of concurrent syncs we are not synchronizing with the same
 * peers over and over. Exact implementation depends on subclass, but general gist is that {@link #isSyncAllowed(NodeId)}
 * calls will fail (return false) if called with recently acquired and released nodeId until enough other nodes are
 * acquired and released beforehand.
 */
public interface SyncGuard {

    /**
     * See if given node should be synchronized with, depending on number of concurrent syncs and preference of not
     * synchronizing with recent peers
     *
     * @param nodeId peer against which we consider synchronization
     * @return true if it is ok to synchronized against that node, false if it shouldn't be synchronized against for any
     * reason
     */
    boolean isSyncAllowed(@NonNull NodeId nodeId);

    /**
     * Called when remote node is forcing synchronization on local node. At that point, all checks are bypassed and
     * remote node will be marked as recently synchronized to avoid initiatining synchronization by ourselves
     *
     * @param nodeId peer which forced synchronization on local node
     */
    void onForcedSync(@NonNull final NodeId nodeId);

    /**
     * Indicate that synchronization has finished and node should be put as most recently synchronized against. Is safe
     * to be called even if corresponding acquire was not called beforehand.
     *
     * @param nodeId peer which has finished synchronization
     */
    void onSyncCompleted(@NonNull final NodeId nodeId);

    /**
     * Indicate that amount of peers have changed. Should NOT be called with removal of node against which
     * synchronization is currently being performed, will result in error.
     *
     * @param added   peers which have been added to roster
     * @param removed peers which have been removed from roster
     */
    void addRemovePeers(@NonNull final List<PeerInfo> added, @NonNull final List<PeerInfo> removed);
}
