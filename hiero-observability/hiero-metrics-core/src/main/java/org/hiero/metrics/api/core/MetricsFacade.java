// SPDX-License-Identifier: Apache-2.0
package org.hiero.metrics.api.core;

import static org.hiero.metrics.api.utils.MetricUtils.load;

import com.swirlds.config.api.Configuration;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.function.Supplier;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hiero.metrics.api.export.MetricsExportManager;
import org.hiero.metrics.api.export.MetricsExporter;
import org.hiero.metrics.api.export.MetricsExporterFactory;
import org.hiero.metrics.api.export.PullingMetricsExporter;
import org.hiero.metrics.api.export.PushingMetricsExporter;
import org.hiero.metrics.internal.export.config.MetricsExportManagerConfig;
import org.hiero.metrics.api.utils.MetricUtils;
import org.hiero.metrics.internal.core.MetricRegistryImpl;
import org.hiero.metrics.internal.export.DefaultMetricsExportManager;
import org.hiero.metrics.internal.export.NoOpMetricsExportManager;
import org.hiero.metrics.internal.export.SinglePullingExporterMetricsExportManager;

/**
 * Facade for creating and managing metrics registries and export managers.
 * <p>
 * Here is production-ready example of using the facade:
 * <pre>
 * {@code
 * Configuration configuration = ConfigurationBuilder.create()
 *      // init configuration
 *      .build();
 *
 * // create export manager named "my-app", that will discover all implementations
 * // of MetricsExporterFactory SPI and create exporters using the provided configuration.
 * // Scheduled thread executor will be only used if there is more than just
 * // a single pulling exporter to sync exports every 3 seconds.
 * MetricsExportManager exportManager = MetricsFacade.createExportManagerWithDiscoveredExporters(
 * 		"my-app", configuration, Executors::newSingleThreadScheduledExecutor, 3);
 *
 * // create metrics registry without global labels and register all metrics found
 * // by any implementation of MetricsRegistrationProvider SPI
 * MetricRegistry metricRegistry = MetricsFacade.createRegistryWithDiscoveredProviders();
 *
 * // allow export manager to manage registry and perform exports
 * exportManager.manageMetricRegistry(metricRegistry);
 *
 * // pass metrics registry to required classes to retrieve or register metrics
 * // Use IdempotentMetricsBinder to bind metrics registry in a thread-safe and idempotent way
 * MetricsBinder service = new MyService();
 * service.bindMetrics(metricRegistry);
 * }
 * </pre>
 *
 * @see MetricRegistry
 * @see MetricsExportManager
 * @see MetricsRegistrationProvider
 * @see MetricsBinder
 */
public final class MetricsFacade {

    private static final Logger logger = LogManager.getLogger(MetricsFacade.class);

    private MetricsFacade() {
        // Prevent instantiation
    }

    /**
     * Creates a new thread-safe {@link MetricRegistry} with the specified global labels.
     *
     * @param globalLabels the global labels to apply to all metrics in the registry, may be empty but not {@code null}
     * @return a new {@link MetricRegistry} instance
     */
    @NonNull
    public static MetricRegistry createRegistry(@NonNull Label... globalLabels) {
        Objects.requireNonNull(globalLabels, "global labels must not be null");
        return new MetricRegistryImpl(globalLabels);
    }

    /**
     * Creates a new thread-safe {@link MetricRegistry} and automatically registers metrics from all discovered
     * {@link MetricsRegistrationProvider} implementations using the Java {@link java.util.ServiceLoader} mechanism.
     *
     * @param globalLabels the global labels to apply to all metrics in the registry, may be empty but not {@code null}
     * @return a new {@link MetricRegistry} instance with registered metrics
     */
    public static MetricRegistry createRegistryWithDiscoveredProviders(@NonNull Label... globalLabels) {
        List<MetricsRegistrationProvider> providers = MetricUtils.load(MetricsRegistrationProvider.class);
        MetricRegistry registry = createRegistry(globalLabels);

        if (providers.isEmpty()) {
            logger.info("No metrics registration providers found. Creating empty registry.");
            return registry;
        }

        for (MetricsRegistrationProvider provider : providers) {
            logger.info("Registering metrics from provider: {}", provider.getClass());
            registry.registerMetrics(provider);
        }

        return registry;
    }

    // TODO support only one pulling exporter to avoid different clocks and inaccurate data ?

