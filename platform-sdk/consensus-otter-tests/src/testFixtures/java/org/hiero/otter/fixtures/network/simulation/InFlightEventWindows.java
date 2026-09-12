// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.network.simulation;

import static java.util.Objects.requireNonNull;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiPredicate;
import org.hiero.consensus.model.node.NodeId;

/**
 * The event windows that have been transmitted onto the simulated network but not yet delivered, held per directed
 * connection in the same way {@link InFlightEvents} holds events.
 *
 * <p>An event window supersedes every window the same node sent before it, so a receiver only ever needs the newest one
 * that has arrived. Delivery therefore discards the windows a newer one has already superseded, and hands the receiver
 * a single window per sender no matter how many arrived since the last delivery.
 *
 * <p>That coalescing happens at delivery and never earlier. Collapsing windows while they are still in flight - keeping
 * one slot per connection and overwriting it - would let a sender that updates its window faster than the connection's
 * latency push the arrival time back on every overwrite, so the window would never become due and the receiver would
 * hear nothing at all.
 *
 * <p>This class is not safe to use from more than one thread.
 */
public class InFlightEventWindows {

    /**
     * Whether a connection is currently up, given the sender and then the receiver. Supplied by the owner of the
     * network topology, so that this class is concerned only with holding event windows and ordering them.
     */
    private final BiPredicate<NodeId, NodeId> connected;

    /**
     * The event windows in flight, keyed by the node that will receive them and then by the node that sent them. A node
     * does not send to itself, so a node's map holds no queue for itself.
     */
    private final Map<NodeId, Map<NodeId, ArrayDeque<EventWindowInTransit>>> connectionsByReceiver = new HashMap<>();

    /**
     * Every node, in node id order. Delivery walks the senders in this order so that the receiver is given their
     * windows in the same order on every run.
     */
    private final List<NodeId> sortedNodeIds = new ArrayList<>();

    /**
     * Constructor.
     *
     * @param connected reports whether the connection from a sender to a receiver is currently up
     */
    public InFlightEventWindows(@NonNull final BiPredicate<NodeId, NodeId> connected) {
        this.connected = requireNonNull(connected);
    }

    /**
     * Adds a node to the network, giving it a queue for every node already present and each of them a queue for it.
     *
     * @param newNodeId the id of the node to add
     */
    public void addNode(@NonNull final NodeId newNodeId) {
        final Map<NodeId, ArrayDeque<EventWindowInTransit>> incomingConnections = new HashMap<>();
        for (final NodeId existingNodeId : sortedNodeIds) {
            incomingConnections.put(existingNodeId, new ArrayDeque<>());
            connectionsByReceiver.get(existingNodeId).put(newNodeId, new ArrayDeque<>());
        }
        connectionsByReceiver.put(newNodeId, incomingConnections);

        sortedNodeIds.add(newNodeId);
        Collections.sort(sortedNodeIds);
    }

    /**
     * Adds an event window that should be delivered to a receiver in the future.
     *
     * <p>The window must not have an arrival time before the window added most recently for the same connection.
     * Delivery relies on each connection's queue being in arrival order, and appending is the only thing that puts it
     * in that order.
     *
     * @param receiver    the node the event window is traveling to
     * @param eventWindow the event window, carrying the node it belongs to and the time it arrives
     * @throws IllegalArgumentException if the event window arrives before the one added most recently for the same
     *                                  connection
     */
    public void add(@NonNull final NodeId receiver, @NonNull final EventWindowInTransit eventWindow) {
        final ArrayDeque<EventWindowInTransit> connection =
                connectionsByReceiver.get(receiver).get(eventWindow.sender());

        final EventWindowInTransit previousEventWindow = connection.peekLast();
        if (previousEventWindow != null && eventWindow.arrivalTime().isBefore(previousEventWindow.arrivalTime())) {
            throw new IllegalArgumentException(
                    ("The event window sent from %s to %s arrives at %s, which is before the window ahead of it on "
                                    + "that connection arrives at %s. They would be delivered out of order.")
                            .formatted(
                                    eventWindow.sender(),
                                    receiver,
                                    eventWindow.arrivalTime(),
                                    previousEventWindow.arrivalTime()));
        }

        connection.addLast(eventWindow);
    }

    /**
     * Delivers the newest event window each sender has landed at the receiving node, discarding the ones it
     * supersedes.
     *
     * <p>A connection whose windows have not arrived yet, or which is down, is passed over and keeps everything it is
     * holding. As with events, a receiver that refuses a window is taken to be refusing everything, so no further
     * senders are tried until the next call.
     *
     * @param now      the current time
     * @param receiver accepts the event windows on behalf of the receiving node
     */
    public void deliverArrivedEventWindows(@NonNull final Instant now, @NonNull final EventReceiver receiver) {
        final NodeId receiverId = receiver.getNodeId();
        final Map<NodeId, ArrayDeque<EventWindowInTransit>> incomingConnections = connectionsByReceiver.get(receiverId);

        for (final NodeId sender : sortedNodeIds) {
            final ArrayDeque<EventWindowInTransit> connection = incomingConnections.get(sender);
            if (connection == null) {
                // the receiver is the sender, so there is no connection
                continue;
            }
            if (!connected.test(sender, receiverId)) {
                // the connection is down, so its windows are not deliverable
                continue;
            }

            // Find the newest window that has arrived. The ones before it are superseded, but they are only discarded
            // once the newest has actually been accepted.
            int arrivedCount = 0;
            EventWindowInTransit newestArrived = null;
            for (final EventWindowInTransit candidate : connection) {
                if (candidate.arrivalTime().isAfter(now)) {
                    break;
                }
                newestArrived = candidate;
                arrivedCount++;
            }

            if (newestArrived == null) {
                // nothing in flight on this connection has arrived yet
                continue;
            }

            if (!receiver.receiveEventWindow(sender, newestArrived.eventWindow())) {
                return;
            }

            for (int i = 0; i < arrivedCount; i++) {
                connection.pollFirst();
            }
        }
    }

    /**
     * Discards every event window in flight towards a node. Used when the node restarts and is about to be sent
     * everything it needs again.
     *
     * @param receiverId the node whose incoming event windows should be discarded
     */
    public void clearIncoming(@NonNull final NodeId receiverId) {
        connectionsByReceiver.get(receiverId).values().forEach(ArrayDeque::clear);
    }
}
