// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.gossip;

import com.swirlds.common.merkle.synchronization.config.ReconnectConfig;
import com.swirlds.platform.system.status.StatusActionSubmitter;
import com.swirlds.platform.system.status.actions.FallenBehindAction;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import org.hiero.consensus.gossip.FallenBehindManager;
import org.hiero.consensus.model.node.NodeId;

/**
 * A thread-safe implementation of {@link FallenBehindManager}
 */
public class FallenBehindManagerImpl implements FallenBehindManager {

    /**
     * the number of neighbors we have
     */
    private int numNeighbors;

    /**
     * set of neighbors who report that this node has fallen behind
     */
    private final Set<NodeId> reportFallenBehind = new HashSet<>();

    /**
     * Enables submitting platform status actions
     */
    private final StatusActionSubmitter statusActionSubmitter;

    private final ReconnectConfig config;
    private boolean previouslyFallenBehind;

    public FallenBehindManagerImpl(
            @NonNull final NodeId selfId,
            final int numNeighbors,
            @NonNull final StatusActionSubmitter statusActionSubmitter,
            @NonNull final ReconnectConfig config) {
        Objects.requireNonNull(selfId, "selfId");

        this.numNeighbors = numNeighbors;

        this.statusActionSubmitter = Objects.requireNonNull(statusActionSubmitter);
        this.config = Objects.requireNonNull(config, "config must not be null");
    }

    @Override
    public synchronized void reportFallenBehind(@NonNull final NodeId id) {
        if (reportFallenBehind.add(id)) {
            checkAndNotifyFallingBehind();
        }
    }

    private void checkAndNotifyFallingBehind() {
        if (!previouslyFallenBehind && hasFallenBehind()) {
            statusActionSubmitter.submitStatusAction(new FallenBehindAction());
            previouslyFallenBehind = true;
        }
    }

    /**
     * {@inheritDoc}
     */
    public synchronized void addRemovePeers(@NonNull final Set<NodeId> added, @NonNull final Set<NodeId> removed) {
        Objects.requireNonNull(added);
        Objects.requireNonNull(removed);

        numNeighbors += added.size() - removed.size();
        for (final NodeId nodeId : removed) {
            if (reportFallenBehind.contains(nodeId) && !added.contains(nodeId)) {
                reportFallenBehind.remove(nodeId);
            }
        }
        checkAndNotifyFallingBehind();
    }

    @Override
    public synchronized boolean hasFallenBehind() {
        return numNeighbors * config.fallenBehindThreshold() < reportFallenBehind.size();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean shouldReconnectFrom(@NonNull final NodeId peerId) {
        if (!hasFallenBehind()) {
            return false;
        }
        synchronized (this) {
            // if this neighbor has told me I have fallen behind, I will reconnect with him
            return reportFallenBehind.contains(peerId);
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public synchronized void resetFallenBehind() {
        reportFallenBehind.clear();
        previouslyFallenBehind = false;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public synchronized int numReportedFallenBehind() {
        return reportFallenBehind.size();
    }
}
