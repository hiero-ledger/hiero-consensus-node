// SPDX-License-Identifier: Apache-2.0
package org.hiero.metrics.internal.measurement;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Objects;
import java.util.function.BooleanSupplier;
import org.hiero.metrics.api.measurement.BooleanGaugeMeasurement;
import org.hiero.metrics.api.stat.StatUtils;

public final class AtomicBooleanGaugeMeasurement implements BooleanGaugeMeasurement {

    private final BooleanSupplier initializer;
    private volatile boolean value;

    public AtomicBooleanGaugeMeasurement() {
        this(StatUtils.BOOL_INIT_FALSE);
    }

    public AtomicBooleanGaugeMeasurement(@NonNull BooleanSupplier initializer) {
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
