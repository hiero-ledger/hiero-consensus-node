// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.network.simulation;

import static java.util.Objects.requireNonNull;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiPredicate;
import org.hiero.consensus.model.node.NodeId;

/**
 * What the simulated network has transmitted but not yet delivered, held in a separate queue for every directed
 * connection. This is the storage that {@link InFlightEvents} and {@link InFlightEventWindows} share; how the queues are
 * drained into a receiver is up to each of them.
 *
 * <p>Each connection's queue is an {@link InFlightQueue}, which hands out what it holds in arrival
 * order. Subclasses rely on that when they deliver.
 *
 * <p>This class is not safe to use from more than one thread.
 *
 * @param <T> what is held in flight
 */
abstract class InFlightConnections<T extends InTransit> {

    /**
     * Whether a connection is currently up, given the sender and then the receiver. Supplied by the owner of the
     * network topology, so that this class is concerned only with holding what is in flight and ordering it.
     */
    private final BiPredicate<NodeId, NodeId> connected;

    /**
     * What is in flight, keyed by the node that will receive it and then by the node that sent it. A node does not send
     * to itself, so a node's map holds no queue for itself.
     */
    private final Map<NodeId, Map<NodeId, InFlightQueue<T>>> connectionsByReceiver = new HashMap<>();

    /**
     * Every node, in node id order. Delivery walks the senders in this order so that what arrives at the same instant
     * is always handled in the same order and the simulation stays reproducible. Fixed when this instance is created.
     */
    private final List<NodeId> sortedNodeIds;

    /**
     * The instant every queue measures arrival times from. All the queues share it, so that the heads of different
     * queues can be compared with each other.
     */
    private final Instant start;

    /**
     * Constructor. Gives every node a queue for each of the other nodes. The nodes cannot be changed afterwards.
     *
     * @param nodeIds   the ids of all nodes in the network, in any order
     * @param connected reports whether the connection from a sender to a receiver is currently up
     * @param start     the instant arrival times are measured from, which should be close to the start of the
     *                  simulation
     */
    InFlightConnections(
            @NonNull final Collection<NodeId> nodeIds,
            @NonNull final BiPredicate<NodeId, NodeId> connected,
            @NonNull final Instant start) {
        this.sortedNodeIds = nodeIds.stream().sorted().toList();
        this.connected = requireNonNull(connected);
        this.start = requireNonNull(start);
        for (final NodeId receiver : sortedNodeIds) {
            final Map<NodeId, InFlightQueue<T>> incomingConnections = new HashMap<>();
            for (final NodeId sender : sortedNodeIds) {
                if (!sender.equals(receiver)) {
                    incomingConnections.put(sender, new InFlightQueue<>(start));
                }
            }
            connectionsByReceiver.put(receiver, incomingConnections);
        }
    }

    /**
     * Adds something that should be delivered to a receiver in the future.
     *
     * @param receiver the node the item is traveling to
     * @param item     the item, carrying the node that sent it and the time it arrives
     */
    public void add(@NonNull final NodeId receiver, @NonNull final T item) {
        connectionsByReceiver.get(receiver).get(item.sender()).add(item);
    }

    /**
     * Discards everything in flight towards a node. Used when the node restarts and is about to be sent everything it
     * needs again.
     *
     * @param receiverId the node whose incoming items should be discarded
     */
    public void clearIncoming(@NonNull final NodeId receiverId) {
        connectionsByReceiver.get(receiverId).values().forEach(InFlightQueue::clear);
    }

    /**
     * Delivers what has arrived at the receiving node and can be delivered right now.
     *
     * @param now      the current time
     * @param receiver accepts what is delivered on behalf of the receiving node
     */
    abstract void deliverArrived(@NonNull Instant now, @NonNull EventReceiver receiver);

    /**
     * Gets what is in flight towards a node, keyed by the node that sent it. There is no entry for the receiver itself.
     *
     * @param receiverId the node the items are traveling to
     * @return the queue of each incoming connection, which the caller may drain
     */
    @NonNull
    final Map<NodeId, InFlightQueue<T>> incomingConnections(@NonNull final NodeId receiverId) {
        return connectionsByReceiver.get(receiverId);
    }

    /**
     * Gets every node in node id order, which is the order delivery walks the senders in.
     *
     * @return the node ids, which cannot be modified
     */
    @NonNull
    final List<NodeId> sortedNodeIds() {
        return sortedNodeIds;
    }

    /**
     * Converts an instant to the form the queues compare arrival times in.
     *
     * @param time the instant to convert
     * @return the nanoseconds since the instant the queues measure arrival times from
     */
    final long nanosSinceStart(@NonNull final Instant time) {
        return InFlightQueue.nanosSince(start, time);
    }

    /**
     * Reports whether a connection is currently up. This is deliberately not folded into
     * {@link #incomingConnections(NodeId)}, because asking is not free and a subclass may know it can put the question
     * off until it matters.
     *
     * @param sender   the node at the sending end of the connection
     * @param receiver the node at the receiving end of the connection
     * @return {@code true} if things can travel from the sender to the receiver
     */
    final boolean isUp(@NonNull final NodeId sender, @NonNull final NodeId receiver) {
        return connected.test(sender, receiver);
    }
}
