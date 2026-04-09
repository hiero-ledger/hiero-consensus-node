// SPDX-License-Identifier: Apache-2.0
package org.hiero.metrics.core;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.function.LongSupplier;

/**
 * A snapshot of a measurement that holds a {@code long} value.
 */
public final class LongMeasurementSnapshot extends MeasurementSnapshot {

    private long value;
    private final LongSupplier valueSupplier;

    public LongMeasurementSnapshot(@NonNull LabelValues dynamicLabelValues, LongSupplier valueSupplier) {
        super(dynamicLabelValues);
        this.valueSupplier = valueSupplier;
        update();
    }

    /**
     * @return the {@code long} value of this measurement snapshot
     */
    public long get() {
        return value;
    }

    @Override
    void update() {
        value = valueSupplier.getAsLong();
    }

    @Override
    public String toString() {
        return "{" + super.toString() + ", value=" + value + "}";
    }
}
