// SPDX-License-Identifier: Apache-2.0
package org.hiero.metrics.internal.measurement;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;
import org.hiero.metrics.api.measurement.LongGaugeMeasurement;

public class AtomicLongGaugeMeasurement implements LongGaugeMeasurement {

    protected final AtomicLong container;

    private final LongSupplier initializer;

    public AtomicLongGaugeMeasurement(@NonNull LongSupplier initializer) {
        this.initializer = Objects.requireNonNull(initializer, "initializer must not be null");
        container = new AtomicLong(initializer.getAsLong());
    }

    @Override
    public long getInitValue() {
        return initializer.getAsLong();
    }

    @Override
    public void update(long value) {
        container.set(value);
    }

    @Override
    public long getAndReset() {
        return container.getAndSet(getInitValue());
    }

    @Override
    public long getAsLong() {
        return container.get();
    }

    @Override
    public void reset() {
        container.set(getInitValue());
    }
}
