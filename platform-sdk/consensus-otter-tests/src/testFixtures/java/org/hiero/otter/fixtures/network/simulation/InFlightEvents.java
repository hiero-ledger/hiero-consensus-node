// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.network.simulation;

import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.BiPredicate;
import java.util.function.Function;
import org.hiero.consensus.model.hashgraph.EventWindow;
import org.hiero.consensus.model.node.NodeId;

/**
 * The events that have been transmitted onto the simulated network but not yet delivered, held per directed connection
 * and handed back to each receiver in the order they arrive.
 *
 * <p>Events are kept in a separate queue for every ordered pair of nodes rather than one queue per receiver. Splitting
 * them this way is what makes a connection that is down skippable: its queue is simply left out of the comparison, so
 * it holds up only its own events instead of blocking every other sender's events behind its head.
 *
 * <p>A connection's queue is a priority queue ordered by arrival time, with ties broken by the order the events were
 * added, so an event added later may be delivered before one added earlier. Delivery then merges the heads of the
 * queues to recover the arrival order across all of the senders.
 *
 * <p>A receiver can refuse the events of one sender without affecting the others: the refused connection is left out
 * for the rest of the delivery, its events stay queued, and they are offered again the next time delivery happens. An
 * event that has become ancient for the receiver while it waited is dropped at delivery instead of being offered.
 *
 * <p>This class is not safe to use from more than one thread.
 */
public class InFlightEvents extends InFlightConnections<EventInTransit> {

    /**
     * Gives the current event window of a node, which decides which events are ancient for it.
     */
    private final Function<NodeId, EventWindow> eventWindows;

    /**
     * Constructor.
     *
     * @param connected    reports whether the connection from a sender to a receiver is currently up
     * @param eventWindows gives the current event window of a node
     */
    public InFlightEvents(
            @NonNull final BiPredicate<NodeId, NodeId> connected,
            @NonNull final Function<NodeId, EventWindow> eventWindows) {
        super(connected);
        this.eventWindows = Objects.requireNonNull(eventWindows);
    }

    /**
     * Delivers every event that has arrived at the receiving node, in the order the events arrived.
     *
     * <p>Each connection's queue hands out its events in arrival order, so repeatedly delivering the earliest of their
     * heads walks the events in arrival order across all the senders. A connection whose head has not arrived yet, or
     * which is down, is left out of the comparison rather than skipped over, so it holds up only its own events and
     * this method always makes progress or returns.
     *
     * <p>An event that is ancient for the receiver's current event window is dropped instead of delivered. When the
     * receiver refuses an event, only that sender's connection is left out for the remainder of this call;
     * the event stays at the head of its queue and the other senders' events are still delivered.
     *
     * @param now      the current time
     * @param receiver accepts the events on behalf of the receiving node
     */
    @Override
    void deliverArrived(@NonNull final Instant now, @NonNull final EventReceiver receiver) {
        final NodeId receiverId = receiver.getNodeId();
        final Map<NodeId, InFlightQueue<EventInTransit>> incomingConnections = incomingConnections(receiverId);
        // senders still worth considering during this delivery; a sender that refuses is removed from it
        final List<NodeId> candidateSenders = new ArrayList<>(sortedNodeIds());

        while (true) {
            final InFlightQueue<EventInTransit> earliestConnection =
                    earliestArrivedConnection(receiverId, incomingConnections, now, candidateSenders);
            if (earliestConnection == null) {
                // every connection is either empty, holding events that have not arrived yet, down or refused
                return;
            }

            final EventInTransit head = earliestConnection.peek();
            if (eventWindows.apply(receiverId).isAncient(head.event())) {
                // the event waited so long that the receiver would discard it, so it is not offered
                earliestConnection.poll();
                continue;
            }

            // only remove the event from the queue if it was successfully delivered. If the receiver refuses it,
            // stop delivering from that sender until the next time this method is called.
            if (receiver.receiveEvent(head.event())) {
                earliestConnection.poll();
            } else {
                candidateSenders.remove(head.sender());
            }
        }
    }

    /**
     * Finds the connection holding the event that arrived earliest among those that can be delivered right now.
     *
     * @param receiverId          the node the events are traveling to
     * @param incomingConnections the events in flight towards the receiver, keyed by the node that sent them
     * @param now                 the current time
     * @param candidateSenders    the senders to consider, in node id order, without those the receiver has refused
     *                            during this delivery
     * @return the connection whose head should be delivered next, or {@code null} if there is nothing to deliver
     */
    @Nullable
    private InFlightQueue<EventInTransit> earliestArrivedConnection(
            @NonNull final NodeId receiverId,
            @NonNull final Map<NodeId, InFlightQueue<EventInTransit>> incomingConnections,
            @NonNull final Instant now,
            @NonNull final List<NodeId> candidateSenders) {

        InFlightQueue<EventInTransit> earliestConnection = null;
        Instant earliestArrivalTime = null;

        for (final NodeId sender : candidateSenders) {
            final InFlightQueue<EventInTransit> connection = incomingConnections.get(sender);
            if (connection == null) {
                // the receiver is the sender, so there is no connection
                continue;
            }
            final EventInTransit event = connection.peek();
            if (event == null || event.arrivalTime().isAfter(now)) {
                // nothing in flight on this connection has arrived yet
                continue;
            }
            if (earliestArrivalTime != null && !event.arrivalTime().isBefore(earliestArrivalTime)) {
                // another connection is holding an event that arrived earlier
                continue;
            }
            if (!isUp(sender, receiverId)) {
                // the connection is down, so its events are not deliverable
                continue;
            }
            earliestConnection = connection;
            earliestArrivalTime = event.arrivalTime();
        }

        return earliestConnection;
    }
}
