// SPDX-License-Identifier: Apache-2.0
package org.hiero.metrics.internal.datapoint;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Objects;
import java.util.function.LongBinaryOperator;
import java.util.function.LongSupplier;

public final class LongAccumulatorGaugeDataPoint extends AtomicLongGaugeDataPoint {

    private final LongBinaryOperator operator;

    public LongAccumulatorGaugeDataPoint(@NonNull LongBinaryOperator operator, @NonNull LongSupplier initializer) {
        super(initializer);
        this.operator = Objects.requireNonNull(operator, "operator must not be null");
    }

    @Override
    public void update(long value) {
        container.accumulateAndGet(value, operator);
    }
}
