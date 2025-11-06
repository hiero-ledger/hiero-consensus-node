// SPDX-License-Identifier: Apache-2.0
package com.swirlds.metrics.impl;

import static com.swirlds.metrics.api.Metric.ValueType.VALUE;

import com.swirlds.base.utility.ToStringBuilder;
import com.swirlds.metrics.api.DoubleGauge;
import com.swirlds.metrics.api.snapshot.Snapshot.SnapshotEntry;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.List;

/**
 * Platform-implementation of {@link DoubleGauge}
 */
public class DefaultDoubleGauge extends AbstractMetric implements DoubleGauge {

    private final AtomicDouble value;

    public DefaultDoubleGauge(@NonNull final Config config) {
        super(config);
        value = new AtomicDouble(config.getInitialValue());
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
    public double get() {
        return value.get();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void set(final double newValue) {
        this.value.set(newValue);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void add(final double change) {
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
