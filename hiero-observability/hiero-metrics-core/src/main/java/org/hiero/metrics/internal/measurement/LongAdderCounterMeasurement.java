// SPDX-License-Identifier: Apache-2.0
package org.hiero.metrics.internal.measurement;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Objects;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.LongSupplier;
import org.hiero.metrics.api.measurement.LongCounterMeasurement;

public final class LongAdderCounterMeasurement implements LongCounterMeasurement {

    private final LongSupplier initializer;
    private final LongAdder container = new LongAdder();

    public LongAdderCounterMeasurement(@NonNull LongSupplier initializer) {
        this.initializer = Objects.requireNonNull(initializer, "initializer must not be null");
        reset();
    }

    @Override
    public void increment(long value) {
        if (value < 0L) {
            throw new IllegalArgumentException("Increment value must be non-negative, but was: " + value);
        }
        if (value != 0L) {
            container.add(value);
        }
    }

    @Override
    public void increment() {
        container.add(1L);
    }

    @Override
    public void reset() {
        container.reset();
        increment(initializer.getAsLong());
    }

    @Override
    public long getAsLong() {
        return container.sum();
    }
}
