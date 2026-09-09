// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.util;

import static java.util.Objects.requireNonNull;

import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.function.ToLongFunction;
import org.hiero.consensus.model.sequence.set.SequenceSet;
import org.hiero.consensus.model.sequence.set.StandardSequenceSet;

/**
 * An ordered log of items, each with an associated sequence number, that supports any number of independent cursors
 * reading at different points in the log. Multiple items may share a sequence number, and items sharing a sequence
 * number need not be adjacent in the log.
 *
 * <p>The log is backed by a growable ring buffer addressed by <i>absolute positions</i>. The position assigned to an
 * item when it is added never changes for as long as that item is in the log, which is what allows cursors to remain
 * valid as the log is appended to and items are removed. All of the following are constant time:
 *
 * <ul>
 *     <li>{@link #add(Object)} - amortized, the buffer doubles when full</li>
 *     <li>{@link #get(long)} - random access by position</li>
 *     <li>{@link Cursor#seek(long)} - repositioning a cursor</li>
 *     <li>{@link Cursor#next()}</li>
 * </ul>
 *
 * <p>{@link #removeSequenceNumber(long)} is the only means of removal, and costs time proportional to the number of
 * items removed rather than the size of the log. This is possible because a {@link SequenceSet} is maintained alongside
 * the buffer as an index from sequence number to the items carrying it.
 *
 * <p><b>Removal leaves a tombstone.</b> A removed item generally sits somewhere in the middle of the insertion order,
 * so its slot is nulled in place rather than the gap being closed. Compacting the log would shift the position of every
 * item after the gap and silently invalidate every outstanding cursor. Cursors skip tombstones during iteration, and
 * tombstones that reach the front of the log are reclaimed. This means {@link #size()} (live items) can be smaller than
 * {@link #span()} (positions between the first and last) while tombstones remain in the middle.
 *
 * <p>Sequence numbers are expected to trend upwards over time, which is the use case the backing {@link SequenceSet} is
 * designed for. Sequence numbers must be removed in strictly increasing order, and the window of sequence numbers the
 * log accepts moves up as they are: an item whose sequence number has already been removed is rejected rather than
 * added. See {@link #removeSequenceNumber(long)} for the consequences.
 *
 * <p>This class is thread safe. All operations, including those on cursors handed out by {@link #newCursor()},
 * synchronize on the log instance.
 *
 * @param <T> the type of item held in the log
 */
public final class CursoredLog<T> {

    /** The smallest buffer this log will allocate. Must be a power of two. */
    private static final int MIN_CAPACITY = 8;

    /** The largest buffer this log will allocate. Must be a power of two. */
    private static final int MAX_CAPACITY = 1 << 30;

    /**
     * An item in the log, paired with the position it occupies and its sequence number.
     *
     * <p>Equality is defined on {@link #position()} alone. Positions are unique and never reused, so this is an exact
     * identity for entries while avoiding any dependence on the equality semantics of {@code T}.
     *
     * @param position the absolute position of this entry in the log
     * @param item     the item itself
     * @param <T>      the type of item held in the log
     */
    private record Entry<T>(long position, @NonNull T item) {

        @Override
        public boolean equals(final Object other) {
            return other instanceof Entry<?> entry && entry.position == position;
        }

        @Override
        public int hashCode() {
            return Long.hashCode(position);
        }

        @Override
        @NonNull
        public String toString() {
            return "Entry[position=" + position + ", item=" + item + "]";
        }
    }

    @NonNull
    private final ToLongFunction<T> getSequenceNumber;

    /**
     * Index from sequence number to the entries carrying it, used for removal. Its window also records which sequence
     * numbers have already been removed, which is what enforces the strictly increasing removal order.
     */
    @NonNull
    private final SequenceSet<Entry<T>> bySequenceNumber;

    /** The ring buffer. Always a power of two in length. Slots may hold {@code null} for tombstoned entries. */
    @NonNull
    private Object[] buffer;

    /** Always {@code buffer.length - 1}. Maps an absolute position onto a slot in {@link #buffer}. */
    private int mask;

    /** The absolute position of the oldest entry in the log. Equal to {@link #tail} when the log is empty. */
    private long head;

    /** The absolute position that will be assigned to the next item added. */
    private long tail;

    /** The number of live entries, which excludes tombstones. */
    private int size;

