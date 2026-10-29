// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.network.simulation;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Instant;
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
 * queues, kept in a heap of their own, to recover the arrival order across all of the senders.
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
     * The connections into the receiver currently being delivered to whose next event has arrived, ordered by the
     * arrival time of that event and then by the node that sent it.
     *
     * <p>It is only used during {@link #deliverArrived(Instant, EventReceiver)} and is empty between calls. It could
     * be a local variable of that method, and is an instance field only to avoid allocating a new heap on every call,
     * which happens once per receiver per tick.
     */
    private final ConnectionHeap<EventInTransit> connectionsByEarliestEvent = new ConnectionHeap<>();

    /**
     * Constructor.
     *
     * @param connected    reports whether the connection from a sender to a receiver is currently up
     * @param eventWindows gives the current event window of a node
     * @param start        the instant arrival times are measured from, which should be close to the start of the
     *                     simulation
     */
    public InFlightEvents(
            @NonNull final BiPredicate<NodeId, NodeId> connected,
            @NonNull final Function<NodeId, EventWindow> eventWindows,
            @NonNull final Instant start) {
        super(connected, start);
        this.eventWindows = Objects.requireNonNull(eventWindows);
    }

    /**
     * Delivers every event that has arrived at the receiving node, in the order the events arrived.
     *
     * <p>Each connection's queue hands out its events in arrival order, so repeatedly delivering the earliest of their
     * heads walks the events in arrival order across all the senders. The connections whose head can be delivered are
     * kept in {@link #connectionsByEarliestEvent}, so finding the earliest head does not mean looking at every
     * connection. A connection whose head has not arrived yet, or which is down, is never put there, so it holds up
     * only its own events and this method always makes progress or returns.
     *
     * <p>Whether a connection is up is asked only once, before the first event is delivered. That is enough because
     * nothing that happens during delivery changes the topology: the network only changes it between deliveries.
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
        final long nowNanos = nanosSinceStart(now);

        // empty after every call that returns normally; cleared in case a previous call ended with an exception
        connectionsByEarliestEvent.clear();
        final Map<NodeId, InFlightQueue<EventInTransit>> incomingConnections = incomingConnections(receiverId);
        final List<NodeId> sortedNodeIds = sortedNodeIds();
        for (int rank = 0; rank < sortedNodeIds.size(); rank++) {
            final NodeId sender = sortedNodeIds.get(rank);
            final InFlightQueue<EventInTransit> connection = incomingConnections.get(sender);
            // there is no connection from the receiver to itself
            if (connection != null && connection.hasArrived(nowNanos) && isUp(sender, receiverId)) {
                connectionsByEarliestEvent.add(connection, rank);
            }
        }

        while (!connectionsByEarliestEvent.isEmpty()) {
            final InFlightQueue<EventInTransit> earliestConnection = connectionsByEarliestEvent.top();
            final EventInTransit head = earliestConnection.peek();
            if (eventWindows.apply(receiverId).isAncient(head.event())) {
                // the event waited so long that the receiver would discard it, so it is not offered
                earliestConnection.poll();
            } else if (receiver.receiveEvent(head.event())) {
                earliestConnection.poll();
            } else {
                // the receiver refused, so the event stays queued and the connection stays out of the heap until the
                // next time this method is called
                connectionsByEarliestEvent.removeTop();
                continue;
            }
            if (earliestConnection.hasArrived(nowNanos)) {
                // the connection's head is later than before, so it moves down to where it belongs now
                connectionsByEarliestEvent.reorderTop();
            } else {
                connectionsByEarliestEvent.removeTop();
            }
        }
    }
}
