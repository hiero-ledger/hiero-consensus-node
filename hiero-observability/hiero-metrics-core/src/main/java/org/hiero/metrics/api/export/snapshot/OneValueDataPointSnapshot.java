// SPDX-License-Identifier: Apache-2.0
package org.hiero.metrics.api.export.snapshot;

import java.util.function.DoubleSupplier;
import java.util.function.LongSupplier;

/**
 * Extension of {@link DataPointSnapshot} for a single {@code double} or {@code long} value.
 * {@link #isFloatingPoint()} indicates which type of value is stored and must be called to determine
 * {@link #getAsDouble()} or {@link #getAsLong()} to call.
 */
public interface OneValueDataPointSnapshot extends DataPointSnapshot, DoubleSupplier, LongSupplier {

    boolean isFloatingPoint();

    @Override
    default double getAsDouble() {
        return Double.longBitsToDouble(getAsLong());
    }
}
