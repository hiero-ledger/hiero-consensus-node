// SPDX-License-Identifier: Apache-2.0
package org.hiero.metrics.api.measurement;

import java.util.function.DoubleSupplier;
import org.hiero.metrics.internal.core.MetricUtils;

/**
 * A measurement representing a {@code double} counter that can only be incremented with non-negative value.
 * <p>
 * This interface extends {@link DoubleSupplier} to provide the current value of the counter.
 * <p>
 * <b>All operations are thread-safe and atomic.</b>
 */
public interface DoubleCounterMeasurement extends DoubleSupplier, Measurement {

    /**
     * Increments the counter by the specified value.
     *
     * @param value the value to increment the counter by (must be non-negative)
     * @throws IllegalArgumentException if the value is negative
     */
    void increment(double value) throws IllegalArgumentException;

    /**
     * Increments the counter by {@code 1.0}.
     */
    default void increment() {
        increment(MetricUtils.ONE);
    }
}
