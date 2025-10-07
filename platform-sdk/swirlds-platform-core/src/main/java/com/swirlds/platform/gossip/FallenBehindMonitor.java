// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.gossip;

import static com.swirlds.metrics.api.Metrics.INTERNAL_CATEGORY;

import com.swirlds.common.merkle.synchronization.config.ReconnectConfig;
import com.swirlds.common.metrics.FunctionGauge;
import com.swirlds.metrics.api.Metrics;
import com.swirlds.platform.system.status.StatusActionSubmitter;
import com.swirlds.platform.system.status.actions.FallenBehindAction;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import org.hiero.consensus.model.node.NodeId;

/**
 * A thread-safe implementation for tracking fallen behind status
 */
public class FallenBehindMonitor {

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

    public FallenBehindMonitor(
            @NonNull final NodeId selfId,
            final int numNeighbors,
            @NonNull final StatusActionSubmitter statusActionSubmitter,
            @NonNull final ReconnectConfig config) {
        Objects.requireNonNull(selfId, "selfId");

        this.numNeighbors = numNeighbors;

        this.statusActionSubmitter = Objects.requireNonNull(statusActionSubmitter);
        this.config = Objects.requireNonNull(config, "config must not be null");
        final Metrics metrics = null; // TODO
        metrics.getOrCreate(
                new FunctionGauge.Config<>(INTERNAL_CATEGORY, "hasFallenBehind", Object.class, this::hasFallenBehind)
                        .withDescription("has this node fallen behind?"));
        metrics.getOrCreate(new FunctionGauge.Config<>(
                        INTERNAL_CATEGORY, "numReportFallenBehind", Integer.class, this::numReportedFallenBehind)
                .withDescription("the number of nodes that have fallen behind")
                .withUnit("count"));
    }

    /**
     * Notify the fallen behind manager that a node has reported that they don't have events we need. This means we have
     * probably fallen behind and will need to reconnect
     *
     * @param id the id of the node who says we have fallen behind
     */
    public synchronized void reportFallenBehind(@NonNull final NodeId id) {
        if (reportFallenBehind.add(id)) {
            checkAndNotifyFallingBehind();
        }
    }

    /**
     * Notify the fallen behind manager that a node has reported that node is providing us with events we need. This
     * means we are not in fallen behind state against that node.
     *
     * @param id the id of the node who is providing us with up to date events
     */
    public synchronized void clearFallenBehind(@NonNull final NodeId id) {
        reportFallenBehind.remove(id);
    }

    private void checkAndNotifyFallingBehind() {
        if (!previouslyFallenBehind && hasFallenBehind()) {
            statusActionSubmitter.submitStatusAction(new FallenBehindAction());
            previouslyFallenBehind = true;
        }
    }

    /**
     * Notify about changes in list of node ids we should be taking into account for falling behind
     *
     * @param added   node ids which were added from the roster
     * @param removed node ids which were removed from the roster
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

    /**
     * Have enough nodes reported that they don't have events we need, and that we have fallen behind?
     *
     * @return true if we have fallen behind, false otherwise
     */
    public synchronized boolean hasFallenBehind() {
        return numNeighbors * config.fallenBehindThreshold() < reportFallenBehind.size();
    }

    /**
     * Should I attempt a reconnect with this neighbor?
     *
     * @param peerId the ID of the neighbor
     * @return true if I should attempt a reconnect
     */
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
     * We have determined that we have not fallen behind, or we have reconnected, so reset everything to the initial
     * state
     */
    public synchronized void resetFallenBehind() {
        reportFallenBehind.clear();
        previouslyFallenBehind = false;
    }

    /**
     * @return the number of nodes that have told us we have fallen behind
     */
    public synchronized int numReportedFallenBehind() {
        return reportFallenBehind.size();
    }
}
