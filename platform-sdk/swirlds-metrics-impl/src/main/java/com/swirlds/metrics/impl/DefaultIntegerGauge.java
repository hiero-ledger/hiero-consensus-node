// SPDX-License-Identifier: Apache-2.0
package com.swirlds.metrics.impl;

import static com.swirlds.metrics.api.Metric.ValueType.VALUE;

import com.swirlds.base.utility.ToStringBuilder;
import com.swirlds.metrics.api.IntegerGauge;
import com.swirlds.metrics.api.snapshot.Snapshot.SnapshotEntry;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Platform-implementation of {@link IntegerGauge}
 */
public class DefaultIntegerGauge extends AbstractMetric implements IntegerGauge {

    private final AtomicInteger value;

    public DefaultIntegerGauge(@NonNull final Config config) {
        super(config);
        this.value = new AtomicInteger(config.getInitialValue());
    }

    /**
     * {@inheritDoc}
     */
    @NonNull
    @Override
    public List<SnapshotEntry> takeSnapshot() {
        return List.of(new SnapshotEntry(VALUE, get()));
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public int get() {
        return value.get();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void set(final int newValue) {
        this.value.set(newValue);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void add(final int change) {
        this.value.addAndGet(change);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String toString() {
        return new ToStringBuilder(this)
                .appendSuper(super.toString())
                .append("value", value.get())
                .toString();
    }
}
