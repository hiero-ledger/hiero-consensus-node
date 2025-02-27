// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.workflows.handle.metric;

import com.swirlds.metrics.api.Metric;
import com.swirlds.metrics.api.MetricConfig;
import com.swirlds.metrics.api.Metrics;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.Collection;

/**
 * A {@link Metrics} implementation that throws {@link UnsupportedOperationException} for all methods,
 * needed as a non-null placeholder for {@link com.hedera.node.app.services.ServiceMigrator} calls made to
 * initialize platform state before the metrics are available.
 */
public enum UnavailableMetrics implements Metrics {
    UNAVAILABLE_METRICS;

    @Nullable
    @Override
    public Metric getMetric(@NonNull String category, @NonNull String name) {
        throw new UnsupportedOperationException("Metrics are not available");
    }

    @NonNull
    @Override
    public Collection<Metric> findMetricsByCategory(@NonNull String category) {
        throw new UnsupportedOperationException("Metrics are not available");
    }

    @NonNull
    @Override
    public Collection<Metric> getAll() {
        throw new UnsupportedOperationException("Metrics are not available");
    }

    @Override
    public <T extends Metric> @NonNull T getOrCreate(@NonNull MetricConfig<T, ?> config) {
        throw new UnsupportedOperationException("Metrics are not available");
    }

    @Override
    public void remove(@NonNull String category, @NonNull String name) {
        throw new UnsupportedOperationException("Metrics are not available");
    }

    @Override
    public void remove(@NonNull Metric metric) {
        throw new UnsupportedOperationException("Metrics are not available");
    }

    @Override
    public void remove(@NonNull MetricConfig<?, ?> config) {
        throw new UnsupportedOperationException("Metrics are not available");
    }

    @Override
    public void addUpdater(@NonNull Runnable updater) {
        throw new UnsupportedOperationException("Metrics are not available");
    }

    @Override
    public void removeUpdater(@NonNull Runnable updater) {
        throw new UnsupportedOperationException("Metrics are not available");
    }

    @Override
    public void start() {
        throw new UnsupportedOperationException("Metrics are not available");
    }
}
