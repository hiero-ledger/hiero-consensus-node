// SPDX-License-Identifier: Apache-2.0
package org.hiero.metrics.api.datapoint;

import static org.hiero.metrics.api.stat.StatUtils.ONE;
import static org.hiero.metrics.api.stat.StatUtils.ZERO;

import java.util.function.BooleanSupplier;
import java.util.function.DoubleSupplier;

/**
 * A {@link DataPoint} that represents a {@code boolean} gauge value.
 * <p>
 * The value can be set to {@code true} or {@code false} using {@link #setTrue()} and {@link #setFalse()} methods,
 * or to an arbitrary {@code boolean} value using {@link #set(boolean)} method.
 * <p>
 * The current value can be retrieved using the {@link #getAsBoolean()} or {@link #getAsDouble()}.
 * <p>
 * <b>All operations are thread-safe and atomic.</b>
 */
public interface BooleanGaugeDataPoint extends BooleanSupplier, DoubleSupplier, DataPoint {

    /**
     * Sets the value of this boolean gauge data point.
     *
     * @param value the new boolean value to set
     */
    void set(boolean value);

    /**
     * Sets the value of this boolean gauge data point to {@code true}.
     */
    default void setTrue() {
        set(true);
    }

    /**
     * Sets the value of this boolean gauge data point to {@code false}.
     */
    default void setFalse() {
        set(false);
    }

    /**
     * Gets the current value of this data point as {@code double},
     * where {@code true} is represented as {@code 1.0} and {@code false} as {@code 0.0}.
     *
     * @return the current {@code boolean} value converted to {@code double}
     */
    @Override
    default double getAsDouble() {
        return getAsBoolean() ? ONE : ZERO;
    }
}
