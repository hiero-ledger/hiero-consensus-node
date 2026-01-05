// SPDX-License-Identifier: Apache-2.0
package org.hiero.metrics.internal.measurement;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Objects;
import java.util.function.DoubleSupplier;
import org.hiero.metrics.api.core.AtomicDouble;
import org.hiero.metrics.api.measurement.DoubleGaugeMeasurement;

public final class AtomicDoubleGaugeMeasurement implements DoubleGaugeMeasurement {

    private final AtomicDouble container;

    public AtomicDoubleGaugeMeasurement(@NonNull DoubleSupplier initializer) {
        Objects.requireNonNull(initializer, "initializer must not be null");
        container = new AtomicDouble(initializer);
    }

    @Override
    public void update(double value) {
        container.set(value);
    }

    @Override
    public double getAndReset() {
        return container.getAndReset();
    }

    @Override
    public double getAsDouble() {
        return container.getAsDouble();
    }

    @Override
    public void reset() {
        container.reset();
    }
}