    /**
     * Creates a new {@link MetricsExportManager} using discovered via the Java {@link java.util.ServiceLoader}
     * mechanism implementations of {@link MetricsExporterFactory} to create either {@link PullingMetricsExporter}
     * or {@link PushingMetricsExporter} exporters.
     * Exporters that are failed to instantiate or not pulling or pushing will be ignored.
     * <p>
     * If no exporters are found, a no-op export manager is returned.
     * <p>
     * If a single pulling exporter is found, a manager without any threads will be created,
     * allowing export metrics on demand.
     * <p>
     * Otherwise, a default export manager is created with scheduled task submitted to executor service to do
     * periodic snapshots on all managed {@link MetricRegistry} instances and propagating snapshots to all exporters.
     * Only one snapshot at the time can be taken, so metric and its data point snapshots are reusable objects.
     * Exporters may use metric and data point snapshots as a key for hash map to store some specific representation
     * or template as a value.
     * <p>
     * Clients have to call {@link MetricsExportManager#manageMetricRegistry(MetricRegistry)}
     * to manage registries for exporting.
     *
     * @param name the name of the export manager (could be an application name), must not be blank
     * @param configuration configuration to be passed to exporter factories, must not be {@code null}
     * @param executorServiceFactory factory to create the {@link ScheduledExecutorService} for scheduling export task
     * @param exportIntervalSeconds the interval in seconds between export operations
     * @return a new {@link MetricsExportManager} instance
     */
    public static MetricsExportManager createExportManagerWithDiscoveredExporters(
            @NonNull String name,
            @NonNull Configuration configuration,
            @NonNull Supplier<ScheduledExecutorService> executorServiceFactory,
            int exportIntervalSeconds) {
        MetricsExportManagerConfig exportConfig = configuration.getConfigData(MetricsExportManagerConfig.class);

        if (!exportConfig.enabled()) {
            logger.info("Metrics export manager is disabled in configuration. Using no-op export manager.");
            return NoOpMetricsExportManager.INSTANCE;
        }

        List<MetricsExporterFactory> exporterFactories = load(MetricsExporterFactory.class);

        List<PullingMetricsExporter> pullingExporters = new ArrayList<>();
        List<PushingMetricsExporter> pushingExporters = new ArrayList<>();

        Optional<MetricsExporter> optionalExporter;
        for (MetricsExporterFactory exporterFactory : exporterFactories) {
            try {
                optionalExporter = exporterFactory.createExporter(configuration);
            } catch (Exception e) {
                logger.warn("Failed to create metrics exporter from factory: {}", exporterFactory.getClass(), e);
                continue;
            }

            if (optionalExporter.isEmpty()) {
                continue;
            }

            MetricsExporter exporter = optionalExporter.get();
            if (!exportConfig.isExporterEnabled(exporter.name())) {
                logger.info("Metrics exporter {} is disabled in configuration. Skipping.", exporter.name());
                try {
                    exporter.close();
                } catch (IOException e) {
                    throw new RuntimeException(
                            "Failed to close metrics exporter, which is disabled: " + exporter.name(), e);
                }
                continue;
            }

            if (exporter instanceof PullingMetricsExporter pullingExporter) {
                pullingExporters.add(pullingExporter);
            } else if (exporter instanceof PushingMetricsExporter pushingExporter) {
                pushingExporters.add(pushingExporter);
            } else {
                logger.warn(
                        "Unsupported exporter type {} create by factory {}",
                        exporter.getClass(),
                        exporterFactory.getClass());
            }
        }

        if (pullingExporters.isEmpty() && pushingExporters.isEmpty()) {
            logger.info("No enabled metrics exporters found. Using no-op export manager.");
            return NoOpMetricsExportManager.INSTANCE;
        }

        if (pushingExporters.isEmpty() && pullingExporters.size() == 1) {
            logger.info("Single pulling exporter found. No export thread will be running.");
            return new SinglePullingExporterMetricsExportManager(name, pullingExporters.getFirst());
        }

        return new DefaultMetricsExportManager(
                name, executorServiceFactory, exportIntervalSeconds, pullingExporters, pushingExporters);
    }

    /**
     * Creates a simple {@link MetricsExportManager} that periodically exports metrics from managed
     * {@link MetricRegistry} instances using the specified {@link PushingMetricsExporter}
     * and single threaded scheduled executor.
     *
     * @param exporter the pushing metrics exporter to use for exporting metrics, must not be {@code null}
     * @param exportIntervalSeconds the interval in seconds between export operations
     * @return a new {@link MetricsExportManager} instance
     */
    public static MetricsExportManager createExportManager(
            @NonNull PushingMetricsExporter exporter, int exportIntervalSeconds) {
        Objects.requireNonNull(exporter, "exporter must not be null");
        return new DefaultMetricsExportManager(
                exporter.name(),
                Executors::newSingleThreadScheduledExecutor,
                exportIntervalSeconds,
                List.of(),
                List.of(exporter));
    }

    /**
     * Creates a simple {@link MetricsExportManager} that allows on-demand exporting of metrics from managed
     * {@link MetricRegistry} instances using the specified {@link PullingMetricsExporter}. <p>
     * No export thread will be created.
     *
     * @param exporter the pulling metrics exporter to use for exporting metrics, must not be {@code null}
     * @return a new {@link MetricsExportManager} instance
     */
    public static MetricsExportManager createExportManager(@NonNull PullingMetricsExporter exporter) {
        Objects.requireNonNull(exporter, "exporter must not be null");
        return new SinglePullingExporterMetricsExportManager(exporter.name(), exporter);
    }
}
