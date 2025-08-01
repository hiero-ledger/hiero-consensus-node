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

/**
 * Fair sync selector based on the least recently used mechanic. It will reject acquires for node ids which are not in
 * the set of <i>maxConcurrentSyncs</i> least recently used ids. It has additional setting to bootstrap the selector -
 * unless at least <i>minimalRoundRobinSize</i> syncs against different node ids were done, it will reject any already
 * used peers.
 * <br>
 * Suggestion is to set minimalRoundRobinSize to not more than 1/3 of the connected nodes.
 */
public class LruSyncGuard implements SyncGuard {

    private static final Logger logger = LogManager.getLogger(LruSyncGuard.class);

    private final int maxConcurrentSyncs;
    private final int minimalRoundRobinSize;

    /**
     * History of recent synchronizations; latest synchronizations are at the end of the list, so index 0 is oldest
     * synchronization
     */
    private final List<NodeId> recentSyncs = new ArrayList<>();

    /**
     * Synchronizations which are currently in progress
     */
    private final Set<NodeId> syncsInProgress = new HashSet<>();

    /**
     * Constructs new LruFairSyncSelector
     *
     * @param maxConcurrentSyncs    maximum number of concurrent synchronizations, after that limit tryAcquire will
     *                              fail
     * @param minimalRoundRobinSize minimum amount of finished synchronizations before nodeId reuse is considered
     */
    public LruSyncGuard(final int maxConcurrentSyncs, final int minimalRoundRobinSize) {
        this.maxConcurrentSyncs = maxConcurrentSyncs;
        this.minimalRoundRobinSize = minimalRoundRobinSize;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public synchronized boolean isSyncAllowed(@NonNull final NodeId nodeId) {

        // if more than allowed number of concurrent syncs is running, reject
        if (syncsInProgress.size() >= maxConcurrentSyncs) {
            return false;
        }

        // if we are already syncing against that node, we have an error
        checkNotInProgress(nodeId);

        // if that node was never synchronized againsts, accept
        final int index = recentSyncs.indexOf(nodeId);
        if (index < 0) {
            syncsInProgress.add(nodeId);
            return true;
        }

        // if we have synchronized against very limited amount of nodes, reject
        if (recentSyncs.size() < minimalRoundRobinSize) {
            return false;
        }

        // if node in question was synced reasonably far in the past, accept
        if (index < maxConcurrentSyncs) {
            syncsInProgress.add(nodeId);
            return true;
        }

        // otherwise reject
        return false;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public synchronized void onForcedSync(@NonNull final NodeId nodeId) {
        checkNotInProgress(nodeId);
        syncsInProgress.add(nodeId);
    }

    private void checkNotInProgress(final NodeId nodeId) {
        if (syncsInProgress.contains(nodeId)) {
            logger.error(SYNC_INFO.getMarker(), "Node " + nodeId + " already has a sync selector permit.");
            throw new IllegalStateException("Node " + nodeId + " already has a sync selector permit.");
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public synchronized void onSyncCompleted(@NonNull final NodeId nodeId) {
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
