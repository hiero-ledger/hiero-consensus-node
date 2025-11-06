// SPDX-License-Identifier: Apache-2.0
package org.hiero.metrics.api.datapoint;

import java.util.function.LongSupplier;

/**
 * A {@link DataPoint} representing a {@code long} counter that can only be incremented with non-negative value.
 * <p>
 * This interface extends {@link LongSupplier} to provide the current value of the counter.
 * <p>
 * <b>All operations are thread-safe and atomic.</b>
 */
public interface LongCounterDataPoint extends LongSupplier, DataPoint {

    /**
     * Increments the counter by the specified value.
     *
     * @param value the value to increment the counter by (should be non-negative)
     * @throws IllegalArgumentException if the value is negative
     */
    void increment(long value) throws IllegalArgumentException;

    /**
     * Increments the counter by <code>1</code>.
     */
    default void increment() {
        increment(1);
    }
}
