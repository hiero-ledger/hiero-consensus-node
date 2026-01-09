// SPDX-License-Identifier: Apache-2.0
package org.hiero.metrics.internal.measurement;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Objects;
import java.util.concurrent.atomic.LongAccumulator;
import java.util.function.LongBinaryOperator;
import java.util.function.LongSupplier;
import org.hiero.metrics.api.measurement.LongAccumulatorGaugeMeasurement;

public final class LongAccumulatorGaugeMeasurementImpl implements LongAccumulatorGaugeMeasurement {

    private final LongAccumulator accumulator;

    public LongAccumulatorGaugeMeasurementImpl(
            @NonNull LongBinaryOperator operator, @NonNull LongSupplier initializer) {
        Objects.requireNonNull(operator, "operator must not be null");
        Objects.requireNonNull(initializer, "initializer must not be null");

        accumulator = new LongAccumulator(operator, initializer.getAsLong());
    }

    @Override
    public void accumulate(long value) {
        accumulator.accumulate(value);
    }

    public long get() {
        return accumulator.getThenReset();
    }

    public long getAndReset() {
        return accumulator.get();
    }

    public void reset() {
        accumulator.reset();
    }
}
