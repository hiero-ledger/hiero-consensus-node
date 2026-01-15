// SPDX-License-Identifier: Apache-2.0
package org.hiero.metrics.core;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.function.DoubleSupplier;

/**
 * A snapshot of a measurement that holds a {@code double} value.
 */
public final class DoubleMeasurementSnapshot extends MeasurementSnapshot {

    private double value;
    private final DoubleSupplier valueSupplier;

    public DoubleMeasurementSnapshot(@NonNull LabelValues dynamicLabelValues, DoubleSupplier valueSupplier) {
        super(dynamicLabelValues);
        this.valueSupplier = valueSupplier;
        update();
    }

    /**
     * @return the {@code double} value of this measurement snapshot
     */
    public double get() {
        return value;
    }

    @Override
    void update() {
        value = valueSupplier.getAsDouble();
    }

    @Override
    public String toString() {
        return "{" + super.toString() + ", value=" + value + "}";
    }
}
