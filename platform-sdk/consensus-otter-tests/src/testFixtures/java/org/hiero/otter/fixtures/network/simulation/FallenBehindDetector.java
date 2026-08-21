// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.network.simulation;

import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.state.roster.Roster;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.hiero.consensus.model.hashgraph.EventWindow;
import org.hiero.consensus.model.node.NodeId;
import org.hiero.consensus.monitoring.FallenBehindMonitor;
import org.hiero.otter.fixtures.internal.network.ConnectionKey;
import org.hiero.otter.fixtures.network.Topology.ConnectionState;

/**
 * Determines which nodes of a simulated network have fallen behind the rest of it.
 *
 * <p>A node is behind a peer when the peer has already expired events the node has not yet caught up to, which is the
 * same comparison of event windows that a real node makes while syncing. A node has fallen behind once enough of the
 * peer weight has told it so, and the reports are both gathered and weighed by {@link FallenBehindMonitor}, the same
 * class production uses. A peer can only speak for itself while it is reachable, and what it said stands until it says
 * otherwise - see {@link #isReportedBehindByEnoughWeight(NodeId)}.
 *
 * <p>Detection is driven from the outside: this class reads the node ids, event windows and connections owned by
 * {@link SimulatedNetworkTraffic} and reports its findings back, but never acts on them itself.
 *
 * <p>A node is only ever reported once. The network stops sending events to a node that has fallen behind, so its
 * event window can never advance again and nothing would bring it back. Reconnect will be what clears this.
 */
class FallenBehindDetector {

    /**
     * Every node in the network, in node id order. Owned by {@link SimulatedNetworkTraffic}; read here so that both the
     * nodes being checked and the peers reporting on them are visited in a deterministic order.
     */
    private final List<NodeId> nodeIds;

    /**
     * The most recent {@link EventWindow} reported by each node. Owned by {@link SimulatedNetworkTraffic}.
     */
    private final Map<NodeId, EventWindow> nodeEventWindows;

    /**
     * The connection state between each pair of nodes. Owned by {@link SimulatedNetworkTraffic}.
     */
    private final Map<ConnectionKey, ConnectionState> connections;

    /**
     * The roster of the network, needed to weigh the reports that a node has fallen behind. Not available when the
     * network is constructed, so it arrives later via {@link #start(Roster, double)}. While it is {@code null},
     * detection is inert and no node is ever considered to have fallen behind.
     */
    @Nullable
    private Roster roster;

    /**
     * The fraction of the peer weight that has to report a node as behind before it is considered to have fallen
     * behind. Provided along with the roster by {@link #start(Roster, double)}.
     */
    private double fallenBehindThreshold;

    /**
     * The monitor of each node, which accumulates the reports of that node's peers and applies
     * {@link #fallenBehindThreshold} to them. Created on first use, since the roster they need is not available when
     * this object is constructed.
     */
    private final Map<NodeId, FallenBehindMonitor> monitors = new HashMap<>();

    /**
     * The nodes that have fallen behind. Confined to the thread that drives the tick: it is written by
     * {@link #detectNewlyFallenBehind()} and read by the delivery decisions that follow on the same tick, and the nodes
     * are not running on any thread while either happens.
     */
    private final Set<NodeId> fallenBehindNodes = new HashSet<>();

    /**
     * Set when something that could change the outcome of a check happens, and cleared by the check itself. Detection
     * compares event windows across connections, so it only has to run again once one of those has changed.
     *
     * <p>Unlike {@link #fallenBehindNodes}, this is written by the nodes themselves as they report event windows, which
     * they do while running concurrently. Hence the {@code volatile}.
     */
    private volatile boolean checkNeeded = false;

    /**
     * Reused by {@link #detectNewlyFallenBehind()} to report its findings, so that a check that finds nothing - which
     * is nearly all of them - does not allocate.
     */
    private final List<NodeId> newlyFallenBehind = new ArrayList<>();

