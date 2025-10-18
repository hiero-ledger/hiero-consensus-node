// SPDX-License-Identifier: Apache-2.0
package org.hiero.metrics.api.datapoint;

import java.util.function.LongSupplier;

/**
 * A gauge data point that holds a <code>long</code> value that can be updated using {@link #update(long)} to any value.
 * Some implementations can use additional calculations/aggregations on observed values.
 * <p>
 * This interface extends {@link LongSupplier} to provide the current value of the gauge.
 * Additionally, {@link #getAndReset()} returns the gauge value followed by a reset
 * to its initial state defined by {@link #getInitValue()}.
 * <p>
 * <b>All operations are thread-safe and atomic.</b>
 */
public interface LongGaugeDataPoint extends LongSupplier, DataPoint {

    /**
     * Gets the initial value of the gauge when it was created.
     *
     * @return the initial value of the gauge
     */
    long getInitValue();

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
