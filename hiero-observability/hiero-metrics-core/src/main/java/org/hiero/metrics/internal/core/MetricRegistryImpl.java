// SPDX-License-Identifier: Apache-2.0
package org.hiero.metrics.internal.core;

import static java.lang.System.Logger.Level.DEBUG;
import static java.lang.System.Logger.Level.INFO;
import static java.lang.System.Logger.Level.WARNING;

import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.io.IOException;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import org.hiero.metrics.api.core.Label;
import org.hiero.metrics.api.core.Metric;
import org.hiero.metrics.api.core.MetricKey;
import org.hiero.metrics.api.core.MetricRegistry;
import org.hiero.metrics.api.export.MetricsExporter;
import org.hiero.metrics.internal.export.snapshot.UpdatableMetricRegistrySnapshot;

public final class MetricRegistryImpl implements MetricRegistry {

    private static final System.Logger logger = System.getLogger(MetricRegistry.class.getName());

    private final List<Label> globalLabels;
    private final MetricsExporter metricsExporter;

    private final Map<String, Metric> metrics = new ConcurrentHashMap<>();
    private final Collection<Metric> metricsView = Collections.unmodifiableCollection(metrics.values());
    private final UpdatableMetricRegistrySnapshot snapshot = new UpdatableMetricRegistrySnapshot();

    public MetricRegistryImpl(@NonNull List<Label> globalLabels, @Nullable MetricsExporter metricsExporter) {
        this.globalLabels = List.copyOf(globalLabels);
        this.metricsExporter = metricsExporter;

        if (metricsExporter != null) {
            metricsExporter.setSnapshotSupplier(snapshot::update);
            logger.log(
                    INFO,
                    "Created metric registry with global labels {} and metrics exporter {}.",
                    this.globalLabels,
                    metricsExporter.getClass());
        } else {
            logger.log(
                    INFO,
                    "Created metric registry with global labels {} and without metrics exporter.",
                    this.globalLabels);
        }
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

            M metric =
                    builder.addStaticLabels(globalLabels.toArray(Label[]::new)).build();
            logger.log(DEBUG, "Registered metric: {}", metric.name());

            if (metric instanceof AbstractMetric<?> snapshotableMetric) {
                snapshot.addMetricSnapshot(snapshotableMetric.snapshot());
            } else {
                logger.log(WARNING, "Metric {} is not a framework metric and won't able to be exported", metric.name());
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

    @Override
    public void close() throws IOException {
        if (metricsExporter != null) {
            logger.log(INFO, "Closing metrics exporter: {}", metricsExporter.getClass());
            metricsExporter.close();
        }
    }
}