    /**
     * Constructor.
     *
     * <p>The three collections are the live ones held by {@link SimulatedNetworkTraffic}, not copies. This class only
     * ever reads them.
     *
     * @param nodeIds          every node in the network, in node id order
     * @param nodeEventWindows the most recent event window reported by each node
     * @param connections      the connection state between each pair of nodes
     */
    FallenBehindDetector(
            @NonNull final List<NodeId> nodeIds,
            @NonNull final Map<NodeId, EventWindow> nodeEventWindows,
            @NonNull final Map<ConnectionKey, ConnectionState> connections) {
        this.nodeIds = requireNonNull(nodeIds);
        this.nodeEventWindows = requireNonNull(nodeEventWindows);
        this.connections = requireNonNull(connections);
    }

    /**
     * Provides the data that is not available when this object is constructed, which enables detection. Until this is
     * called, no node is ever considered to have fallen behind.
     *
     * @param roster                the roster of the network, used to weigh the reports that a node has fallen behind
     * @param fallenBehindThreshold the fraction of the peer weight that has to report a node as behind before it is
     *                              considered to have fallen behind
     */
    void start(@NonNull final Roster roster, final double fallenBehindThreshold) {
        this.roster = requireNonNull(roster);
        this.fallenBehindThreshold = fallenBehindThreshold;
        this.checkNeeded = true;
    }

    /**
     * Records that an event window or the connections have changed, so that the next call to
     * {@link #detectNewlyFallenBehind()} does the work rather than returning immediately. Safe to be called by multiple
     * nodes in parallel.
     */
    void markStale() {
        checkNeeded = true;
    }

    /**
     * Checks whether a node has fallen behind the rest of the network.
     *
     * @param nodeId the id of the node to check
     * @return {@code true} if the node has fallen behind
     */
    boolean hasFallenBehind(@NonNull final NodeId nodeId) {
        return fallenBehindNodes.contains(nodeId);
    }

    /**
     * Finds the nodes that have fallen behind since the previous call.
     *
     * @return the nodes that have just fallen behind, empty if there are none; valid until the next call
     */
    @NonNull
    List<NodeId> detectNewlyFallenBehind() {
        newlyFallenBehind.clear();
        if (roster == null || !checkNeeded) {
            return newlyFallenBehind;
        }
        checkNeeded = false;

        for (final NodeId nodeId : nodeIds) {
            if (!fallenBehindNodes.contains(nodeId) && isReportedBehindByEnoughWeight(nodeId)) {
                fallenBehindNodes.add(nodeId);
                newlyFallenBehind.add(nodeId);
            }
        }
        return newlyFallenBehind;
    }

    /**
     * Asks each of a node's reachable peers whether it considers the node to be behind, and weighs the answers against
     * {@link #fallenBehindThreshold}.
     *
     * <p>What a peer said is remembered until that same peer says otherwise. This is how a real node behaves:
     * {@link FallenBehindMonitor#check} is the only thing that ever withdraws a report, and gossip only reaches it
     * while a sync with that peer is under way. Losing the connection to a peer therefore leaves its last report
     * standing rather than retracting it, and so does that peer falling behind itself. Only a completed reconnect wipes
     * the slate, which is why nothing here calls {@link FallenBehindMonitor#clear()}.
     *
     * @param nodeId the id of the node to check
     * @return {@code true} if enough peer weight reports the node as behind
     */
    private boolean isReportedBehindByEnoughWeight(@NonNull final NodeId nodeId) {
        final EventWindow eventWindow = nodeEventWindows.get(nodeId);
        final FallenBehindMonitor monitor = monitors.computeIfAbsent(
                nodeId, id -> new FallenBehindMonitor(requireNonNull(roster), id, fallenBehindThreshold));

        for (final NodeId peerId : nodeIds) {
            if (peerId.equals(nodeId) || fallenBehindNodes.contains(peerId)) {
                // A node cannot report on itself, and a node that has fallen behind has stopped gossiping, so it has
                // no way to revise whatever it last said
                continue;
            }

            // A node only learns a peer's event window by syncing with it, which it cannot do without a connection, so
            // a peer it cannot reach is left as it was rather than asked again. A node that has never been reported by
            // anyone is therefore never declared behind, but one that already has been stays that way.
            final ConnectionState connectionState = connections.get(new ConnectionKey(peerId, nodeId));
            if (connectionState == null || !connectionState.connected()) {
                continue;
            }

            monitor.check(eventWindow, nodeEventWindows.get(peerId), peerId);
        }

        return monitor.hasFallenBehind();
    }
}
