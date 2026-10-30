// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.network.simulation;

import static java.util.Objects.requireNonNull;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Instant;

/**
 * What the simulated network has transmitted but not yet delivered, held in a separate queue for every directed
 * connection. This is the storage that {@link InFlightEvents} and {@link InFlightEventWindows} share; how the queues are
 * drained into a receiver is up to each of them.
 *
 * <p>Nodes are identified by their rank, which is their position in node id order. Delivery walks
 * the senders in rank order, so that what arrives at the same instant is always handled in the same order and the
 * simulation stays reproducible.
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
     * Reports whether a connection is currently up, given the ranks of its sender and receiver.
     */
    @FunctionalInterface
    interface ConnectionCheck {

        /**
         * @param senderRank   the rank of the node at the sending end of the connection
         * @param receiverRank the rank of the node at the receiving end of the connection
         * @return {@code true} if things can travel from the sender to the receiver
         */
        boolean isUp(int senderRank, int receiverRank);
    }

    /**
     * Whether a connection is currently up. Supplied by the owner of the network topology, so that this class is
     * concerned only with holding what is in flight and ordering it.
     */
    private final ConnectionCheck connected;

    /**
     * What is in flight, indexed by the rank of the node that will receive it and then by the rank of the node that sent
     * it. A node does not send to itself, so the slot of a node's own rank is {@code null}. Empty until
     * {@link #setUp(int)} is called.
     */
    private InFlightQueue<T>[][] connectionsByReceiver = newQueueArray(0);

    /**
     * The instant every queue measures arrival times from. All the queues share it, so that the heads of different
     * queues can be compared with each other.
     */
    private final Instant start;

    /**
     * Constructor.
     *
     * @param connected reports whether the connection from a sender to a receiver is currently up
     * @param start     the instant arrival times are measured from, which should be close to the start of the
     *                  simulation
     */
    InFlightConnections(@NonNull final ConnectionCheck connected, @NonNull final Instant start) {
        this.connected = requireNonNull(connected);
        this.start = requireNonNull(start);
    }

    @SuppressWarnings("unchecked")
    private static <T extends InTransit> InFlightQueue<T>[][] newQueueArray(final int nodeCount) {
        return new InFlightQueue[nodeCount][nodeCount];
    }

    /**
     * Sets up the whole network at once, giving every node a queue for each of the other nodes. Can only be called
     * once.
     *
     * @param nodeCount the number of nodes in the network; their ranks are {@code 0} to {@code nodeCount - 1}
     * @throws IllegalStateException if the network has already been set up
     */
    public void setUp(final int nodeCount) {
        if (connectionsByReceiver.length != 0) {
            throw new IllegalStateException("The network has already been set up");
        }
        final InFlightQueue<T>[][] queues = newQueueArray(nodeCount);
        for (int receiverRank = 0; receiverRank < nodeCount; receiverRank++) {
            for (int senderRank = 0; senderRank < nodeCount; senderRank++) {
                if (senderRank != receiverRank) {
                    queues[receiverRank][senderRank] = new InFlightQueue<>(start);
                }
            }
        }
        connectionsByReceiver = queues;
    }

    /**
     * Adds something that should be delivered to a receiver in the future.
     *
     * @param senderRank   the rank of the node that sent the item
     * @param receiverRank the rank of the node the item is traveling to
     * @param item         the item, carrying the node that sent it and the time it arrives
     */
    public void add(final int senderRank, final int receiverRank, @NonNull final T item) {
        connectionsByReceiver[receiverRank][senderRank].add(item);
    }

    /**
     * Discards everything in flight towards a node. Used when the node restarts and is about to be sent everything it
     * needs again.
     *
     * @param receiverRank the rank of the node whose incoming items should be discarded
     */
    public void clearIncoming(final int receiverRank) {
        for (final InFlightQueue<T> connection : connectionsByReceiver[receiverRank]) {
            if (connection != null) {
                connection.clear();
            }
        }
    }

    /**
     * Delivers what has arrived at the receiving node and can be delivered right now.
     *
     * @param now          the current time
     * @param receiverRank the rank of the receiving node
     * @param receiver     accepts what is delivered on behalf of the receiving node
     */
    abstract void deliverArrived(@NonNull Instant now, int receiverRank, @NonNull EventReceiver receiver);

    /**
     * Gets what is in flight towards a node, indexed by the rank of the node that sent it. The slot of the receiver's
     * own rank is {@code null}.
     *
     * @param receiverRank the rank of the node the items are traveling to
     * @return the queue of each incoming connection, which the caller may drain but must not modify
     */
    @NonNull
    final InFlightQueue<T>[] incomingConnections(final int receiverRank) {
        return connectionsByReceiver[receiverRank];
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
     * {@link #incomingConnections(int)}, because asking is not free and a subclass may know it can put the question off
     * until it matters.
     *
     * @param senderRank   the rank of the node at the sending end of the connection
     * @param receiverRank the rank of the node at the receiving end of the connection
     * @return {@code true} if things can travel from the sender to the receiver
     */
    final boolean isUp(final int senderRank, final int receiverRank) {
        return connected.isUp(senderRank, receiverRank);
    }
}
