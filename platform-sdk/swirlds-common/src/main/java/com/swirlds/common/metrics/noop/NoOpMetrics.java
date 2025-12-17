// SPDX-License-Identifier: Apache-2.0
package com.swirlds.common.metrics.noop;

import com.swirlds.common.metrics.PlatformMetrics;
import com.swirlds.common.metrics.noop.internal.NoOpMetricsFactory;
import com.swirlds.metrics.api.Metric;
import com.swirlds.metrics.api.MetricConfig;
import com.swirlds.metrics.api.Metrics;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * A no-op {@link Metrics} implementation.
 *
 * @param <KEY> the type of the unique identifier for separate instances of metrics
 * @deprecated This class serves as a temporary workaround and may be removed at a future time without notice. External
 * parties are warned not to rely on this class.
 */
@Deprecated(forRemoval = true)
public class NoOpMetrics<KEY> implements PlatformMetrics<KEY> {

    private final Map<String /* category */, Map<String /* name */, Metric>> metrics = new HashMap<>();

    private static final NoOpMetricsFactory FACTORY = new NoOpMetricsFactory();

    @Nullable
    private final KEY key;

    /**
     * Constructor
     *
     * @param key the unique identifier (can be null for global metrics)
     */
    public NoOpMetrics(@Nullable final KEY key) {
        this.key = key;
    }

    @Nullable
    @Override
    public KEY getKey() {
        return key;
    }

    @Override
    public boolean isGlobalMetrics() {
        return key == null;
    }

    @Override
    public boolean isPlatformMetrics() {
        return key != null;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public synchronized @Nullable Metric getMetric(@NonNull final String category, @NonNull final String name) {
        final Map<String, Metric> metricsInCategory = metrics.get(category);
        if (metricsInCategory == null) {
            return null;
        }
        return metricsInCategory.get(name);
    }

    /**
     * {@inheritDoc}
     */
    @NonNull
    @Override
    public synchronized Collection<Metric> findMetricsByCategory(@NonNull final String category) {
        final Map<String, Metric> metricsInCategory = metrics.get(category);
        if (metricsInCategory == null) {
            return List.of();
        }
        return metricsInCategory.values();
    }

    /**
     * {@inheritDoc}
     */
    @NonNull
    @Override
    public synchronized Collection<Metric> getAll() {
        // Not very efficient, but the no-op metrics doesn't do snapshotting, so this should rarely (if ever) be called.
        final List<Metric> allMetrics = new ArrayList<>();
        for (final Map<String, Metric> metricsInCategory : metrics.values()) {
            allMetrics.addAll(metricsInCategory.values());
        }
        return allMetrics;
    }

    /**
     * {@inheritDoc}
     */
    @NonNull
    @SuppressWarnings("unchecked")
    @Override
    public synchronized <T extends Metric> T getOrCreate(@NonNull final MetricConfig<T, ?> config) {
        Objects.requireNonNull(config, "config must not be null");
        final String category = config.getCategory();
        final String name = config.getName();

        final Map<String, Metric> metricsInCategory = metrics.computeIfAbsent(category, k -> new HashMap<>());
        return (T) metricsInCategory.computeIfAbsent(name, k -> FACTORY.createMetric(config));
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public synchronized void remove(@NonNull final String category, @NonNull final String name) {
        Objects.requireNonNull(category, "category must not be null");
        Objects.requireNonNull(name, "name must not be null");
        final Map<String, Metric> metricsInCategory = metrics.get(category);

        if (metricsInCategory == null) {
            return;
        }

        metricsInCategory.remove(name);

        if (metricsInCategory.isEmpty()) {
            metrics.remove(category);
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void remove(@NonNull final Metric metric) {
        Objects.requireNonNull(metric, "metric must not be null");
        remove(metric.getCategory(), metric.getName());
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void remove(@NonNull final MetricConfig<?, ?> config) {
        Objects.requireNonNull(config, "config must not be null");
        remove(config.getCategory(), config.getName());
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void addUpdater(@NonNull final Runnable updater) {
        Objects.requireNonNull(updater, "updater must not be null");
        // Intentional no-op
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void removeUpdater(@NonNull final Runnable updater) {
        Objects.requireNonNull(updater, "updater must not be null");
        // Intentional no-op
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void start() {
        // Intentional no-op
    }
}
