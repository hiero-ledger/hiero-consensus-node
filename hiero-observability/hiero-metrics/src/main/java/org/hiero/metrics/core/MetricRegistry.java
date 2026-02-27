// SPDX-License-Identifier: Apache-2.0
package org.hiero.metrics.core;

import static java.lang.System.Logger.Level.DEBUG;
import static java.lang.System.Logger.Level.INFO;
import static java.lang.System.Logger.Level.WARNING;

import com.swirlds.config.api.Configuration;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.io.Closeable;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A thread-safe registry for {@link Metric} instances, that allows registering new metrics by their builders
 * and retrieving existing metrics by their {@link MetricKey}.
 * <p>
 * The registry may have global labels that are applied to all metrics registered in it.
 * New registry can be created via {@link #builder()} using builder pattern.
 * <p>
 * Metric registry can optionally be associated with a {@link MetricsExporter} to export
 * the metrics snapshots to an external system. It can be set during the registry creation via the builder:
 * {@link Builder#setMetricsExporter(MetricsExporter)} or {@link Builder#discoverMetricsExporter(Configuration)}.
 * It implements {@link Closeable} to allow closing associated {@link MetricsExporter}, if present.
 */
public final class MetricRegistry implements Closeable {

    /** Configuration property to disable metrics exporter discovery.*/
    private static final String PROPERTY_EXPORT_DISCOVERY_DISABLED = "hiero.metrics.export.discovery.diasbled";

    private static final System.Logger logger = System.getLogger(MetricRegistry.class.getName());

    private final List<Label> globalLabels;
    private final MetricsExporter exporter;

    private final Map<String, Metric> metrics = new ConcurrentHashMap<>();
    private final Collection<Metric> metricsView = Collections.unmodifiableCollection(metrics.values());

    @Nullable
    private final MetricRegistrySnapshot snapshot;

    private MetricRegistry(@NonNull List<Label> globalLabels, @Nullable MetricsExporter exporter) {
        this.globalLabels = List.copyOf(globalLabels);
        this.exporter = exporter;

        if (exporter != null) {
            snapshot = new MetricRegistrySnapshot();
            exporter.setSnapshotSupplier(snapshot::update);
            logger.log(
                    INFO,
                    "Created metric registry. globalLabels={0}, exporter={1}",
                    this.globalLabels,
                    exporter.getClass());
        } else {
            snapshot = null;
            logger.log(INFO, "Created metric registry without exporter. globalLabels={0}", this.globalLabels);
        }
    }
    /**
     * @return {@code true} if this registry has an associated {@link MetricsExporter}, {@code false} otherwise
     */
    public boolean hasMetricsExporter() {
        return exporter != null;
    }

    /**
     * @return a new {@link Builder} for constructing {@link MetricRegistry} instance.
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * @return unmodifiable list of global labels that are applied to all metrics in this registry, may be empty but never {@code null}
     */
    @NonNull
    public List<Label> globalLabels() {
        return globalLabels;
    }

    /**
     * @return unmodifiable collection of all registered metrics in this registry, may be empty but never {@code null}
     */
    @NonNull
    public Collection<Metric> metrics() {
        return metricsView;
    }

    /**
     * Creates and registers a metric using the given metric builder.
     * <p>
     * This method is <b>not idempotent</b> and throws an exception, if metric with the same name already registered.
     *
     * @param builder the metric builder, must not be {@code null}
     * @param <M>     the type of the metric to be created and registered
     * @param <B>     the type of the metric builder that creates the metric
     * @return the created and registered metric, never {@code null}
     * @throws NullPointerException     if the builder is {@code null}
     * @throws IllegalArgumentException if a metric with the same name already exists in the registry
     */
    @NonNull
    public <M extends Metric, B extends Metric.Builder<?, M>> M register(final @NonNull B builder) {
        Objects.requireNonNull(builder, "metric builder must not be null");

        final MetricKey<M> metricKey = builder.key();

        return metricKey.type().cast(metrics.compute(metricKey.name(), (name, existingMetric) -> {
            if (existingMetric != null) {
                throw new IllegalArgumentException(
                        "Duplicate metric name: " + metricKey + ". Existing metric: " + existingMetric.name());
            }

            builder.addStaticLabels(globalLabels.toArray(Label[]::new));
            if (snapshot == null) {
                builder.doNotSnapshot();
            }

            M metric = builder.build();
            logger.log(DEBUG, "Registered metric. name={0}", metric.name());

            if (snapshot != null) {
                snapshot.addMetricSnapshot(metric.snapshot());
            }

            return metric;
        }));
    }

    /**
     * Resets all registered metrics and their measurements in this registry to their initial state.
     */
    public void reset() {
        metrics().forEach(Metric::reset);
    }

    /**
     * Checks if a metric with the given key is registered in the registry.
     * Metric to be found has to have the same name as the provided key and be of compatible type.
     *
     * @param key the metric key, must not be {@code null}
     * @return {@code true} if a metric with the given key is registered, {@code false} otherwise
     * @throws NullPointerException if the key is {@code null}
     */
    public boolean containsMetric(@NonNull MetricKey<?> key) {
        Objects.requireNonNull(key, "metric key must not be null");
        Metric metric = metrics.get(key.name());
        return key.type().isInstance(metric);
    }

    /**
     * Gets a metric by its key.
     * Metric to be found has to have the same name as the provided key and be of compatible type.
     *
     * @param key the metric key, must not be {@code null}
     * @param <M> the type of the metric
     * @return the found metric, never {@code null}
     * @throws NullPointerException if the key is {@code null}
     * @throws NoSuchElementException if no metric is found for the given key name
     * @throws ClassCastException if metric found with the given key name is not of the expected key type
     */
    @NonNull
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
        if (exporter != null) {
            logger.log(INFO, "Closing metrics exporter: {0}", exporter.getClass());
            exporter.close();
        }
    }

    /**
     * Builder for constructing {@link MetricRegistry} instances.
     */
    public static final class Builder {

        private final List<Label> globalLabels = new ArrayList<>();
        private final Set<String> globalLabelNames = new HashSet<>();

        private boolean discoverMetricProviders = false;
        private MetricsExporter metricsExporter;
        private Configuration configuration;

        /**
         * Adds a global label to the registry that will be applied to all metrics.
         *
         * @param label the label to add, must not be {@code null}
         * @return this builder instance
         * @throws NullPointerException if the label is {@code null}
         * @throws IllegalArgumentException if a global label with the same name already exists
         */
        @NonNull
        public Builder addGlobalLabel(@NonNull Label label) {
            Objects.requireNonNull(label, "global label must not be null");
            if (!globalLabelNames.add(label.name())) {
                throw new IllegalArgumentException("Duplicate global label name: " + label.name());
            }

            this.globalLabels.add(label);
            return this;
        }

        /**
         * Sets the {@link MetricsExporter} to be associated with the registry.
         *
         * @param metricsExporter the metrics exporter, must not be {@code null}
         * @return this builder instance
         * @throws NullPointerException if the metrics exporter is {@code null}
         */
        @NonNull
        public Builder setMetricsExporter(@NonNull MetricsExporter metricsExporter) {
            this.metricsExporter = Objects.requireNonNull(metricsExporter, "metrics exporter must not be null");
            return this;
        }

        /**
         * Enable discovery of {@link MetricsRegistrationProvider} implementations to register in the registry.
         * Actual discovery happens during the {@link #build()} call.
         *
         * @return this builder instance
         */
        @NonNull
        public Builder discoverMetricProviders() {
            discoverMetricProviders = true;
            return this;
        }

        /**
         * Enables discovery {@link MetricsExporterFactory} implementation that creates {@link MetricsExporter}
         * using the provided configuration.
         * Actual discovery happens during the {@link #build()} call and if successful, overrides exporter set
         * by {@link #setMetricsExporter(MetricsExporter)}.
         * {@link MetricsExporter} will be used only if single {@link MetricsExporterFactory} is discovered and
         * {@value #PROPERTY_EXPORT_DISCOVERY_DISABLED} configuration property is not set to {@code true}.
         *
         * @param configuration the configuration to use for creating an instance of {@link MetricsExporter}, must not be {@code null}
         * @return this builder instance
         * @throws NullPointerException if the configuration is {@code null}
         */
        @NonNull
        public Builder discoverMetricsExporter(@NonNull Configuration configuration) {
            this.configuration = Objects.requireNonNull(configuration, "configuration must not be null");
            return this;
        }

        /**
         * Builds the {@link MetricRegistry} instance.
         * <p>
         * Registry may have associated {@link MetricsExporter} if set via
         * {@link #setMetricsExporter(MetricsExporter)} or discovered via
         * {@link #discoverMetricsExporter(Configuration)}. Discovered exporter takes precedence over
         * explicitly set exporter.
         * <p>
         * If exporter discovery is enabled via {@link #discoverMetricsExporter(Configuration)} and not disabled by
         * configuration property {@value #PROPERTY_EXPORT_DISCOVERY_DISABLED}, it attempts to discover a single
         * {@link MetricsExporterFactory} via service loader and create an exporter using it.
         * If successful, it overrides any exporter set via {@link #setMetricsExporter(MetricsExporter)}.
         * If multiple factories are found, they are ignored and a warning is logged.
         * <p>
         * If metric providers discovery is enabled via {@link #discoverMetricProviders()}, it attempts to discover
         * all {@link MetricsRegistrationProvider} implementations via service loader and register their metrics
         * in the constructed registry.
         *
         * @return the constructed {@link MetricRegistry}
         */
        @NonNull
        public MetricRegistry build() {
            if (configuration != null) {
                Boolean exporterDiscoveryDisabled =
                        configuration.getValue(PROPERTY_EXPORT_DISCOVERY_DISABLED, Boolean.class, false);
                if (exporterDiscoveryDisabled != null && exporterDiscoveryDisabled) {
                    logger.log(
                            INFO,
                            "Exporter discovery is disabled by configuration property: {0}",
                            PROPERTY_EXPORT_DISCOVERY_DISABLED);
                } else {
                    List<MetricsExporterFactory> factories = MetricUtils.load(MetricsExporterFactory.class);
                    if (factories.size() > 1) {
                        logger.log(
                                WARNING,
                                "Multiple metrics exporter factories found: {0}. "
                                        + "Expected at most one. Ignoring discovered exporter factories.",
                                factories);
                    } else if (factories.size() == 1) {
                        MetricsExporterFactory factory = factories.getFirst();
                        MetricsExporter exporter =
                                factory.createExporter(Collections.unmodifiableList(globalLabels), configuration);

                        if (exporter != null) {
                            this.metricsExporter = exporter;
                        } else {
                            logger.log(INFO, "Exporter factory did not create an exporter: {0}", factory.getClass());
                        }
                    }
                }
            }

            final MetricRegistry registry = new MetricRegistry(globalLabels, metricsExporter);

            if (discoverMetricProviders) {
                List<MetricsRegistrationProvider> providers = MetricUtils.load(MetricsRegistrationProvider.class);

                if (providers.isEmpty()) {
                    logger.log(INFO, "No metrics registration providers found.");
                    return registry;
                }

                for (MetricsRegistrationProvider provider : providers) {
                    Objects.requireNonNull(provider, "metrics registration provider must not be null");
                    logger.log(INFO, "Registering metrics from provider: {0}", provider.getClass());

                    Collection<Metric.Builder<?, ?>> metricsToRegister = provider.getMetricsToRegister();
                    Objects.requireNonNull(metricsToRegister, "metrics collection must not be null");

                    for (Metric.Builder<?, ?> builder : metricsToRegister) {
                        registry.register(builder);
                    }
                }
            }

            return registry;
        }
    }
}
