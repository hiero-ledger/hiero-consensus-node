// SPDX-License-Identifier: Apache-2.0
package org.hiero.metrics.internal.core;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hiero.metrics.api.core.Label;
import org.hiero.metrics.api.core.Metric;
import org.hiero.metrics.api.core.MetricKey;
import org.hiero.metrics.api.export.snapshot.MeasurementSnapshot;
import org.hiero.metrics.internal.export.SnapshotableMetric;
import org.hiero.metrics.internal.export.SnapshotableMetricsRegistry;
import org.hiero.metrics.internal.export.snapshot.UpdatableMetricRegistrySnapshot;

public final class MetricRegistryImpl implements SnapshotableMetricsRegistry {

    private static final Logger logger = LogManager.getLogger(MetricRegistryImpl.class);

    private final String name;
    private final List<Label> globalLabels;
    private final Map<String, Metric> metrics = new ConcurrentHashMap<>();
    private final Collection<Metric> metricsView = Collections.unmodifiableCollection(metrics.values());

    private final UpdatableMetricRegistrySnapshot snapshot = new UpdatableMetricRegistrySnapshot();

    public MetricRegistryImpl(@NonNull String name, @NonNull Collection<Label> globalLabels) {
        this.name = name;
        this.globalLabels = List.copyOf(globalLabels);
    }

    @NonNull
    @Override
    public String name() {
        return name;
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

    @NonNull
    @Override
    public <M extends Metric, B extends Metric.Builder<?, M>> M register(final @NonNull B builder) {
        Objects.requireNonNull(builder, "metric builder must not be null");

        final MetricKey<M> metricKey = builder.key();

        return metricKey.type().cast(metrics.compute(metricKey.name(), (name, existingMetric) -> {
            if (existingMetric != null) {
                throw new IllegalArgumentException(
                        "Duplicate metric name: " + metricKey + ". Existing metric: " + existingMetric.name());
            }

            M metric = builder.withStaticLabels(globalLabels).build();
            logger.info("Registered metric: {} with global labels: {}", metric.name(), globalLabels);

            if (metric instanceof SnapshotableMetric<? extends MeasurementSnapshot> snapshotableMetric) {
                snapshot.add(snapshotableMetric);
            }
            return metric;
        }));
    }

    @Override
    public boolean containsMetric(@NonNull MetricKey<?> key) {
        Objects.requireNonNull(key, "metric key must not be null");
        Metric metric = metrics.get(key.name());
        return key.type().isInstance(metric);
    }

    @NonNull
    @Override
    public <M extends Metric> M getMetric(@NonNull MetricKey<M> key) {
        Objects.requireNonNull(key, "metric key must not be null");
        Metric metric = metrics.get(key.name());
        if (metric == null) {
            throw new NoSuchElementException("Metric not found: " + key);
        }
        return key.type().cast(metric);
    }

    @NonNull
    @Override
    public UpdatableMetricRegistrySnapshot snapshot() {
        return snapshot;
    }
}
