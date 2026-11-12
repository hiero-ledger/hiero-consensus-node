// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.network.simulation;

import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.Arrays;
import org.hiero.consensus.model.hashgraph.EventWindow;

/**
 * The event windows the nodes of a simulated network report.
 *
 * <p>A node's latest window determines which events are worth transmitting to it, and the oldest window across all
 * nodes determines what the network can forget. The windows reported since the previous tick are also held until they
 * can be put on the network; only the last window a node reports within a tick is kept, since the ones before it are
 * superseded and would be discarded on arrival anyway.
 *
 * <p>Nodes are identified by their rank, which is their index in the roster, and the windows are kept in arrays indexed
 * by rank.
 *
 * <p>Nodes report while they are running concurrently. Reporting is safe from several nodes in parallel, as long as each
 * reports only its own window, because then every element of the arrays has a single writer. Everything else is only
 * safe from a single thread while no node is reporting.
 */
final class NodeEventWindows {

    /**
     * The most recent event window reported by each node, indexed by rank.
     */
    private final EventWindow[] latest;

    /**
     * The event window reported by each node since the previous tick, indexed by rank, or {@code null} for a node that
     * has not reported one since it was last taken by {@link #takeNewlyReported(int)}. Until then it also serves as the
     * record of whether anything has changed since the last tick, which is what {@link #oldestIfChanged()} needs to
     * know.
     */
    private final EventWindow[] newlyReported;

    /**
     * Constructor. Gives every node the genesis event window, which it has until it reports one.
     *
     * @param nodeCount the number of nodes in the network; their ranks are {@code 0} to {@code nodeCount - 1}
     */
    NodeEventWindows(final int nodeCount) {
        latest = new EventWindow[nodeCount];
        Arrays.fill(latest, EventWindow.getGenesisEventWindow());
        newlyReported = new EventWindow[nodeCount];
    }

    /**
     * Records the latest event window of a node.
     *
     * @param rank        the rank of the node
     * @param eventWindow its latest event window
     */
    void report(final int rank, @NonNull final EventWindow eventWindow) {
        latest[rank] = eventWindow;
        newlyReported[rank] = eventWindow;
    }

    /**
     * Gets the latest event window of a node.
     *
     * @param rank the rank of the node
     * @return its latest event window
     */
    @NonNull
    EventWindow latest(final int rank) {
        return latest[rank];
    }

    /**
     * Takes the event window a node has reported since the previous tick, if any.
     *
     * @param rank the rank of the node
     * @return the event window, or {@code null} if the node has not reported one since it was last taken
     */
    @Nullable
    EventWindow takeNewlyReported(final int rank) {
        final EventWindow eventWindow = newlyReported[rank];
        newlyReported[rank] = null;
        return eventWindow;
    }

    /**
     * Finds the event window of the node that is furthest behind, if any node has reported a window since the
     * windows were last taken. Otherwise the oldest one cannot have moved.
     *
     * @return the event window with the lowest expired threshold, or {@code null} if nothing has changed or there are no
     *     nodes
     */
    @Nullable
    EventWindow oldestIfChanged() {
        if (!anyNewlyReported()) {
            return null;
        }
        EventWindow oldest = null;
        for (final EventWindow eventWindow : latest) {
            if (oldest == null || eventWindow.expiredThreshold() < oldest.expiredThreshold()) {
                oldest = eventWindow;
            }
        }
        return oldest;
    }

    /**
     * Reports whether any node has reported a window since the windows were last taken.
     *
     * @return {@code true} if a window has been reported
     */
    private boolean anyNewlyReported() {
        for (final EventWindow eventWindow : newlyReported) {
            if (eventWindow != null) {
                return true;
            }
        }
        return false;
    }
}
