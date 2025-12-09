// SPDX-License-Identifier: Apache-2.0
package org.hiero.metrics.internal.measurement;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;
import org.hiero.metrics.api.stat.StatUtils;

public final class AtomicLongCounterMeasurement extends AbstractLongCounterMeasurement {

    private final LongSupplier initializer;
    private final AtomicLong container = new AtomicLong();

    public AtomicLongCounterMeasurement() {
        this(StatUtils.LONG_INIT);
    }

    public AtomicLongCounterMeasurement(@NonNull LongSupplier initializer) {
        this.initializer = Objects.requireNonNull(initializer, "initializer must not be null");
        increment(initializer.getAsLong());
    }

    @Override
    protected void safeIncrement(long value) {
        container.getAndAdd(value);
    }

    @Override
    public long getAsLong() {
        return container.get();
    }

    @Override
    public void reset() {
        container.set(initializer.getAsLong());
    }
}
