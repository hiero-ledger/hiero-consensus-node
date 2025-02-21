// SPDX-License-Identifier: Apache-2.0
package com.swirlds.common.metrics.noop.internal;

import static com.swirlds.metrics.api.Metric.DataType.INT;

import com.swirlds.common.metrics.StatEntry;
import com.swirlds.common.metrics.statistics.StatsBuffered;
import com.swirlds.metrics.api.MetricConfig;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * A no-op implementation of a stat entry.
 */
public class NoOpStatEntry extends AbstractNoOpMetric implements StatEntry {

    public NoOpStatEntry(final @NonNull MetricConfig<?, ?> config) {
        super(config);
    }

    /**
     * {@inheritDoc}
     */
    @NonNull
    @Override
    public DataType getDataType() {
        return INT;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public StatsBuffered getBuffered() {
        return new NoOpStatsBuffered();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public Consumer<Double> getReset() {
        return x -> {};
    }

    /**
     * {@inheritDoc}
     */
    @NonNull
    @Override
    public Supplier<Object> getStatsStringSupplier() {
        return () -> "";
    }

    /**
     * {@inheritDoc}
     */
    @NonNull
    @Override
    public Supplier<Object> getResetStatsStringSupplier() {
        return () -> "";
    }
}
