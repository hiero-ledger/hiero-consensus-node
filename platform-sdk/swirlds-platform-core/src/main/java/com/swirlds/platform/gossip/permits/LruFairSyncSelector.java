// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.gossip.permits;

import static com.swirlds.logging.legacy.LogMarker.SYNC_INFO;

import com.swirlds.platform.network.PeerInfo;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hiero.consensus.model.node.NodeId;

public class LruFairSyncSelector implements FairSyncSelector {

    private static final Logger logger = LogManager.getLogger(FairSyncSelector.class);

    private final int maxConcurrentSyncs;
    private final int minimalRoundRobinSize;
    private final List<NodeId> recentSyncs = new ArrayList<>();
    private final Set<NodeId> syncsInProgress = new HashSet<>();

    public LruFairSyncSelector(final int maxConcurrentSyncs, final int minimalRoundRobinSize) {
        this.maxConcurrentSyncs = maxConcurrentSyncs;
        this.minimalRoundRobinSize = minimalRoundRobinSize;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public synchronized boolean tryAcquire(@NonNull final NodeId nodeId) {
        if (syncsInProgress.size() >= maxConcurrentSyncs) {
            return false;
        }

        if (syncsInProgress.contains(nodeId)) {
            logger.error(SYNC_INFO.getMarker(), "Node " + nodeId + " already has a sync selector permit.");
            throw new IllegalStateException("Node " + nodeId + " already has a sync selector permit.");
        }

        final int index = recentSyncs.indexOf(nodeId);
        if (index < 0) {
            syncsInProgress.add(nodeId);
            return true;
        }

        if (recentSyncs.size() < minimalRoundRobinSize) {
            return false;
        }

        if (index < maxConcurrentSyncs) {
            syncsInProgress.add(nodeId);
            return true;
        }
        return false;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public synchronized void forceAcquire(@NonNull final NodeId nodeId) {
        if (syncsInProgress.contains(nodeId)) {
            logger.error(SYNC_INFO.getMarker(), "Node " + nodeId + " already has a sync selector permit.");
            throw new IllegalStateException("Node " + nodeId + " already has a sync selector permit.");
        }
        syncsInProgress.add(nodeId);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public synchronized void releaseIfAcquired(@NonNull final NodeId nodeId) {
        // we don't care if it is there, possibly no-op
        syncsInProgress.remove(nodeId);
        recentSyncs.remove(nodeId);

        // but we mark it as recently synced, as it might have been invoked by remote side
        recentSyncs.add(nodeId);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public synchronized void addRemovePeers(
            @NonNull final List<PeerInfo> added, @NonNull final List<PeerInfo> removed) {
        // TODO: possibly update maxConcurrentSyncs and minimalRoundRobinSize? should it be controlled by caller, or
        // based on original config parameters?
        removed.forEach(peerInfo -> {
            recentSyncs.remove(peerInfo.nodeId());
            if (syncsInProgress.contains(peerInfo.nodeId())) {
                logger.error(
                        SYNC_INFO.getMarker(),
                        "Connection against {} still in sync, while getting removed from syn permits!",
                        peerInfo.nodeId());
            }
        });
    }
}
