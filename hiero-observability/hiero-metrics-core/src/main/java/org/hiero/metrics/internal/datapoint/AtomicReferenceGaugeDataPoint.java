// SPDX-License-Identifier: Apache-2.0
package org.hiero.metrics.internal.datapoint;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Objects;
import java.util.function.Supplier;
import java.util.function.ToDoubleFunction;
import org.hiero.metrics.api.datapoint.GaugeDataPoint;

public final class AtomicReferenceGaugeDataPoint<T> implements GaugeDataPoint<T> {

    private final Supplier<T> initializer;
    private final ToDoubleFunction<T> valueConverter;
    private volatile T value;

    public AtomicReferenceGaugeDataPoint(@NonNull ToDoubleFunction<T> valueConverter) {
        this(() -> null, valueConverter);
    }

    public AtomicReferenceGaugeDataPoint(
            @NonNull Supplier<T> initializer, @NonNull ToDoubleFunction<T> valueConverter) {
        this.initializer = Objects.requireNonNull(initializer, "initializer cannot be null");
        this.valueConverter = Objects.requireNonNull(valueConverter, "value converter cannot be null");
        reset();
    }

    @Override
    public void update(T value) {
        this.value = value;
    }

    @Override
    public double getAsDouble() {
        T val = this.value;
        if (val == null) {
            return Double.NaN;
        }
        return valueConverter.applyAsDouble(val);
    }

    @Override
    public void reset() {
        value = initializer.get();
    }
}
