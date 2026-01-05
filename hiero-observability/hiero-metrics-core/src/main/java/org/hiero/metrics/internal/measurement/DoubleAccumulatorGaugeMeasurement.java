// SPDX-License-Identifier: Apache-2.0
package org.hiero.metrics.internal.measurement;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Objects;
import java.util.concurrent.atomic.DoubleAccumulator;
import java.util.function.DoubleBinaryOperator;
import java.util.function.DoubleSupplier;
import org.hiero.metrics.api.measurement.DoubleGaugeMeasurement;

public final class DoubleAccumulatorGaugeMeasurement implements DoubleGaugeMeasurement {

    private final DoubleAccumulator accumulator;

    public DoubleAccumulatorGaugeMeasurement(
            @NonNull DoubleBinaryOperator operator, @NonNull DoubleSupplier initializer) {
        Objects.requireNonNull(operator, "operator must not be null");
        Objects.requireNonNull(initializer, "initializer must not be null");

        accumulator = new DoubleAccumulator(operator, initializer.getAsDouble());
    }

    @Override
    public void update(double value) {
        accumulator.accumulate(value);
    }

    @Override
    public double getAndReset() {
        return accumulator.getThenReset();
    }

    @Override
    public double getAsDouble() {
        return accumulator.get();
    }

    @Override
    public void reset() {
        accumulator.reset();
    }
}
