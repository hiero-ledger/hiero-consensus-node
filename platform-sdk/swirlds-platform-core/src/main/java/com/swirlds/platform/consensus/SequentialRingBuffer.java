// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.consensus;

import com.swirlds.common.utility.RandomAccessDeque;
import com.swirlds.logging.legacy.LogMarker;
import edu.umd.cs.findbugs.annotations.Nullable;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Stores a sequence of elements ordered by their index. The index for an element never changes, even when elements are
 * removed. Maintains an ordered sequence even if creation happens out of order. Intentionally tries to handle bad calls
 * without throwing.
 */
public class SequentialRingBuffer<T> {
    private static final Logger logger = LogManager.getLogger(SequentialRingBuffer.class);
    /** the default capacity for elements */
    private static final int DEFAULT_CAPACITY = 1000;
    /** a deque of elements */
    private final RandomAccessDeque<T> elements;
    /** the minimum index stored */
    private long minIndex;

    /**
     * @param startingIndex   the lowest index to store
     * @param initialCapacity the initial capacity for elements, will be automatically adjusted if exceeded
     */
    public SequentialRingBuffer(final long startingIndex, final int initialCapacity) {
        this.elements = new RandomAccessDeque<>(initialCapacity);
        this.minIndex = startingIndex;
    }

    public SequentialRingBuffer(final long startingIndex) {
        this(startingIndex, DEFAULT_CAPACITY);
    }

    /**
     * Reset this instance. Remove all elements currently stored and sets the minimum index as supplied.
     *
     * @param minIndex the lowest index to store
     */
    public void reset(final long minIndex) {
        elements.clear();
        this.minIndex = minIndex;
    }

    /**
     * Checks if this index exists
     *
     * @param index the index number to check
     * @return true if this index exists
     */
    public boolean exists(final long index) {
        return index >= minIndex && index < nextIndex();
    }

    /**
     * Create the element with the supplied index. The expectation is that this should always be called with the index
     * value of {@link #nextIndex()}. If it is called with another value, the instance will try its best to comply,
     * logging the issue.
     *
     * @param index   the index number to store the value at
     * @param element the element to add
     */
    public void add(final long index, @Nullable final T element) {
        if (index < minIndex) {
            logger.error(
                    LogMarker.EXCEPTION.getMarker(),
                    "SequentialRingBuffer.add called for an old, discarded index."
                            + " Index requested:{}, Min index:{}, Next index:{} ",
                    index,
                    minIndex,
                    nextIndex());
            return;
        }
        if (index < nextIndex()) {
            logger.error(
                    LogMarker.EXCEPTION.getMarker(),
                    "SequentialRingBuffer.add called for an existing index."
                            + " Index requested:{}, Min index:{}, Next index:{} ",
                    index,
                    minIndex,
                    nextIndex());
            elements.set(dequeIndex(index), element);
            return;
        }
        if (index != nextIndex()) {
            logger.error(
                    LogMarker.EXCEPTION.getMarker(),
                    "SequentialRingBuffer.add called for a index that is not the next one."
                            + " Index requested:{}, Min index:{}, Next index:{} ",
                    index,
                    minIndex,
                    nextIndex());
            for (long i = nextIndex(); i < index; i++) {
                elements.addLast(null);
            }
            elements.addLast(element);
            return;
        }
        elements.addLast(element);
    }

    /**
     * Remove all indexes later than the supplied one
     *
     * @param index the latest index to keep
     */
    public void removeNewerThan(final long index) {
        for (long i = maxIndex(); i > index && exists(i); i--) {
            elements.removeLast();
        }
    }

    /**
     * Remove all indexes older than the supplied index
     *
     * @param index the oldest index to keep
     */
    public void removeOlderThan(final long index) {
        while (minIndex < index && minIndex <= maxIndex()) {
            elements.removeFirst();
            minIndex++;
        }
    }

    /**
     * Get the requested element
     *
     * @param index the index number
     * @return the element or null if it doesn't exist
     */
    public @Nullable T get(final long index) {
        if (exists(index)) {
            return elements.get(dequeIndex(index));
        }
        return null;
    }

    /**
     * @return the latest element stored, or null if none are stored
     */
    public @Nullable T getLatest() {
        return get(maxIndex());
    }

    /**
     * @return the minimum index stored
     */
    public long minIndex() {
        return minIndex;
    }

    /**
     * @return the maximum index stored
     */
    public long maxIndex() {
        return nextIndex() - 1;
    }

    /**
     * @return the next index in line to be created
     */
    public long nextIndex() {
        return minIndex + elements.size();
    }

    private int dequeIndex(final long index) {
        final long dequeIndex = index - minIndex;
        if ((int) dequeIndex != dequeIndex) {
            return 0;
        }
        return (int) dequeIndex;
    }
}
