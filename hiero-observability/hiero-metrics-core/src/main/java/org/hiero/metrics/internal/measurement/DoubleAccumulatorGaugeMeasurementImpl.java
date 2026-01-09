// SPDX-License-Identifier: Apache-2.0
package org.hiero.metrics.internal.measurement;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Objects;
import java.util.concurrent.atomic.DoubleAccumulator;
import java.util.function.DoubleBinaryOperator;
import java.util.function.DoubleSupplier;
import org.hiero.metrics.api.measurement.DoubleAccumulatorGaugeMeasurement;

public final class DoubleAccumulatorGaugeMeasurementImpl implements DoubleAccumulatorGaugeMeasurement {

    private final DoubleAccumulator accumulator;

    public DoubleAccumulatorGaugeMeasurementImpl(
            @NonNull DoubleBinaryOperator operator, @NonNull DoubleSupplier initializer) {
        Objects.requireNonNull(operator, "operator must not be null");
        Objects.requireNonNull(initializer, "initializer must not be null");

        accumulator = new DoubleAccumulator(operator, initializer.getAsDouble());
    }

    @Override
    public void accumulate(double value) {
        accumulator.accumulate(value);
    }

    public double get() {
        return accumulator.get();
    }

    public double getAndReset() {
        return accumulator.getThenReset();
    }

    public void reset() {
        accumulator.reset();
    }
}
