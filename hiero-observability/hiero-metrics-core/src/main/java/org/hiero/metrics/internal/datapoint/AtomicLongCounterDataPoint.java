// SPDX-License-Identifier: Apache-2.0
package org.hiero.metrics.internal.datapoint;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;
import org.hiero.metrics.api.stat.StatUtils;

public final class AtomicLongCounterDataPoint extends AbstractLongCounterDataPoint {

    private final LongSupplier initializer;
    private final AtomicLong container = new AtomicLong();

    public AtomicLongCounterDataPoint() {
        this(StatUtils.LONG_INIT);
    }

    public AtomicLongCounterDataPoint(@NonNull LongSupplier initializer) {
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