    /**
     * Creates a new log.
     *
     * @param firstSequenceNumber    the lowest sequence number permitted in the log at construction time
     * @param sequenceNumberCapacity the number of distinct sequence numbers permitted to be in the log at once; the log
     *                               expands this as needed when items arrive with higher sequence numbers, so this is a
     *                               starting point rather than a hard limit
     * @param initialCapacity        the number of items the log can hold before its buffer is grown; rounded up to a
     *                               power of two, with a minimum of {@value #MIN_CAPACITY}
     * @param getSequenceNumber      a function that extracts the sequence number from an item
     * @throws IllegalArgumentException if {@code sequenceNumberCapacity} or {@code initialCapacity} is not positive
     */
    public CursoredLog(
            final long firstSequenceNumber,
            final int sequenceNumberCapacity,
            final int initialCapacity,
            @NonNull final ToLongFunction<T> getSequenceNumber) {
        if (sequenceNumberCapacity <= 0) {
            throw new IllegalArgumentException(
                    "sequenceNumberCapacity must be positive, was " + sequenceNumberCapacity);
        }
        if (initialCapacity <= 0) {
            throw new IllegalArgumentException("initialCapacity must be positive, was " + initialCapacity);
        }

        final int capacity = tableSizeFor(initialCapacity);
        this.buffer = new Object[capacity];
        this.mask = capacity - 1;
        this.getSequenceNumber = getSequenceNumber;
        this.bySequenceNumber = new StandardSequenceSet<>(
                firstSequenceNumber,
                sequenceNumberCapacity,
                true,
                entry -> getSequenceNumber.applyAsLong(entry.item()));
    }

    /**
     * Appends an item to the end of the log.
     *
     * @param item the item to append
     * @return the position assigned to the item, which remains valid until the item is removed
     * @throws IllegalArgumentException if {@code sequenceNumber} has already been removed from the log
     */
    public synchronized long add(@NonNull final T item) {
        requireNonNull(item, "item must not be null");

        if (tail - head == buffer.length) {
            grow();
        }

        final long position = tail;
        final long sequenceNumber = getSequenceNumber.applyAsLong(item);
        final Entry<T> entry = new Entry<>(position, item);
        // Check if the item already exists in the log.
        if (!bySequenceNumber.add(entry)) {
            throw new IllegalArgumentException("sequence number " + sequenceNumber
                    + " has already been removed from this log, the lowest sequence number still accepted is "
                    + bySequenceNumber.getFirstSequenceNumberInWindow());
        }

        buffer[(int) (position & mask)] = entry;
        tail++;
        size++;
        return position;
    }

    /**
     * Removes every item carrying the given sequence number, wherever those items sit in the log.
     *
     * <p>Sequence numbers must be removed in strictly increasing order. Removing a sequence number also removes any
     * item still present with a <i>lower</i> sequence number, since the strictly increasing order means no later call
     * could ever remove it. Both this and the argument itself become invalid for future calls to {@link #add(Object)},
     * which keeps the backing index from growing without bound as sequence numbers climb.
     *
     * @param sequenceNumber the sequence number whose items are to be removed
     * @return the number of items removed
     * @throws IllegalArgumentException if {@code sequenceNumber} has already been removed
     */
    public synchronized int removeSequenceNumber(final long sequenceNumber) {
        final long firstInWindow = bySequenceNumber.getFirstSequenceNumberInWindow();
        if (sequenceNumber < firstInWindow) {
            throw new IllegalArgumentException("sequence numbers must be removed in strictly increasing order, but "
                    + sequenceNumber + " has already been removed; the lowest that may still be removed is "
                    + firstInWindow);
        }

        final int beforeRemoval = size;
        bySequenceNumber.shiftWindow(sequenceNumber + 1, this::tombstone);
        sweepHead();
        return beforeRemoval - size;
    }

    /**
     * Returns the item at an absolute position.
     *
     * @param position the position to read
     * @return the item at that position, or {@code null} if it has been removed or was never assigned
     */
    @Nullable
    public synchronized T get(final long position) {
        if (position < head || position >= tail) {
            return null;
        }
        final Entry<T> entry = entryAt(position);
        return entry == null ? null : entry.item();
    }

