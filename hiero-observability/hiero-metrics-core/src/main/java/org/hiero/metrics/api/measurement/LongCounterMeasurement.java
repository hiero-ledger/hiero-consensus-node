// SPDX-License-Identifier: Apache-2.0
package org.hiero.metrics.api.measurement;

/**
 * A measurement representing a {@code long} counter that can only be incremented with non-negative value.
 * All increments are thread-safe and atomic.
 */
public interface LongCounterMeasurement {

    /**
     * Increments the counter by the specified value.
     *
     * @param value the value to increment the counter by (should be non-negative)
     * @throws IllegalArgumentException if the value is negative
     */
    void increment(long value) throws IllegalArgumentException;

    /**
     * Increments the counter by {@code 1L}.
     */
    default void increment() {
        increment(1L);
    }
}
