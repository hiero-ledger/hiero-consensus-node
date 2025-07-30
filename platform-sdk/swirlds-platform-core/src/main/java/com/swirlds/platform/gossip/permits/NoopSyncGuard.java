// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.gossip.permits;

import com.swirlds.platform.network.PeerInfo;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.List;
import org.hiero.consensus.model.node.NodeId;

/**
 * Empty implementation of fair sync selector, allowing every acquired and not depending on matching number of acquires
 * and releases.
 */
public class NoopSyncGuard implements SyncGuard {
    /**
     * {@inheritDoc}
     */
    @Override
    public boolean isSyncAllowed(@NonNull final NodeId nodeId) {
        return true;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void onForcedSync(@NonNull final NodeId nodeId) {
        // no-op
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void onSyncCompleted(@NonNull final NodeId nodeId) {
        // no-op
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void addRemovePeers(@NonNull final List<PeerInfo> added, @NonNull final List<PeerInfo> removed) {
        // no-op
    }
}
