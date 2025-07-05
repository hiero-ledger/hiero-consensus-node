// SPDX-License-Identifier: Apache-2.0
package com.swirlds.common.metrics.noop.internal;

import com.swirlds.metrics.api.MetricConfig;
import com.swirlds.metrics.api.snapshot.Snapshot.SnapshotEntry;
import com.swirlds.metrics.api.snapshot.SnapshotableMetric;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Collections;
import java.util.List;
import java.util.Set;

/**
 * Boilerplate for a no-op metric.
 */
public abstract class AbstractNoOpMetric implements SnapshotableMetric {

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
    public Set<ValueType> getValueTypes() {
        return Collections.emptySet();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void reset() {
        // intentional no-op
    }

    @NonNull
    @Override
    public List<SnapshotEntry> takeSnapshot() {
        return List.of();
    }
}