    /**
     * Returns the items carrying the given sequence number, in the order they were added to the log.
     *
     * <p>The returned list is a snapshot; modifying it does not affect the log.
     *
     * @param sequenceNumber the sequence number to read
     * @return the items carrying that sequence number, ordered by position
     */
    @NonNull
    public synchronized List<T> getItemsWithSequenceNumber(final long sequenceNumber) {
        final List<Entry<T>> entries = new ArrayList<>(bySequenceNumber.getEntriesWithSequenceNumber(sequenceNumber));
        entries.sort(Comparator.comparingLong(Entry::position));

        final List<T> items = new ArrayList<>(entries.size());
        for (final Entry<T> entry : entries) {
            items.add(entry.item());
        }
        return items;
    }

    /**
     * Returns the lowest sequence number the log still accepts. Everything below this has been removed and will be
     * rejected by {@link #add(Object)}.
     *
     * @return the first sequence number in the window
     */
    public synchronized long getFirstSequenceNumberInWindow() {
        return bySequenceNumber.getFirstSequenceNumberInWindow();
    }

    /**
     * Creates a cursor positioned at the oldest item in the log.
     *
     * @return a new cursor
     */
    @NonNull
    public synchronized Cursor<T> newCursor() {
        return new Cursor<>(this, head);
    }

    /**
     * Creates a cursor positioned past the newest item in the log, so that it yields only items added after this call.
     *
     * @return a new cursor
     */
    @NonNull
    public synchronized Cursor<T> newCursorAtEnd() {
        return new Cursor<>(this, tail);
    }

    /**
     * Returns the number of live items in the log, which excludes tombstones left by removal.
     *
     * @return the number of items in the log
     */
    public synchronized int size() {
        return size;
    }

    /**
     * Returns whether the log holds no items.
     *
     * @return {@code true} if the log is empty
     */
    public synchronized boolean isEmpty() {
        return size == 0;
    }

    /**
     * Returns the number of positions spanned by the log, which is the number of live items plus the number of
     * tombstones that have not yet reached the front. Compare against {@link #size()} to gauge how much of the buffer
     * is being held by tombstones.
     *
     * @return the distance between the first position and the next position to be assigned
     */
    public synchronized long span() {
        return tail - head;
    }

    /**
     * Returns the position of the oldest item in the log, or the position that the next item added will occupy if the
     * log is empty.
     *
     * @return the first position in the log
     */
    public synchronized long firstPosition() {
        return head;
    }

    /**
     * Returns the position that the next item added will occupy. No item currently occupies this position.
     *
     * @return the position past the end of the log
     */
    public synchronized long nextPosition() {
        return tail;
    }

    /**
     * Removes every item from the log and resets the window of accepted sequence numbers to the one the log was
     * constructed with. Positions are not reused; cursors that survive a clear resume at the end of the log.
     */
    public synchronized void clear() {
        for (long position = head; position < tail; position++) {
            buffer[(int) (position & mask)] = null;
        }
        bySequenceNumber.clear();
        head = tail;
        size = 0;
    }

    /**
     * Nulls the slot held by an entry that has been dropped from the sequence number index.
     *
     * @param entry the entry to tombstone
     */
    private void tombstone(@NonNull final Entry<T> entry) {
        final long position = entry.position();
        if (position < head || position >= tail) {
            // already reclaimed by clear
            return;
        }
        buffer[(int) (position & mask)] = null;
        size--;
    }

    /**
     * Advances the front of the log past any tombstones that have reached it, so that their slots become reusable and
     * the entries they held become eligible for collection.
     */
    private void sweepHead() {
        while (head < tail && buffer[(int) (head & mask)] == null) {
            head++;
        }
    }

    /**
     * Returns the first position holding a live entry at or after {@code from}, clamped to the front of the log.
     * Returns {@link #tail} if there is no such position.
     *
     * @param from the position to search from
     * @return the next live position, or {@link #tail} if there is none
     */
    private long nextLive(final long from) {
        long position = Math.max(from, head);
        while (position < tail && buffer[(int) (position & mask)] == null) {
            position++;
        }
        return position;
    }

    /**
     * Reads the entry at a position that is known to be within {@code [head, tail)}.
     *
     * @param position the position to read
     * @return the entry at that position, or {@code null} if the slot holds a tombstone
     */
    @SuppressWarnings("unchecked")
    @Nullable
    private Entry<T> entryAt(final long position) {
        return (Entry<T>) buffer[(int) (position & mask)];
    }

