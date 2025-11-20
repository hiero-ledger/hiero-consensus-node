// SPDX-License-Identifier: Apache-2.0
package org.hiero.metrics.api.export;

import static org.hiero.metrics.api.utils.MetricUtils.load;

import com.swirlds.config.api.Configuration;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.function.Supplier;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hiero.metrics.api.core.MetricRegistry;
import org.hiero.metrics.internal.export.DefaultMetricsExportManager;
import org.hiero.metrics.internal.export.NoOpMetricsExportManager;
import org.hiero.metrics.internal.export.SinglePullingExporterMetricsExportManager;
import org.hiero.metrics.internal.export.SnapshotableMetricsRegistry;
import org.hiero.metrics.internal.export.config.MetricsExportManagerConfig;

/**
 * Manager for exporting metrics data points from all managed registries to external systems using set of
 * {@link PushingMetricsExporter} or {@link PullingMetricsExporter}, or both.
 * <p>
 * Export manager can be created using {@link #builder} builder pattern.
 * Export manager can manage only one metrics registry, but multiple export managers can be created
 * to manage different registries if needed.
 */
public interface MetricsExportManager extends AutoCloseable {

    /**
     * @return the managed metric registry, never {@code null}
     */
    @NonNull
    MetricRegistry registry();

    /**
     * @return {@code true} if this manager has additional running thread to export metrics.
     */
    boolean hasRunningExportThread();

    /**
     * Stop exporting and shutdown the manager.
     * This method is idempotent and called to release resources when the manager is no longer needed.
     */
    void shutdown();

    /**
     * Closes this export manager by shutting it down.
     * This method is equivalent to calling {@link #shutdown()}.
     */
    @Override
    default void close() {
        shutdown();
    }

    /**
     * Creates a builder for {@link MetricsExportManager}.
     *
     * @return a new builder instance
     * @throws NullPointerException     if the {@code exportManagerName} is {@code null}
     * @throws IllegalArgumentException if the {@code exportManagerName} is blank
     */
    @NonNull
    static Builder builder() {
        return new Builder();
    }

    /**
     * Builder for {@link MetricsExportManager}.
     * <p>
     * Allows adding pulling and pushing exporters, setting export interval and executor service factory,
     * and enabling discovery of exporters using SPI. Discovered exporters are created using provided configuration.
     * <p>
     * Default export interval is 3 seconds.
     * Default executor service factory creates a single-threaded scheduled executor.
     */
    class Builder {

        private static final Logger logger = LogManager.getLogger(MetricsExportManager.class);

        private final List<PullingMetricsExporter> pullingExporters = new ArrayList<>();
        private final List<PushingMetricsExporter> pushingExporters = new ArrayList<>();
        private final Set<String> exporterNames = new HashSet<>();

        private int exportIntervalSeconds = 3;
        private Supplier<ScheduledExecutorService> executorServiceFactory = Executors::newSingleThreadScheduledExecutor;

        private Configuration configuration;

        /**
         * Sets the factory for creating the executor service for running export tasks.
         * If only single pulling exporter is used, no export thread will be created and this setting is ignored.
         *
         * @param executorServiceFactory the executor service factory, must not be {@code null}
         * @return this builder instance
         * @throws NullPointerException if the {@code executorServiceFactory} is {@code null}
         */
        @NonNull
        public Builder withExecutorServiceFactory(@NonNull Supplier<ScheduledExecutorService> executorServiceFactory) {
            this.executorServiceFactory =
                    Objects.requireNonNull(executorServiceFactory, "executor factory must not be null");
            return this;
        }

        /**
         * Sets the export interval in seconds for pushing exporters.
         * If only single pulling exporter is used, no export thread will be created and this setting is ignored.
         *
         * @param exportIntervalSeconds the export interval in seconds, must be positive
         * @return this builder instance
         * @throws IllegalArgumentException if {@code exportIntervalSeconds} is not positive
         */
        @NonNull
        public Builder withExportIntervalSeconds(int exportIntervalSeconds) {
            if (exportIntervalSeconds <= 0) {
                throw new IllegalArgumentException("export interval seconds must be positive");
            }
            this.exportIntervalSeconds = exportIntervalSeconds;
            return this;
        }

        /**
         * Adds a pulling metrics exporter to the export manager.
         *
         * @param exporter the pulling metrics exporter to add, must not be {@code null}
         * @return this builder instance
         * @throws NullPointerException if the exporter is {@code null}
         * @throws IllegalArgumentException if an exporter with the same name already exists
         */
        @NonNull
        public Builder withExporter(@NonNull PullingMetricsExporter exporter) {
            this.pullingExporters.add(validateExporter(exporter));
            return this;
        }

        /**
         * Adds a pushing metrics exporter to the export manager.
         *
         * @param exporter the pushing metrics exporter to add, must not be {@code null}
         * @return this builder instance
         * @throws NullPointerException if the exporter is {@code null}
         * @throws IllegalArgumentException if an exporter with the same name already exists
         */
        @NonNull
        public Builder withExporter(@NonNull PushingMetricsExporter exporter) {
            this.pushingExporters.add(validateExporter(exporter));
            return this;
        }

