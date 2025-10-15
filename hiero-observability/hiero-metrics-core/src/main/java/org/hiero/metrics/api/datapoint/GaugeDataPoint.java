// SPDX-License-Identifier: Apache-2.0
package org.hiero.metrics.api.datapoint;

import java.util.function.DoubleSupplier;
import java.util.function.Supplier;

/**
 * A gauge data point for a generic type that is converted to {@code double}.
 * <p>
 * This interface extends {@link DoubleSupplier} to provide the current converted value of the gauge.
 * <p>
 * <b>All operations are thread-safe and atomic.</b>
 *
 * @param <T> the type of value used to update the gauge
 */
public interface GaugeDataPoint<T> extends DataPoint, Supplier<T> {

    /**
     * Update the gauge with a new value.
     *
     * @param value the new value
     */
    void update(T value);
}
