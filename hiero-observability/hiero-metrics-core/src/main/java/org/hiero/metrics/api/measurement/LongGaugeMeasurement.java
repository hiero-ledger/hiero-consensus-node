// SPDX-License-Identifier: Apache-2.0
package org.hiero.metrics.api.measurement;

import java.util.function.LongSupplier;

/**
 * A gauge measurement that holds a {@code long} value that can be updated using {@link #update(long)} to any value.
 * Some implementations can use additional calculations/aggregations on observed values.
 * <p>
 * This interface extends {@link LongSupplier} to provide the current value of the gauge.
 * <p>
 * <b>All operations are thread-safe and atomic.</b>
 */
public interface LongGaugeMeasurement extends LongSupplier, Measurement {

    /**
     * Updates the gauge by adding the specified value.
     * The value can be positive or negative.
     *
     * @param value the value to update the gauge
     */
    void update(long value);

    /**
     * Resets the gauge to its initial value and returns the value before reset.
     *
     * @return the value before reset
     */
    long getAndReset();

    /**
     * {@inheritDoc}
     */
    @Override
    default void reset() {
        getAndReset();
    }
}