        /**
         * Enables discovery of exporters using SPI by looking for available {@link MetricsExporterFactory}s.
         * Export interval seconds from the configuration will be override manually set value
         * by {@link #withExportIntervalSeconds(int)}.
         *
         * @param configuration the configuration to use for creating exporters, must not be {@code null}
         * @return this builder instance
         * @throws NullPointerException if the configuration is {@code null}
         */
        @NonNull
        public Builder withDiscoverExporters(@NonNull Configuration configuration) {
            this.configuration = Objects.requireNonNull(configuration, "configuration must not be null");
            return this;
        }

        private <E extends MetricsExporter> E validateExporter(E exporter) {
            Objects.requireNonNull(exporter, "exporter must not be null");
            String exporterName = exporter.name();
            Objects.requireNonNull(exporterName, "exporter name must not be null");

            if (!exporterNames.add(exporterName)) {
                throw new IllegalArgumentException("Duplicate exporter name: " + exporterName);
            }
            return exporter;
        }

        private void loadExporters(String registryName, MetricsExportManagerConfig exportConfig) {
            List<MetricsExporterFactory> exporterFactories = load(MetricsExporterFactory.class);

            MetricsExporter exporter;
            for (MetricsExporterFactory exporterFactory : exporterFactories) {
                if (!exportConfig.isExporterEnabled(exporterFactory.name())) {
                    logger.info("Metrics exporter factory is disabled: {}", exporterFactory.name());
                    continue;
                }

                try {
                    exporter = exporterFactory.createExporter(registryName, configuration);
                } catch (RuntimeException e) {
                    logger.warn(
                            "Failed to create metrics exporter. factory={}, registry={}",
                            exporterFactory.name(),
                            registryName,
                            e);
                    continue;
                }

                switch (exporter) {
                    case null ->
                        logger.info(
                                "Metrics exporter factory doesn't create exporter. factory={}, registry={}",
                                exporterFactory.name(),
                                registryName);
                    case PullingMetricsExporter pullingExporter -> withExporter(pullingExporter);
                    case PushingMetricsExporter pushingExporter -> withExporter(pushingExporter);
                    default ->
                        logger.warn(
                                "Unsupported exporter. type={}, factory={}",
                                exporter.getClass(),
                                exporterFactory.name());
                }
            }
        }

        /**
         * Builds the {@link MetricsExportManager} instance managing provided metrics registry.
         * <p>
         * If configuration was provided and exporting is disabled, a no-op export manager will be returned.<br>
         * If no exporters were added or discovered, a no-op export manager will be returned.<br>
         * If only a single pulling exporter is added, a single pulling exporter export manager will be returned
         * without a running export thread, allowing manual pulling of metrics on demand.<br>
         * If multiple exporters are added, a default export manager will be returned with a running export thread,
         * that exports metrics at the configured interval using the provided executor service factory, providing
         * metrics snapshots to all pulling/pushing exporters.
         *
         * @param metricRegistry the metric registry to be managed, must not be {@code null}
         * @return the constructed {@link MetricsExportManager}
         */
        @NonNull
        public MetricsExportManager build(@NonNull MetricRegistry metricRegistry) {
            Objects.requireNonNull(metricRegistry, "metrics registry must not be null");

            if (configuration != null) {
                MetricsExportManagerConfig exportConfig = configuration.getConfigData(MetricsExportManagerConfig.class);

                if (!exportConfig.enabled()) {
                    logger.info("Metrics export manager is disabled in configuration. Using no-op export manager.");
                    return new NoOpMetricsExportManager(metricRegistry);
                }

                withExportIntervalSeconds(exportConfig.exportIntervalSeconds());

                pushingExporters.removeIf(exporter -> !exportConfig.isExporterEnabled(exporter.name()));
                pullingExporters.removeIf(exporter -> !exportConfig.isExporterEnabled(exporter.name()));

                loadExporters(metricRegistry.name(), exportConfig);
            }

            if (pushingExporters.isEmpty() && pullingExporters.isEmpty()) {
                logger.info("No enabled metrics exporters found. Using no-op export manager.");
                return new NoOpMetricsExportManager(metricRegistry);
            }

            if (pushingExporters.isEmpty() && pullingExporters.size() == 1) {
                logger.info("Single pulling exporter found. No export thread will be running.");
                return new SinglePullingExporterMetricsExportManager(
                        (SnapshotableMetricsRegistry) metricRegistry, pullingExporters.getFirst());
            }

            return new DefaultMetricsExportManager(
                    (SnapshotableMetricsRegistry) metricRegistry,
                    executorServiceFactory,
                    exportIntervalSeconds,
                    pullingExporters,
                    pushingExporters);
        }
    }
}
