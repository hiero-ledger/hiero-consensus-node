// SPDX-License-Identifier: Apache-2.0
package org.hiero.metrics.api.measurement;

import org.hiero.metrics.internal.core.MetricUtils;

/**
 * A measurement representing a {@code double} gauge allowing to accumulate result using some
 * {@link java.util.function.DoubleBinaryOperator}.
 * Accumulates are thread-safe and atomic.
 */
public interface DoubleAccumulatorGaugeMeasurement {

    /**
     * Accumulate the given value to the current gauge value using the configured operator.
     *
     * @param value the value to accumulate
     */
    void accumulate(double value);

    /**
     * Accumulate {@code 1.0} to the current gauge value using the configured operator.
     */
    default void accumulate() {
        accumulate(MetricUtils.ONE);
    }
}
