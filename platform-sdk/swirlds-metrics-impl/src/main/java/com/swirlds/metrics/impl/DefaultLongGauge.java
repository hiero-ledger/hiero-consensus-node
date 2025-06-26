// SPDX-License-Identifier: Apache-2.0
package com.swirlds.metrics.impl;

import static com.swirlds.metrics.api.Metric.ValueType.VALUE;

import com.swirlds.base.utility.ToStringBuilder;
import com.swirlds.metrics.api.LongGauge;
import com.swirlds.metrics.api.snapshot.Snapshot.SnapshotEntry;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Platform-implementation of {@link LongGauge}
 */
public class DefaultLongGauge extends AbstractMetric implements LongGauge {

    private final AtomicLong value;

    public DefaultLongGauge(@NonNull final Config config) {
        super(config);
        this.value = new AtomicLong(config.getInitialValue());
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
    public long get() {
        return value.get();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void set(final long newValue) {
        value.set(newValue);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void add(final long change) {
        value.addAndGet(change);
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
