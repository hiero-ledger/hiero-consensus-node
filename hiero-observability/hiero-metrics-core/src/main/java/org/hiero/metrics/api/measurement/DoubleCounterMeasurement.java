// SPDX-License-Identifier: Apache-2.0
package org.hiero.metrics.api.measurement;

import org.hiero.metrics.internal.core.MetricUtils;

/**
 * A measurement representing a {@code double} counter that can only be incremented with non-negative value.
 * Increments are thread-safe and atomic.
 */
public interface DoubleCounterMeasurement {

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
