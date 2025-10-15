// SPDX-License-Identifier: Apache-2.0
package org.hiero.metrics.internal.datapoint;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Objects;
import java.util.function.Supplier;
import org.hiero.metrics.api.datapoint.GaugeDataPoint;

public final class AtomicReferenceGaugeDataPoint<T> implements GaugeDataPoint<T> {

    private final Supplier<T> initializer;
    private volatile T value;

    public AtomicReferenceGaugeDataPoint() {
        this(() -> null);
    }

    public AtomicReferenceGaugeDataPoint(@NonNull Supplier<T> initializer) {
        this.initializer = Objects.requireNonNull(initializer, "initializer cannot be null");
        reset();
    }

    @Override
    public void update(T value) {
        this.value = value;
    }

    @Override
    public T get() {
        return value;
    }

    @Override
    public void reset() {
        value = initializer.get();
    }
}
