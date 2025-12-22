// SPDX-License-Identifier: Apache-2.0
package org.hiero.metrics.internal.measurement;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.function.DoubleBinaryOperator;
import java.util.function.DoubleSupplier;
import java.util.function.LongBinaryOperator;
import org.hiero.metrics.api.core.AtomicDouble;

public final class DoubleAccumulatorGaugeMeasurement extends AtomicDoubleGaugeMeasurement {

    private final LongBinaryOperator operator;

    public DoubleAccumulatorGaugeMeasurement(
            @NonNull DoubleBinaryOperator operator, @NonNull DoubleSupplier initializer) {
        super(initializer);
        this.operator = AtomicDouble.convertBinaryOperator(operator);
    }

    @Override
    public void update(double value) {
        container.accumulateAndGet(value, operator);
    }
}
