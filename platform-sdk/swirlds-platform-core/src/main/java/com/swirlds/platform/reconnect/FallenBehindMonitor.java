// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.reconnect;

import static com.swirlds.metrics.api.Metrics.INTERNAL_CATEGORY;
import static java.util.Objects.requireNonNull;

import com.google.common.annotations.VisibleForTesting;
import com.hedera.hapi.node.state.roster.Roster;
import com.swirlds.common.merkle.synchronization.config.ReconnectConfig;
import com.swirlds.common.metrics.FunctionGauge;
import com.swirlds.config.api.Configuration;
import com.swirlds.metrics.api.Metrics;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.HashSet;
import java.util.Set;
import org.hiero.consensus.model.hashgraph.EventWindow;
import org.hiero.consensus.model.node.NodeId;

/**
 * Detects when this node has fallen behind the network.
 * <p>This monitor tracks reports from peer nodes by comparing event windows' ancient and
 * expired thresholds. When the number of reporting peers exceeds a configurable threshold
 * (as a proportion of total peers), all interested clients are notified.
 *
 * <p>The monitor can be queried if a particular peer reported this node as behind and it also provides
 * a blocking {@link #awaitFallenBehind()} method that suspends calling threads until the fallen-behind
 * condition is detected that allow interested parties to be notified without the need of polling for the information.
 */
public class FallenBehindMonitor {

    /**
     * the number of peers in the roster
     */
    private int peersSize;

    /**
     * set of peers that reported this node has fallen behind
     */
    private final Set<NodeId> reportFallenBehind = new HashSet<>();

    private final double fallenBehindThreshold;
    private boolean isBehind;

    public FallenBehindMonitor(
            @NonNull final Roster roster, @NonNull final Configuration config, @NonNull final Metrics metrics) {
        this(
                requireNonNull(roster).rosterEntries().size() - 1,
                requireNonNull(config).getConfigData(ReconnectConfig.class).fallenBehindThreshold());
        requireNonNull(metrics)
                .getOrCreate(new FunctionGauge.Config<>(
                                INTERNAL_CATEGORY, "hasFallenBehind", Object.class, this::hasFallenBehind)
                        .withDescription("has this node fallen behind?"));
        metrics.getOrCreate(new FunctionGauge.Config<>(
                        INTERNAL_CATEGORY, "numReportFallenBehind", Integer.class, this::reportedSize)
                .withDescription("the number of nodes that have fallen behind")
                .withUnit("count"));
    }

    @VisibleForTesting
    public FallenBehindMonitor(int peersSize, final double fallenBehindThreshold) {
        this.peersSize = peersSize;
        this.fallenBehindThreshold = fallenBehindThreshold;
    }

    private void checkAndNotify() {
        boolean wasNotBehind = !isBehind;
        // Fall behind if reports > threshold OR if all peers have reported (handles threshold = 1.0 edge case)
        isBehind = peersSize * fallenBehindThreshold < reportFallenBehind.size()
                || (peersSize > 0 && reportFallenBehind.size() == peersSize);
        if (wasNotBehind && isBehind) {
            notifyAll(); // notify waiting threads
        }
    }

    /**
     * Notify the fallen behind manager that a node has reported that they don't have events we need. This means we have
     * probably fallen behind and will need to reconnect
     *
     * @param id the id of the node who says we have fallen behind
     */
    synchronized void report(@NonNull final NodeId id) {
        if (reportFallenBehind.add(id)) {
            checkAndNotify();
        }
    }

    /**
     * Notify the fallen behind manager that a node has reported that node is providing us with events we need. This
     * means we are not in fallen behind state against that node.
     *
     * @param id the id of the node who is providing us with up to date events
     */
    synchronized void clear(@NonNull final NodeId id) {
        reportFallenBehind.remove(id);
        checkAndNotify();
    }

    /**
     * Notify about changes in list of node ids we should be taking into account for falling behind
     *
     * @param added   node ids which were added from the roster
     * @param removed node ids which were removed from the roster
     */
    public synchronized void update(@NonNull final Set<NodeId> added, @NonNull final Set<NodeId> removed) {
        requireNonNull(added);
        requireNonNull(removed);

        peersSize += added.size() - removed.size();
        for (final NodeId nodeId : removed) {
            if (reportFallenBehind.contains(nodeId) && !added.contains(nodeId)) {
                reportFallenBehind.remove(nodeId);
            }
        }
        checkAndNotify();
    }

    /**
     * Have enough nodes reported that they don't have events we need, and that we have fallen behind?
     *
     * @return true if we have fallen behind, false otherwise
     */
    public synchronized boolean hasFallenBehind() {
        return isBehind;
    }

    /**
     * Should I attempt a reconnect with this neighbor?
     *
     * @param peerId the ID of the neighbor
     * @return true if I should attempt a reconnect
     */
    public boolean wasReportedByPeer(@NonNull final NodeId peerId) {
        synchronized (this) {
            // if this neighbor has told me I have fallen behind, I will reconnect with him
            return reportFallenBehind.contains(peerId);
        }
    }

    /**
     * We have determined that we have not fallen behind, or we have reconnected, so reset everything to the initial
     * state
     */
    public synchronized void reset() {
        reportFallenBehind.clear();
        isBehind = false;
    }

    /**
     * @return the number of nodes that have told us we have fallen behind
     */
    public synchronized int reportedSize() {
        return reportFallenBehind.size();
    }

    public void awaitFallenBehind() throws InterruptedException {
        synchronized (this) {
            while (!isBehind) {
                wait();
            }
        }
    }

    /**
     * checks if we have fallen behind with respect to this peer.
     *
     * @param self                            our event window
     * @param other                           their event window
     * @param peer                          node id against which we have fallen behind
     * @return status about who has fallen behind
     */
    public FallenBehindStatus check(
            @NonNull final EventWindow self, @NonNull final EventWindow other, @NonNull final NodeId peer) {
        requireNonNull(self);
        requireNonNull(other);
        requireNonNull(peer);

        final FallenBehindStatus status = FallenBehindStatus.getStatus(self, other);
        if (status == FallenBehindStatus.SELF_FALLEN_BEHIND) {
            report(peer);
        } else {
            clear(peer);
        }
        return status;
    }
}
