// SPDX-License-Identifier: Apache-2.0
package org.hiero.metrics.internal.measurement;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.DoubleSupplier;
import org.hiero.metrics.api.measurement.DoubleGaugeMeasurement;

public final class AtomicDoubleGaugeMeasurement implements DoubleGaugeMeasurement {

    private final DoubleSupplier initializer;
    private final AtomicLong container;

    public AtomicDoubleGaugeMeasurement(@NonNull DoubleSupplier initializer) {
        this.initializer = Objects.requireNonNull(initializer, "initializer must not be null");
        container = new AtomicLong();
        reset();
    }

    @Override
    public void update(double value) {
        container.set(fromDouble(value));
    }

    @Override
    public double getAndReset() {
        return toDouble(container.getAndSet(fromDouble(initializer.getAsDouble())));
    }

    @Override
    public double getAsDouble() {
        return toDouble(container.get());
    }

    @Override
    public void reset() {
        update(initializer.getAsDouble());
    }

    private static long fromDouble(double value) {
        return Double.doubleToRawLongBits(value);
    }

    private static double toDouble(long value) {
        return Double.longBitsToDouble(value);
    }
}
