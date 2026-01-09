// SPDX-License-Identifier: Apache-2.0
package org.hiero.metrics.api.measurement;

/**
 * A measurement representing a {@code long} gauge allowing to accumulate result using some
 * {@link java.util.function.LongBinaryOperator}.
 * Accumulates are thread-safe and atomic.
 */
public interface LongAccumulatorGaugeMeasurement {

    /**
     * Accumulate the given value to the current gauge value using the configured operator.
     *
     * @param value the value to accumulate
     */
    void accumulate(long value);

    /**
     * Accumulate {@code 1L} to the current gauge value using the configured operator.
     */
    default void accumulate() {
        accumulate(1L);
    }
}
