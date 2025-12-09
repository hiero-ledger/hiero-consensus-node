// SPDX-License-Identifier: Apache-2.0
package org.hiero.metrics.internal.measurement;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Objects;
import java.util.function.Supplier;
import org.hiero.metrics.api.measurement.GaugeMeasurement;

public final class AtomicReferenceGaugeMeasurement<T> implements GaugeMeasurement<T> {

    private final Supplier<T> initializer;
    private volatile T value;

    public AtomicReferenceGaugeMeasurement(@NonNull Supplier<T> initializer) {
        this.initializer = Objects.requireNonNull(initializer, "initializer must not be null");
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
