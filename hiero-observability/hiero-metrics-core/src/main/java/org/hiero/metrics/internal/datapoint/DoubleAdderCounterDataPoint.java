// SPDX-License-Identifier: Apache-2.0
package org.hiero.metrics.internal.datapoint;

import static org.hiero.metrics.api.stat.StatUtils.ZERO;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Objects;
import java.util.concurrent.atomic.DoubleAdder;
import java.util.function.DoubleSupplier;
import org.hiero.metrics.api.datapoint.DoubleCounterDataPoint;
import org.hiero.metrics.api.stat.StatUtils;

public final class DoubleAdderCounterDataPoint implements DoubleCounterDataPoint {

    private final DoubleSupplier initializer;
    private final DoubleAdder container = new DoubleAdder();

    public DoubleAdderCounterDataPoint() {
        this(StatUtils.DOUBLE_INIT);
    }

    public DoubleAdderCounterDataPoint(@NonNull DoubleSupplier initializer) {
        this.initializer = Objects.requireNonNull(initializer, "initializer must not be null");
        increment(initializer.getAsDouble());
    }

    @Override
    public void increment(double value) {
        if (value < ZERO) {
            throw new IllegalArgumentException("Increment value must be non-negative, but was: " + value);
        }
        if (value != ZERO) {
            container.add(value);
        }
    }

    @Override
    public void increment() {
        container.add(1);
    }

    @Override
    public double getAsDouble() {
        return container.sum();
    }

    @Override
    public void reset() {
        container.reset();
        increment(initializer.getAsDouble());
    }
}
