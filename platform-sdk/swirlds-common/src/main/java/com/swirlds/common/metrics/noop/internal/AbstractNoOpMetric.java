// SPDX-License-Identifier: Apache-2.0
package com.swirlds.common.metrics.noop.internal;

import com.swirlds.common.metrics.PlatformMetric;
import com.swirlds.common.metrics.statistics.StatsBuffered;
import com.swirlds.metrics.api.Metric;
import com.swirlds.metrics.api.MetricConfig;
import com.swirlds.metrics.api.snapshot.Snapshot.SnapshotEntry;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.EnumSet;
import java.util.List;

/**
 * Boilerplate for a no-op metric.
 */
public abstract class AbstractNoOpMetric implements Metric, PlatformMetric {

    private final MetricConfig<?, ?> config;

    protected AbstractNoOpMetric(final MetricConfig<?, ?> config) {
        this.config = config;
    }

    /**
     * {@inheritDoc}
     */
    @NonNull
    @Override
    public String getCategory() {
        return config.getCategory();
    }

    /**
     * {@inheritDoc}
     */
    @NonNull
    @Override
    public String getName() {
        return config.getName();
    }

    /**
     * {@inheritDoc}
     */
    @NonNull
    @Override
    public String getDescription() {
        return config.getDescription();
    }

    /**
     * {@inheritDoc}
     */
    @NonNull
    @Override
    public String getUnit() {
        return config.getUnit();
    }

    /**
     * {@inheritDoc}
     */
    @NonNull
    @Override
    public String getFormat() {
        return config.getFormat();
    }

    /**
     * {@inheritDoc}
     */
    @NonNull
    @Override
    public EnumSet<ValueType> getValueTypes() {
        return EnumSet.noneOf(ValueType.class);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void reset() {
        // intentional no-op
    }

    /**
     * {@inheritDoc}
     */
    @NonNull
    @Override
    public StatsBuffered getStatsBuffered() {
        return new NoOpStatsBuffered();
    }

    @NonNull
    @Override
    public List<SnapshotEntry> takeSnapshot() {
        return List.of();
    }
}
