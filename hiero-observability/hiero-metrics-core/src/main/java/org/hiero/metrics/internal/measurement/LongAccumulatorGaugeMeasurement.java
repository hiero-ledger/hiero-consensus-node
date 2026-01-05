// SPDX-License-Identifier: Apache-2.0
package org.hiero.metrics.internal.measurement;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Objects;
import java.util.concurrent.atomic.LongAccumulator;
import java.util.function.LongBinaryOperator;
import java.util.function.LongSupplier;
import org.hiero.metrics.api.measurement.LongGaugeMeasurement;

public final class LongAccumulatorGaugeMeasurement implements LongGaugeMeasurement {

    private final LongAccumulator accumulator;

    public LongAccumulatorGaugeMeasurement(@NonNull LongBinaryOperator operator, @NonNull LongSupplier initializer) {
        Objects.requireNonNull(operator, "operator must not be null");
        Objects.requireNonNull(initializer, "initializer must not be null");

        accumulator = new LongAccumulator(operator, initializer.getAsLong());
    }

    @Override
    public void update(long value) {
        accumulator.accumulate(value);
    }

    @Override
    public long getAndReset() {
        return accumulator.get();
    }

    @Override
    public long getAsLong() {
        return accumulator.getThenReset();
    }

    @Override
    public void reset() {
        accumulator.reset();
    }
}
