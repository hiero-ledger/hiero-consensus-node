// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.gossip.permits;

import com.swirlds.platform.network.PeerInfo;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.List;
import org.hiero.consensus.model.node.NodeId;

public class NoopFairSyncSelector implements FairSyncSelector {
    /**
     * {@inheritDoc}
     */
    @Override
    public boolean tryAcquire(@NonNull final NodeId nodeId) {
        return true;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void forceAcquire(@NonNull final NodeId nodeId) {
        // no-op
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void releaseIfAcquired(@NonNull final NodeId nodeId) {
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
