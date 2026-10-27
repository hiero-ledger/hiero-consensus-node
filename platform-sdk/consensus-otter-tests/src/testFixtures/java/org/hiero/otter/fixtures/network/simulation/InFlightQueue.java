// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.network.simulation;

import static java.util.Objects.requireNonNull;

import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.time.Instant;
import java.util.Arrays;

/**
 * What is in flight on one directed connection, handed out in order of arrival time whatever order it was added in.
 * Items that arrive at the same time are handed out in the order they were added.
 *
 * <p>Taking the next item off a connection is on the hottest path of a simulated network, so this is a binary min-heap
 * written out by hand rather than a {@link java.util.PriorityQueue}. It is kept in parallel arrays, so that comparing
 * two items reads primitives rather than going through {@link Instant} and a comparator. An arrival time is kept as
 * the nanoseconds since a start instant given to the constructor. Only the first {@link #size} slots of each array are
 * used.
 *
 * <p>This class is not safe to use from more than one thread.
 *
 * @param <T> what is held in flight
 */
final class InFlightQueue<T extends InTransit> {

    private static final int INITIAL_CAPACITY = 8;

    private static final long NANOS_PER_SECOND = 1_000_000_000L;

    /**
     * The furthest an instant can be from the start, in seconds, and still have its distance in nanoseconds fit in a
     * {@code long}. That is about 292 years, far longer than any simulation runs.
     */
    private static final long MAX_OFFSET_SECONDS = Long.MAX_VALUE / NANOS_PER_SECOND - 1;

    /** The instant arrival times are measured from, as seconds since the epoch. */
    private final long startSeconds;

    /** The instant arrival times are measured from, as the nanoseconds within its second. */
    private final int startNanos;

    /** The arrival time of each item, as nanoseconds since the start. */
    private long[] arrivals = new long[INITIAL_CAPACITY];

    /** The position each item was added in, which breaks ties between equal arrival times. */
    private long[] sequences = new long[INITIAL_CAPACITY];

    /** The items. */
    private Object[] items = new Object[INITIAL_CAPACITY];

    /** The number of items. */
    private int size;

    /** The position the next item added will have. */
    private long nextSequence = 0;

    /**
     * The arrival time of the next item as nanoseconds since the start, or {@link Long#MAX_VALUE} if there is none. A
     * copy of the first slot of {@link #arrivals}, so that comparing two queues reads fewer arrays.
     */
    private long headArrival = Long.MAX_VALUE;

    /**
     * Constructor.
     *
     * @param start the instant arrival times are measured from; queues whose heads are compared with each other must
     *              all be given the same one
     */
    InFlightQueue(@NonNull final Instant start) {
        requireNonNull(start);
        this.startSeconds = start.getEpochSecond();
        this.startNanos = start.getNano();
    }

    /**
     * Converts an instant to the nanoseconds since the start of the queues it is compared with.
     *
     * <p>An instant more than about 292 years from the start is clamped to {@link Long#MIN_VALUE} or
     * {@link Long#MAX_VALUE}. That only happens to something sent before the network's first tick, while the
     * network's clock still reads {@link Instant#MIN}; it has arrived by any current time, as it would be without the
     * clamp.
     *
     * @param start the instant arrival times are measured from
     * @param time  the instant to convert
     * @return the nanoseconds from {@code start} to {@code time}
     */
    static long nanosSince(@NonNull final Instant start, @NonNull final Instant time) {
        return nanosSince(start.getEpochSecond(), start.getNano(), time);
    }

    private static long nanosSince(final long startSeconds, final int startNanos, @NonNull final Instant time) {
        final long seconds = time.getEpochSecond() - startSeconds;
        if (seconds > MAX_OFFSET_SECONDS) {
            return Long.MAX_VALUE;
        }
        if (seconds < -MAX_OFFSET_SECONDS) {
            return Long.MIN_VALUE;
        }
        return seconds * NANOS_PER_SECOND + (time.getNano() - startNanos);
    }

    /**
     * Adds something to be delivered in the future.
     *
     * @param item the item to add
     */
    void add(@NonNull final T item) {
        if (size == items.length) {
            final int capacity = size * 2;
            arrivals = Arrays.copyOf(arrivals, capacity);
            sequences = Arrays.copyOf(sequences, capacity);
            items = Arrays.copyOf(items, capacity);
        }
        final long itemArrival = nanosSince(startSeconds, startNanos, item.arrivalTime());
        final long itemSequence = nextSequence++;

        int hole = size++;
        while (hole > 0) {
            final int parent = (hole - 1) >>> 1;
            if (!isEarlier(itemArrival, itemSequence, arrivals[parent], sequences[parent])) {
                break;
            }
            moveTo(parent, hole);
            hole = parent;
        }
        set(hole, itemArrival, itemSequence, item);
        if (hole == 0) {
            headArrival = itemArrival;
        }
    }

    /**
     * @return the next item to be delivered, or {@code null} if the queue is empty
     */
    @Nullable
    @SuppressWarnings("unchecked")
    T peek() {
        return size == 0 ? null : (T) items[0];
    }

    /**
     * Removes the next item to be delivered.
     *
     * @return the removed item, or {@code null} if the queue is empty
     */
    @Nullable
    T poll() {
        if (size == 0) {
            return null;
        }
        final T head = peek();

        final int last = --size;
        final long lastArrival = arrivals[last];
        final long lastSequence = sequences[last];
        final Object lastItem = items[last];
        items[last] = null;
        if (last == 0) {
            headArrival = Long.MAX_VALUE;
            return head;
        }

        int hole = 0;
        while (true) {
            int child = 2 * hole + 1;
            if (child >= last) {
                break;
            }
            final int right = child + 1;
            if (right < last && isEarlier(arrivals[right], sequences[right], arrivals[child], sequences[child])) {
                child = right;
            }
            if (!isEarlier(arrivals[child], sequences[child], lastArrival, lastSequence)) {
                break;
            }
            moveTo(child, hole);
            hole = child;
        }
        set(hole, lastArrival, lastSequence, lastItem);
        headArrival = arrivals[0];
        return head;
    }

    /**
     * Discards everything in the queue.
     */
    void clear() {
        Arrays.fill(items, 0, size, null);
        size = 0;
        headArrival = Long.MAX_VALUE;
    }

    /**
     * Reports whether the next item has arrived. The same as checking that {@link #peek()} is not {@code null} and
     * its arrival time is not after the current time, given as nanoseconds since the start so that a caller asking
     * about many queues converts the current time only once.
     *
     * @param now the current time, as returned by {@link #nanosSince(Instant, Instant)} for this queue's start
     * @return {@code true} if there is an item whose arrival time is not after the current time
     */
    boolean hasArrived(final long now) {
        return size > 0 && headArrival <= now;
    }

    /**
     * @return the arrival time of the next item as nanoseconds since the start; only meaningful if the queue is not
     *     empty
     */
    long headArrival() {
        return headArrival;
    }

    private static boolean isEarlier(
            final long aArrival, final long aSequence, final long bArrival, final long bSequence) {
        if (aArrival != bArrival) {
            return aArrival < bArrival;
        }
        return aSequence < bSequence;
    }

    private void moveTo(final int from, final int to) {
        arrivals[to] = arrivals[from];
        sequences[to] = sequences[from];
        items[to] = items[from];
    }

    private void set(final int index, final long itemArrival, final long itemSequence, @NonNull final Object item) {
        arrivals[index] = itemArrival;
        sequences[index] = itemSequence;
        items[index] = item;
    }
}
