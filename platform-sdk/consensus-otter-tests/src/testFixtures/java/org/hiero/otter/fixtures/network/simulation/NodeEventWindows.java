// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.network.simulation;

import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.Collection;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.hiero.consensus.model.hashgraph.EventWindow;
import org.hiero.consensus.model.node.NodeId;

/**
 * The event windows the nodes of a simulated network report.
 *
 * <p>A node's latest window determines which events are worth transmitting to it, and the oldest window across all
 * nodes determines what the network can forget. The windows reported since the previous tick are also held until they
 * can be put on the network; only the last window a node reports within a tick is kept, since the ones before it are
 * superseded and would be discarded on arrival anyway.
 *
 * <p>Reporting is safe from several nodes in parallel.
 */
final class NodeEventWindows {

    /**
     * The most recent event window reported by each node.
     */
    private final Map<NodeId, EventWindow> latest = new ConcurrentHashMap<>();

    /**
     * The event windows reported by each node since the previous tick, until they are taken by
     * {@link #takeNewlyReported(NodeId)}. Until then it also serves as the record of whether anything has changed
     * since the last tick, which is what {@link #oldestIfChanged()} needs to know.
     */
    private final Map<NodeId, EventWindow> newlyReported = new ConcurrentHashMap<>();

    /**
     * Constructor. Gives every node the genesis event window, which it has until it reports one.
     *
     * @param nodeIds the nodes of the network
     */
    NodeEventWindows(@NonNull final Collection<NodeId> nodeIds) {
        for (final NodeId nodeId : nodeIds) {
            latest.put(nodeId, EventWindow.getGenesisEventWindow());
        }
    }

    /**
     * Records the latest event window of a node.
     *
     * @param nodeId      the node
     * @param eventWindow its latest event window
     */
    void report(@NonNull final NodeId nodeId, @NonNull final EventWindow eventWindow) {
        latest.put(nodeId, eventWindow);
        newlyReported.put(nodeId, eventWindow);
    }

    /**
     * Gets the latest event window of a node.
     *
     * @param nodeId the node
     * @return its latest event window, or {@code null} if the node is not part of the network
     */
    @Nullable
    EventWindow latest(@NonNull final NodeId nodeId) {
        return latest.get(nodeId);
    }

    /**
     * Takes the event window a node has reported since the previous tick, if any.
     *
     * @param nodeId the node
     * @return the event window, or {@code null} if the node has not reported one since it was last taken
     */
    @Nullable
    EventWindow takeNewlyReported(@NonNull final NodeId nodeId) {
        return newlyReported.remove(nodeId);
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
        if (newlyReported.isEmpty()) {
            return null;
        }
        EventWindow oldest = null;
        for (final EventWindow eventWindow : latest.values()) {
            if (oldest == null || eventWindow.expiredThreshold() < oldest.expiredThreshold()) {
                oldest = eventWindow;
            }
        }
        return oldest;
    }
}
