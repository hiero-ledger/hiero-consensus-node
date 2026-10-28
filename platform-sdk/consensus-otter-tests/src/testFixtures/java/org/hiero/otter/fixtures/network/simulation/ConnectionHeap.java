// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.network.simulation;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Arrays;

/**
 * A binary min-heap of connections, ordered by the arrival time of the item at the head of each connection's queue and
 * then by the position of the connection's sender in node id order. Delivery uses it to find the connection holding
 * the earliest item among many without looking at all of them.
 *
 * <p>It is written out by hand rather than being a {@link java.util.PriorityQueue}, because it is consulted for every
 * item delivered. The connections are kept in {@link #connections}, and the sender of each in {@link #senderRanks},
 * slot for slot. Only the first {@link #size} slots are used.
 *
 * <p>The order is read from the head of each connection's queue, so a connection's queue must not change while the
 * connection is in the heap, except for the connection at the top, which must be put back in order with
 * {@link #reorderTop()} or taken out with {@link #removeTop()} straight afterwards.
 *
 * <p>This class is not safe to use from more than one thread.
 *
 * @param <T> what is held in flight on the connections
 */
final class ConnectionHeap<T extends InTransit> {

    private static final int INITIAL_CAPACITY = 8;

    /** The connections, in heap order. */
    @SuppressWarnings("unchecked")
    private InFlightQueue<T>[] connections = new InFlightQueue[INITIAL_CAPACITY];

    /**
     * The position in node id order of the sender of each connection in {@link #connections}. It breaks ties between
     * connections whose heads arrive at the same instant.
     */
    private int[] senderRanks = new int[INITIAL_CAPACITY];

    /** The number of connections in the heap. */
    private int size;

    /**
     * Removes every connection.
     */
    void clear() {
        Arrays.fill(connections, 0, size, null);
        size = 0;
    }

    /**
     * @return {@code true} if there are no connections in the heap
     */
    boolean isEmpty() {
        return size == 0;
    }

    /**
     * @return the connection whose head arrives first; the heap must not be empty
     */
    @NonNull
    InFlightQueue<T> top() {
        return connections[0];
    }

    /**
     * Adds a connection.
     *
     * @param connection the connection to add, which must not be empty
     * @param senderRank the position of the connection's sender in node id order
     */
    void add(@NonNull final InFlightQueue<T> connection, final int senderRank) {
        if (size == connections.length) {
            connections = Arrays.copyOf(connections, size * 2);
            senderRanks = Arrays.copyOf(senderRanks, size * 2);
        }
        int hole = size++;
        while (hole > 0) {
            final int parent = (hole - 1) >>> 1;
            if (!isEarlier(connection, senderRank, parent)) {
                break;
            }
            connections[hole] = connections[parent];
            senderRanks[hole] = senderRanks[parent];
            hole = parent;
        }
        connections[hole] = connection;
        senderRanks[hole] = senderRank;
    }

    /**
     * Removes the connection at the top. The heap must not be empty.
     */
    void removeTop() {
        final int last = --size;
        final InFlightQueue<T> lastConnection = connections[last];
        connections[last] = null;
        if (last > 0) {
            siftDownFromTop(lastConnection, senderRanks[last]);
        }
    }

    /**
     * Moves the connection at the top to where it belongs, after the head of its queue has been removed. Its queue
     * must not be empty.
     */
    void reorderTop() {
        siftDownFromTop(connections[0], senderRanks[0]);
    }

    /**
     * Puts a connection at the top and moves it down to where it belongs.
     *
     * @param connection the connection to put at the top, replacing the one that was there
     * @param senderRank the position of the connection's sender in node id order
     */
    private void siftDownFromTop(@NonNull final InFlightQueue<T> connection, final int senderRank) {
        final InFlightQueue<T>[] heap = connections;
        final int heapSize = size;
        int hole = 0;
        while (true) {
            int child = 2 * hole + 1;
            if (child >= heapSize) {
                break;
            }
            final int right = child + 1;
            if (right < heapSize && isEarlier(heap[right], senderRanks[right], child)) {
                child = right;
            }
            if (isEarlier(connection, senderRank, child)) {
                break;
            }
            heap[hole] = heap[child];
            senderRanks[hole] = senderRanks[child];
            hole = child;
        }
        heap[hole] = connection;
        senderRanks[hole] = senderRank;
    }

    /**
     * Decides whether a connection comes before the one in a slot: it does if its head arrives earlier, or, when both
     * arrive at the same instant, if its sender has the lower node id.
     *
     * @param connection a connection that is not empty
     * @param senderRank the position of that connection's sender in node id order
     * @param slot       a slot of the heap, holding a connection from a different sender
     * @return {@code true} if {@code connection} comes before the connection in {@code slot}
     */
    private boolean isEarlier(@NonNull final InFlightQueue<T> connection, final int senderRank, final int slot) {
        final InFlightQueue<T> other = connections[slot];
        if (connection.headArrival() != other.headArrival()) {
            return connection.headArrival() < other.headArrival();
        }
        return senderRank < senderRanks[slot];
    }
}
