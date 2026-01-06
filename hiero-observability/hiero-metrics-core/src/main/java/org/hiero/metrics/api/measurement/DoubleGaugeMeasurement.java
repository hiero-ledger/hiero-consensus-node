// SPDX-License-Identifier: Apache-2.0
package org.hiero.metrics.api.measurement;

import java.util.function.DoubleSupplier;
import org.hiero.metrics.internal.core.MetricUtils;

/**
 * A gauge measurement that holds a {@code double} value that can be updated using {@link #update(double)} to any value.
 * <br>
 * Some implementations can use additional calculations/aggregations on observed values.
 * <p>
 * This interface extends {@link DoubleSupplier} to provide the current value of the gauge.
 * <p>
 * <b>All operations are thread-safe and atomic.</b>
 */
public interface DoubleGaugeMeasurement extends DoubleSupplier, Measurement {

    /**
     * Update the gauge by {@code 1.0}.
     */
    default void update() {
        update(MetricUtils.ONE);
    }

    /**
     * Update the gauge by the specified value.
     * The value can be positive or negative.
     *
     * @param value the value to update the gauge by
     */
    void update(double value);

    /**
     * Reset the gauge to its initial value and return the value before reset.
     *
     * @return the value before reset
     */
    double getAndReset();

    /**
     * {@inheritDoc}
     */
    @Override
    default void reset() {
        getAndReset();
    }
}
