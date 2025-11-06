// SPDX-License-Identifier: Apache-2.0
package com.swirlds.common.metrics.platform;

import com.swirlds.common.metrics.statistics.StatsBuffered;
import com.swirlds.metrics.api.MetricConfig;
import com.swirlds.metrics.api.snapshot.Snapshot.SnapshotEntry;
import com.swirlds.metrics.impl.AbstractMetric;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.List;
import java.util.Objects;

/**
 * Represents a metric computed over a distribution function
 */
public abstract class AbstractDistributionMetric extends AbstractMetric {

    /**
     * Half-life of the metric
     */
    protected final double halfLife;

    /**
     * Constructs a new {@code AbstractStatsMetric} with the specified configuration and half-life.
     *
     * @param config   the metric configuration
     * @param halfLife the half-life of the metric
     */
    protected AbstractDistributionMetric(@NonNull final MetricConfig<?, ?> config, final double halfLife) {
        super(config);
        this.halfLife = halfLife;
    }

    /**
     * Returns the {@link StatsBuffered} instance that holds the statistics for this metric.
     *
     * @return the {@link StatsBuffered} instance
     */
    @NonNull
    public abstract StatsBuffered getStatsBuffered();

    /**
     * Returns the mean value of this {@code Metric}.
     *
     * @return the current value
     */
    public abstract double get();

    /**
     * Getter of the {@code halfLife}
     *
     * @return the {@code halfLife}
     */
    public double getHalfLife() {
        return halfLife;
    }

    /**
     * {@inheritDoc}
     */
    @NonNull
    @Override
    public Double get(@NonNull final ValueType valueType) {
        Objects.requireNonNull(valueType, "valueType must not be null");
        return switch (valueType) {
            case VALUE -> get();
            case MAX -> getStatsBuffered().getMax();
            case MIN -> getStatsBuffered().getMin();
            case STD_DEV -> getStatsBuffered().getStdDev();
            default -> throw new IllegalArgumentException("Unsupported ValueType: " + valueType);
        };
    }

    /**
     * {@inheritDoc}
     */
    @NonNull
    @Override
    public List<SnapshotEntry> takeSnapshot() {
        final StatsBuffered statsBuffered = getStatsBuffered();
        return List.of(
                new SnapshotEntry(ValueType.VALUE, get()),
                new SnapshotEntry(ValueType.MAX, statsBuffered.getMax()),
                new SnapshotEntry(ValueType.MIN, statsBuffered.getMin()),
                new SnapshotEntry(ValueType.STD_DEV, statsBuffered.getStdDev()));
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void reset() {
        getStatsBuffered().reset(halfLife);
    }
}
