// SPDX-License-Identifier: Apache-2.0
package com.swirlds.metrics.impl;

import static com.swirlds.metrics.api.Metric.ValueType.VALUE;

import com.swirlds.base.utility.ToStringBuilder;
import com.swirlds.metrics.api.Counter;
import com.swirlds.metrics.api.snapshot.Snapshot.SnapshotEntry;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.List;
import java.util.concurrent.atomic.LongAdder;

/**
 * Platform-implementation of {@link Counter}
 */
public class DefaultCounter extends AbstractMetric implements Counter {

    private static final String INCREASE_ONLY_ERROR_MESSAGE = "The value of a a Counter can only be increased";

    private final LongAdder adder = new LongAdder();

    public DefaultCounter(@NonNull final Config config) {
        super(config);
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
        return adder.sum();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void add(final long value) {
        if (value <= 0) {
            throw new IllegalArgumentException(INCREASE_ONLY_ERROR_MESSAGE);
        }
        adder.add(value);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void increment() {
        adder.increment();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected ToStringBuilder selfToString() {
        return super.selfToString().append("value", get());
    }
}
