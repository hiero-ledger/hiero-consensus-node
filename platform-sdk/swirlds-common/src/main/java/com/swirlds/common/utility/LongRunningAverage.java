// SPDX-License-Identifier: Apache-2.0
package com.swirlds.common.utility;

import static com.swirlds.common.formatting.StringFormattingUtils.formattedList;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * <p>
 * Tracks a running average of the last N long values. This class does not "forget" values based on time, it
 * only cares about the last N values regardless of when they were added relative to the clock. It does not
 * do any special weighting, all of the last N values are weighted equally.
 * </p>
 *
 * <p>
 * By design, this class is not thread safe. If thread safety is require then the caller must synchronize.
 * </p>
 */
public class LongRunningAverage {

    private final Deque<Long> values;
    private final int capacity;
    private long runningSum;

    /**
     * Create an object for tracking the running average.
     *
     * @param capacity
     * 		the number of recent values to include in the average
     */
    public LongRunningAverage(final int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be non-zero positive");
        }
        this.values = new ArrayDeque<>(capacity);
        this.capacity = capacity;
    }

    /**
     * Add a value to the running average. Removes the oldest value from the
     * running average if too many values have been observed.
     *
     * @param value
     * 		the value to add
     */
    public void add(final long value) {
        if (values.size() == capacity) {
            runningSum -= values.removeFirst();
        }

        values.addLast(value);
        runningSum += value;
    }

    /**
     * Get the current running average, or 0 if no values have been added.
     *
     * @return the current running average
     */
    public long getAverage() {
        if (values.isEmpty()) {
            return 0;
        }

        return runningSum / values.size();
    }

    /**
     * Get the number of elements currently being averaged.
     *
     * @return the number of elements currently being averaged
     */
    public int size() {
        return values.size();
    }

    /**
     * Check if any values have been added to the running average.
     *
     * @return true if no values have been added, otherwise false
     */
    public boolean isEmpty() {
        return values.isEmpty();
    }

    @Override
    public String toString() {
        final StringBuilder sb = new StringBuilder();

        sb.append(getAverage()).append(" [");
        formattedList(sb, values.iterator());
        sb.append("]");

        return sb.toString();
    }
}