    /**
     * Doubles the size of the buffer, preserving the absolute position of every entry.
     */
    private void grow() {
        if (buffer.length >= MAX_CAPACITY) {
            throw new IllegalStateException("log has reached the maximum capacity of " + MAX_CAPACITY + " positions");
        }

        final Object[] next = new Object[buffer.length << 1];
        final int nextMask = next.length - 1;
        // copied by position rather than compacted, so that positions - and therefore cursors - stay valid
        for (long position = head; position < tail; position++) {
            next[(int) (position & nextMask)] = buffer[(int) (position & mask)];
        }
        buffer = next;
        mask = nextMask;
    }

    /**
     * Rounds a requested capacity up to a power of two no smaller than {@value #MIN_CAPACITY}.
     *
     * @param capacity the requested capacity
     * @return the capacity to allocate
     */
    private static int tableSizeFor(final int capacity) {
        int result = MIN_CAPACITY;
        while (result < capacity && result < MAX_CAPACITY) {
            result <<= 1;
        }
        return result;
    }

    /**
     * A movable read position within a {@link CursoredLog}. Any number of cursors may exist over the same log, each at
     * a different position, and each is unaffected by the others.
     *
     * <p>A cursor is an {@link Iterator} over the items at and after its position. It follows the end of the log: once
     * {@link #hasNext()} has returned {@code false}, it will return {@code true} again if items are subsequently added.
     * Items removed from the log are skipped.
     *
     * <p>If the front of the log advances past a cursor - because items it had not yet read were removed - the cursor
     * is silently moved forward to the oldest item still present. Compare {@link #position()} before and after reading
     * to detect that this has happened.
     */
    public static final class Cursor<T> implements Iterator<T> {

        /** The log this cursor reads from. */
        private final CursoredLog<T> log;

        /** The position this cursor will read next. */
        private long position;

        /**
         * Creates a cursor over a log at the given position.
         *
         * @param log      the log to read from
         * @param position the position to start at
         */
        private Cursor(@NonNull final CursoredLog<T> log, final long position) {
            this.log = log;
            this.position = position;
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public boolean hasNext() {
            synchronized (log) {
                // resolving here means a run of tombstones is only ever scanned once by a given cursor
                position = log.nextLive(position);
                return position < log.tail;
            }
        }

        /**
         * {@inheritDoc}
         *
         * @throws NoSuchElementException if there is no item at or after this cursor
         */
        @Override
        @NonNull
        public T next() {
            synchronized (log) {
                position = log.nextLive(position);
                if (position >= log.tail) {
                    throw new NoSuchElementException("no items at or after position " + position);
                }
                final Entry<T> entry = log.entryAt(position);
                position++;
                assert entry != null;
                return entry.item();
            }
        }

        /**
         * Returns the item this cursor would read next without advancing the cursor.
         *
         * @return the next item, or {@code null} if there is none
         */
        @Nullable
        public T peek() {
            synchronized (log) {
                position = log.nextLive(position);
                if (position >= log.tail) {
                    return null;
                }
                final Entry<T> entry = log.entryAt(position);
                assert entry != null;
                return entry.item();
            }
        }

        /**
         * Returns the position this cursor will read next. This is the position of the next live item, which may be
         * later than the position most recently passed to {@link #seek(long)} if items in between have been removed.
         *
         * @return the position of this cursor
         */
        public long position() {
            synchronized (log) {
                position = log.nextLive(position);
                return position;
            }
        }

        /**
         * Moves this cursor to an arbitrary position, from which iteration continues. The position is clamped to the
         * bounds of the log, so seeking before the first item starts at the first item and seeking past the last item
         * starts at the next item to be added.
         *
         * @param newPosition the position to move to
         */
        public void seek(final long newPosition) {
            synchronized (log) {
                position = Math.clamp(newPosition, log.head, log.tail);
            }
        }

        /**
         * Moves this cursor to the oldest item in the log.
         */
        public void seekToFirst() {
            synchronized (log) {
                position = log.head;
            }
        }

        /**
         * Moves this cursor past the newest item in the log, so that it yields only items added after this call.
         */
        public void seekToEnd() {
            synchronized (log) {
                position = log.tail;
            }
        }

        /**
         * {@inheritDoc}
         */
        @Override
        @NonNull
        public String toString() {
            return "Cursor[position=" + position() + "]";
        }
    }
}
