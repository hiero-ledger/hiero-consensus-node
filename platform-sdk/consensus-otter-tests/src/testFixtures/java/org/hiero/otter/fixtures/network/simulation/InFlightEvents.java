// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.network.simulation;

import static java.util.Objects.requireNonNull;

import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
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
 * The events that have been transmitted onto the simulated network but not yet delivered, held per directed connection
 * and handed back to each receiver in the order they arrive.
 *
 * <p>Events are kept in a separate queue for every ordered pair of nodes rather than one queue per receiver. Splitting
 * them this way is what makes a connection that is down skippable: its queue is simply left out of the comparison, so
 * it holds up only its own events instead of blocking every other sender's events behind its head.
 *
 * <p>Nothing here sorts. A connection's queue is in arrival order because events are appended to it in arrival order,
 * which is a precondition of {@link #add(NodeId, EventInTransit)} rather than something this class arranges. Delivery
 * then merges the heads of the queues to recover the arrival order across all of the senders.
 *
 * <p>This class is not safe to use from more than one thread.
 */
public class InFlightEvents {

    /**
     * Whether a connection is currently up, given the sender and then the receiver. Supplied by the owner of the
     * network topology, so that this class is concerned only with holding events and ordering them.
     */
    private final BiPredicate<NodeId, NodeId> connected;

    /**
     * The events in flight, keyed by the node that will receive them and then by the node that sent them. A node does
     * not send to itself, so a node's map holds no queue for itself.
     */
    private final Map<NodeId, Map<NodeId, ArrayDeque<EventInTransit>>> connectionsByReceiver = new HashMap<>();

    /**
     * Every node, in node id order. Delivery walks the senders in this order, so that events which arrive at the same
     * instant are always delivered in the same order and the simulation stays reproducible.
     */
    private final List<NodeId> sortedNodeIds = new ArrayList<>();

    /**
     * Constructor.
     *
     * @param connected reports whether the connection from a sender to a receiver is currently up
     */
    public InFlightEvents(@NonNull final BiPredicate<NodeId, NodeId> connected) {
        this.connected = requireNonNull(connected);
    }

    /**
     * Adds a node to the network, giving it a queue for every node already present and each of them a queue for it.
     *
     * @param newNodeId the id of the node to add
     */
    public void addNode(@NonNull final NodeId newNodeId) {
        final Map<NodeId, ArrayDeque<EventInTransit>> incomingConnections = new HashMap<>();
        for (final NodeId existingNodeId : sortedNodeIds) {
            incomingConnections.put(existingNodeId, new ArrayDeque<>());
            connectionsByReceiver.get(existingNodeId).put(newNodeId, new ArrayDeque<>());
        }
        connectionsByReceiver.put(newNodeId, incomingConnections);

        sortedNodeIds.add(newNodeId);
        Collections.sort(sortedNodeIds);
    }

    /**
     * Adds an event that has been transmitted towards a receiver.
     *
     * <p>The event must not arrive before the event added most recently for the same connection. Delivery relies on
     * each connection's queue being in arrival order, and appending is the only thing that puts it in that order.
     *
     * @param receiver the node the event is travelling to
     * @param event    the event, carrying the node that sent it and the time it arrives
     */
    public void add(@NonNull final NodeId receiver, @NonNull final EventInTransit event) {
        final ArrayDeque<EventInTransit> connection =
                connectionsByReceiver.get(receiver).get(event.sender());
        assert connection.isEmpty()
                        || !event.arrivalTime().isBefore(connection.peekLast().arrivalTime())
                : "events must be added to a connection in the order they arrive";
        connection.addLast(event);
    }

    /**
     * Delivers every event that has arrived at the receiving node, in the order the events arrived, until the node
     * stops accepting them.
     *
     * <p>Each connection's queue is already in arrival order, so repeatedly delivering the earliest of their heads
     * walks the events in arrival order across all the senders. A connection whose head has not arrived yet, or
     * which is down, is left out of the comparison rather than skipped over, so it holds up only its own events and
     * this method always either delivers an event or returns.
     *
     * @param now           the current time
     * @param receiver accepts the events on behalf of the receiving node
     */
    public void deliverArrivedEvents(@NonNull final Instant now, @NonNull final EventReceiver receiver) {
        final NodeId receiverId = receiver.getNodeId();
        final Map<NodeId, ArrayDeque<EventInTransit>> incomingConnections = connectionsByReceiver.get(receiverId);

        while (true) {
            final ArrayDeque<EventInTransit> earliestConnection =
                    earliestArrivedConnection(receiverId, incomingConnections, now);
            if (earliestConnection == null) {
                // every connection is either empty, holding events that have not arrived yet, or down
                return;
            }

            // only remove the event from the queue if it was successfully delivered. If the event is not
            // successfully delivered, stop attempting event delivery until the next time this method is called.
            if (!receiver.receiveEvent(earliestConnection.peekFirst().event())) {
                return;
            }
            earliestConnection.pollFirst();
        }
    }

    /**
     * Finds the connection holding the event that arrived earliest among those that can be delivered right now.
     *
     * @param receiverId            the node the events are traveling to
     * @param incomingConnections the events in flight towards the receiver, keyed by the node that sent them
     * @param now                 the current time
     * @return the connection whose head should be delivered next, or {@code null} if there is nothing to deliver
     */
    @Nullable
    private ArrayDeque<EventInTransit> earliestArrivedConnection(
            @NonNull final NodeId receiverId,
            @NonNull final Map<NodeId, ArrayDeque<EventInTransit>> incomingConnections,
            @NonNull final Instant now) {

        ArrayDeque<EventInTransit> earliestConnection = null;
        Instant earliestArrivalTime = null;

        for (final NodeId sender : sortedNodeIds) {
            final ArrayDeque<EventInTransit> connection = incomingConnections.get(sender);
            if (connection == null) {
                // the receiver is the sender, so there is no connection
                continue;
            }
            final EventInTransit event = connection.peekFirst();
            if (event == null || event.arrivalTime().isAfter(now)) {
                // nothing in flight on this connection has arrived yet
                continue;
            }
            if (earliestArrivalTime != null && !event.arrivalTime().isBefore(earliestArrivalTime)) {
                // another connection is holding an event that arrived earlier
                continue;
            }
            if (!connected.test(sender, receiverId)) {
                // the connection is down, so its events are not deliverable
                continue;
            }
            earliestConnection = connection;
            earliestArrivalTime = event.arrivalTime();
        }

        return earliestConnection;
    }

    /**
     * Discards every event in flight towards a node. Used when the node restarts and is about to be sent everything it
     * needs again.
     *
     * @param receiverId the node whose incoming events should be discarded
     */
    public void clearIncoming(@NonNull final NodeId receiverId) {
        connectionsByReceiver.get(receiverId).values().forEach(ArrayDeque::clear);
    }
}
