// SPDX-License-Identifier: Apache-2.0
package org.hiero.metrics.internal.datapoint;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Objects;
import java.util.function.DoubleSupplier;
import org.hiero.metrics.api.datapoint.DoubleGaugeDataPoint;
import org.hiero.metrics.api.stat.container.AtomicDouble;

public class AtomicDoubleGaugeDataPoint implements DoubleGaugeDataPoint {

    protected final AtomicDouble container;

    public AtomicDoubleGaugeDataPoint(@NonNull DoubleSupplier initializer) {
        Objects.requireNonNull(initializer, "initializer must not be null");
        container = new AtomicDouble(initializer);
    }

    @Override
    public double getInitValue() {
        return container.getInitValue();
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
