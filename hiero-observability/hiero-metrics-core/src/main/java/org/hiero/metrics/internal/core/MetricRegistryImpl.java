// SPDX-License-Identifier: Apache-2.0
package org.hiero.metrics.internal.core;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hiero.metrics.api.core.Label;
import org.hiero.metrics.api.core.Metric;
import org.hiero.metrics.api.core.MetricKey;
import org.hiero.metrics.api.core.MetricsRegistrationProvider;
import org.hiero.metrics.api.export.snapshot.DataPointSnapshot;
import org.hiero.metrics.api.utils.MetricUtils;
import org.hiero.metrics.internal.export.SnapshotableMetric;
import org.hiero.metrics.internal.export.SnapshotableMetricsRegistry;
import org.hiero.metrics.internal.export.snapshot.UpdatableMetricRegistrySnapshot;

public class MetricRegistryImpl implements SnapshotableMetricsRegistry {

    private static final Logger logger = LogManager.getLogger(MetricRegistryImpl.class);

    private final List<Label> globalLabels;
    private final ConcurrentHashMap<String, Metric> metrics = new ConcurrentHashMap<>();
    private final Collection<Metric> metricsView = Collections.unmodifiableCollection(metrics.values());

    private final UpdatableMetricRegistrySnapshot snapshot = new UpdatableMetricRegistrySnapshot();

    public MetricRegistryImpl(@NonNull Label... globalLabels) {
        this.globalLabels = MetricUtils.asList(globalLabels);
    }

    @NonNull
    @Override
    public List<Label> globalLabels() {
        return globalLabels;
    }

    @NonNull
    @Override
    public Collection<Metric> metrics() {
        return metricsView;
    }

    @Override
    public void registerMetrics(@NonNull MetricsRegistrationProvider provider) {
        Objects.requireNonNull(provider, "metrics registration provider must not be null");

        Collection<Metric.Builder<?, ?>> metricsToRegister = provider.getMetricsToRegister();
        Objects.requireNonNull(metricsToRegister, "metrics collection must not be null");

        for (Metric.Builder<?, ?> builder : metricsToRegister) {
            register(builder);
        }
    }

    @NonNull
    @Override
    @SuppressWarnings("unchecked")
    public <M extends Metric, B extends Metric.Builder<?, M>> M register(final @NonNull B builder) {
        Objects.requireNonNull(builder, "metric builder must not be null");

        final MetricKey<M> metricKey = builder.key();

        return (M) metrics.compute(metricKey.name(), (name, existingMetric) -> {
            if (existingMetric != null) {
                throw new IllegalArgumentException(
                        "Duplicate metric name: " + metricKey + ". Existing metric: " + existingMetric.metadata());
            }

            M metric = builder.withConstantLabels(globalLabels).build();
            logger.info("Registered metric: {} with global labels: {}", metric.metadata(), globalLabels);

            if (metric instanceof SnapshotableMetric<? extends DataPointSnapshot> snapshotableMetric) {
                snapshot.add(snapshotableMetric);
            }
            return metric;
        });
    }

    @NonNull
    @Override
    @SuppressWarnings("unchecked")
    public <M extends Metric> Optional<M> findMetric(@NonNull MetricKey<M> key) {
        Objects.requireNonNull(key, "metric key must not be null");
        Metric metric = metrics.get(key.name());
        if (key.type().isInstance(metric)) {
            return Optional.of((M) metric);
        }
        return Optional.empty();
    }

    @NonNull
    @Override
    public UpdatableMetricRegistrySnapshot snapshot() {
        return snapshot;
    }
}
