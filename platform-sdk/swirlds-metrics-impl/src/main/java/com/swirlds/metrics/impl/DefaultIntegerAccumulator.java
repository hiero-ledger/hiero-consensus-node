// SPDX-License-Identifier: Apache-2.0
package com.swirlds.metrics.impl;

import static com.swirlds.metrics.api.Metric.ValueType.VALUE;

import com.swirlds.base.utility.ToStringBuilder;
import com.swirlds.metrics.api.IntegerAccumulator;
import com.swirlds.metrics.api.snapshot.Snapshot.SnapshotEntry;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntBinaryOperator;
import java.util.function.IntSupplier;

/**
 * Platform-implementation of {@link IntegerAccumulator}
 */
public class DefaultIntegerAccumulator extends AbstractMetric implements IntegerAccumulator {

    private final AtomicInteger container;
    private final IntBinaryOperator accumulator;
    private final IntSupplier initializer;

    public DefaultIntegerAccumulator(@NonNull final Config config) {
        super(config);
        this.accumulator = config.getAccumulator();
        final int initialValue = config.getInitialValue();
        this.initializer = config.getInitializer() != null ? config.getInitializer() : () -> initialValue;
        this.container = new AtomicInteger(this.initializer.getAsInt());
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public int getInitialValue() {
        return initializer.getAsInt();
    }

    /**
     * {@inheritDoc}
     */
    @NonNull
    @Override
    public List<SnapshotEntry> takeSnapshot() {
        return List.of(new SnapshotEntry(VALUE, container.getAndSet(initializer.getAsInt())));
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public int get() {
        return container.get();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void update(final int other) {
        container.accumulateAndGet(other, accumulator);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void reset() {
        container.set(initializer.getAsInt());
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String toString() {
        return new ToStringBuilder(this)
                .appendSuper(super.toString())
                .append("value", get())
                .toString();
    }
}
