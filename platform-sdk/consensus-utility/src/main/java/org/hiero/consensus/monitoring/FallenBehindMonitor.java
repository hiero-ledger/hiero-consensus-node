// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.monitoring;

import static com.swirlds.metrics.api.Metrics.INTERNAL_CATEGORY;
import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.state.roster.Roster;
import com.swirlds.config.api.Configuration;
import com.swirlds.metrics.api.Metrics;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import javax.annotation.concurrent.GuardedBy;
import org.hiero.consensus.config.FallenBehindConfig;
import org.hiero.consensus.metrics.FunctionGauge;
import org.hiero.consensus.model.hashgraph.EventWindow;
import org.hiero.consensus.model.node.NodeId;

/**
 * Detects when this node has fallen behind the network.
 * <p>This monitor tracks reports from peer nodes by comparing event windows' ancient and
 * expired thresholds. When the number of reporting peers exceeds a configurable threshold (as a proportion of total
 * peers), all interested clients are notified.
 *
 * <p>The monitor can be queried if a particular peer reported this node as behind and it also provides
 * a blocking {@link #awaitFallenBehind()} method that suspends calling threads until the fallen-behind condition is
 * detected that allow interested parties to be notified without the need of polling for the information.
 */
public class FallenBehindMonitor {

    private final Lock lock = new ReentrantLock();
    private final Condition fallenBehindCondition = lock.newCondition();
    private final Condition gossipSyncPausedCondition = lock.newCondition();

    /**
     * the number of peers in the roster
     */
    private final int peersSize;

    /**
     * set of peers that reported this node has fallen behind
     */
    private final Set<NodeId> reportFallenBehind = new HashSet<>();

    @GuardedBy("lock")
    private final double fallenBehindThreshold;

    @GuardedBy("lock")
    private boolean isBehind;

    @GuardedBy("lock")
    private boolean pausedNotificationReceived;

    public FallenBehindMonitor(
            @NonNull final Roster roster, @NonNull final Configuration config, @NonNull final Metrics metrics) {
        this(
                requireNonNull(roster).rosterEntries().size() - 1,
                requireNonNull(config).getConfigData(FallenBehindConfig.class).fallenBehindThreshold());
        requireNonNull(metrics)
                .getOrCreate(new FunctionGauge.Config<>(
                                INTERNAL_CATEGORY, "hasFallenBehind", Object.class, this::hasFallenBehind)
                        .withDescription("has this node fallen behind?"));
        metrics.getOrCreate(new FunctionGauge.Config<>(
                        INTERNAL_CATEGORY, "numReportFallenBehind", Integer.class, this::reportedSize)
                .withDescription("the number of nodes that have fallen behind")
                .withUnit("count"));
    }

    public FallenBehindMonitor(final int peersSize, final double fallenBehindThreshold) {
        this.peersSize = peersSize;
        this.fallenBehindThreshold = fallenBehindThreshold;
    }

    private void checkAndNotify() {
        boolean wasNotBehind = !isBehind;
        // Fall behind if reports > threshold OR if all peers have reported (handles threshold = 1.0 edge case)
        isBehind = peersSize * fallenBehindThreshold < reportFallenBehind.size()
                || (peersSize > 0 && reportFallenBehind.size() == peersSize);
        if (wasNotBehind && isBehind) {
            fallenBehindCondition.signalAll(); // notify waiting threads
        }
    }

    /**
     * Notify the fallen behind manager that a node has reported that they don't have events we need. This means we have
     * probably fallen behind and will need to reconnect
     *
     * @param id the id of the node who says we have fallen behind
     */
    public void report(@NonNull final NodeId id) {
        lock.lock();
        try {
            if (reportFallenBehind.add(id)) {
                checkAndNotify();
            }
        } finally {
            lock.unlock();
        }
    }

    /**
     * Notify the fallen behind manager that a node has reported that node is providing us with events we need. This
     * means we are not in fallen behind state against that node.
     *
     * @param id the id of the node who is providing us with up to date events
     */
    void clear(@NonNull final NodeId id) {
        lock.lock();
        try {
            reportFallenBehind.remove(id);
            checkAndNotify();
        } finally {
            lock.unlock();
        }
    }

    /**
     * Have enough nodes reported that they don't have events we need, and that we have fallen behind?
     *
     * @return true if we have fallen behind, false otherwise
     */
    public boolean hasFallenBehind() {
        lock.lock();
        try {
            return isBehind;
        } finally {
            lock.unlock();
        }
    }

    /**
     * is the local node behind from the peer's perspective?
     *
     * @param peerId the ID of the peer
     * @return true if it was detected that the local node is behind
     */
    public boolean isBehindPeer(@NonNull final NodeId peerId) {
        lock.lock();
        try {
            // if this peer has told me I have fallen behind, I will reconnect with him
            return reportFallenBehind.contains(peerId);
        } finally {
            lock.unlock();
        }
    }

    /**
     * Clears the monitor initial state
     */
    public void clear() {
        lock.lock();
        try {
            reportFallenBehind.clear();
            isBehind = false;
        } finally {
            lock.unlock();
        }
    }

    /**
     * @return the number of nodes that have told us we have fallen behind
     */
    public int reportedSize() {
        lock.lock();
        try {
            return reportFallenBehind.size();
        } finally {
            lock.unlock();
        }
    }

    /**
     * This method blocks the thread until the monitor detects that the local node has fallen behind. It releases all
     * waiting threads as soon as the condition becomes true.
     */
    public void awaitFallenBehind() throws InterruptedException {
        lock.lock();
        try {
            while (!isBehind) {
                fallenBehindCondition.await();
            }
        } finally {
            lock.unlock();
        }
    }

    /**
     * Checks if we have fallen behind with respect to this peer and updates the internal status accordingly.
     *
     * @param self  local node event window
     * @param other peer's event window
     * @param peer  node id against which we have fallen behind
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

    /**
     * Inform thread listening on {@link #awaitGossipPaused()} that gossip was already fully paused. Supports notifying
     * only a single thread, but can be executed before, after or concurrently with the await call.
     */
    public void notifySyncProtocolPaused() {
        lock.lock();
        try {
            gossipSyncPausedCondition.signal();
            pausedNotificationReceived = true;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Blocks the thread until {@link #notifySyncProtocolPaused()} is called. Supports notification before, after or
     * concurrently with await, but each method has to be called only once from each side.
     *
     * @throws InterruptedException if the wait was interrupted
     */
    public void awaitGossipPaused() throws InterruptedException {
        lock.lock();
        try {
            while (!pausedNotificationReceived) {
                gossipSyncPausedCondition.await();
            }
            pausedNotificationReceived = false;
        } finally {
            lock.unlock();
        }
    }
}
