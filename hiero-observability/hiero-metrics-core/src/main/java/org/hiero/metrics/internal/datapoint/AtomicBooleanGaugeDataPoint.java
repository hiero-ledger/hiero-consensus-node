// SPDX-License-Identifier: Apache-2.0
package org.hiero.metrics.internal.datapoint;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Objects;
import java.util.function.BooleanSupplier;
import org.hiero.metrics.api.datapoint.BooleanGaugeDataPoint;
import org.hiero.metrics.api.stat.StatUtils;

public final class AtomicBooleanGaugeDataPoint implements BooleanGaugeDataPoint {

    private final BooleanSupplier initializer;
    private volatile boolean value;

    public AtomicBooleanGaugeDataPoint() {
        this(StatUtils.BOOL_INIT_FALSE);
    }

    public AtomicBooleanGaugeDataPoint(@NonNull BooleanSupplier initializer) {
        this.initializer = Objects.requireNonNull(initializer, "initializer must not be null");
        reset();
    }

    @Override
    public void set(boolean value) {
        this.value = value;
    }

    @Override
    public boolean getAsBoolean() {
        return value;
    }

    @Override
    public void reset() {
        value = initializer.getAsBoolean();
    }
}
