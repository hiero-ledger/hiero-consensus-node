// SPDX-License-Identifier: Apache-2.0
package com.swirlds.common.metrics.platform;

import static com.swirlds.metrics.api.Metric.ValueType.MAX;
import static com.swirlds.metrics.api.Metric.ValueType.MIN;
import static com.swirlds.metrics.api.Metric.ValueType.STD_DEV;
import static com.swirlds.metrics.api.Metric.ValueType.VALUE;

import com.swirlds.base.utility.ToStringBuilder;
import com.swirlds.common.metrics.StatEntry;
import com.swirlds.common.metrics.statistics.StatsBuffered;
import com.swirlds.metrics.api.MetricConfig;
import com.swirlds.metrics.api.snapshot.Snapshot.SnapshotEntry;
import com.swirlds.metrics.impl.AbstractMetric;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Platform-implementation of {@link StatEntry}
 */
@SuppressWarnings("removal")
public class PlatformStatEntry extends AbstractMetric implements StatEntry {

    private final @NonNull DataType dataType;
    /**
     * the statistics object (if it implements StatsBuffered), else null
     */
    private final @Nullable StatsBuffered buffered;
    /**
     * a lambda that resets the statistic, using the given half life
     */
    private final @Nullable Consumer<Double> reset;
    /**
     * a lambda that returns the statistic string
     */
    private final @NonNull Supplier<Object> statsStringSupplier;
    /**
     * a lambda that returns the statistic string and resets it at the same time
     */
    private final @NonNull Supplier<Object> resetStatsStringSupplier;

    /**
     * the half life of the statistic, in seconds
     */
    private final double halfLife;

    @SuppressWarnings("unchecked")
    public PlatformStatEntry(@NonNull final StatEntry.Config<?> config) {
        super(config);
        this.dataType = MetricConfig.mapDataType(config.getType());
        this.buffered = config.getBuffered();
        this.reset = config.getReset();
        this.statsStringSupplier = (Supplier<Object>) config.getStatsStringSupplier();
        this.resetStatsStringSupplier = (Supplier<Object>) config.getResetStatsStringSupplier();
        halfLife = config.getHalfLife();
        if (config.getInit() != null) {
            config.getInit().apply(halfLife);
        }
    }

    /**
     * {@inheritDoc}
     */
    @NonNull
    @Override
    public DataType getDataType() {
        return dataType;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @Nullable
    public StatsBuffered getBuffered() {
        return buffered;
    }

    /**
     * {@inheritDoc}
     */
    @Nullable
    @Override
    public Consumer<Double> getReset() {
        return reset;
    }

    /**
     * {@inheritDoc}
     */
    @NonNull
    @Override
    public Supplier<Object> getStatsStringSupplier() {
        return statsStringSupplier;
    }

    /**
     * {@inheritDoc}
     */
    @NonNull
    @Override
    public Supplier<Object> getResetStatsStringSupplier() {
        return resetStatsStringSupplier;
    }

    /**
     * {@inheritDoc}
     */
    @NonNull
    @Override
    public List<SnapshotEntry> takeSnapshot() {
        if (buffered == null) {
            return List.of(new SnapshotEntry(VALUE, resetStatsStringSupplier.get()));
        }

        final double max = buffered.getMax();
        final double min = buffered.getMin();
        final double stdDev = buffered.getStdDev();
        final Object value = resetStatsStringSupplier.get();
        return List.of(
                new SnapshotEntry(VALUE, value),
                new SnapshotEntry(MAX, max),
                new SnapshotEntry(MIN, min),
                new SnapshotEntry(STD_DEV, stdDev));
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void reset() {
        if (reset != null) {
            reset.accept(halfLife);
        } else if (buffered != null) {
            buffered.reset(halfLife);
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String toString() {
        return new ToStringBuilder(this)
                .appendSuper(super.toString())
                .append("value", statsStringSupplier.get())
                .toString();
    }
}
