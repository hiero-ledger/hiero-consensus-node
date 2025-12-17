// SPDX-License-Identifier: Apache-2.0
package com.swirlds.common.metrics.platform;

import static com.swirlds.common.threading.manager.AdHocThreadManager.getStaticThreadManager;
import static com.swirlds.logging.legacy.LogMarker.EXCEPTION;

import com.sun.net.httpserver.HttpServer;
import com.swirlds.base.state.Lifecycle;
import com.swirlds.base.state.LifecyclePhase;
import com.swirlds.common.io.utility.FileUtils;
import com.swirlds.common.metrics.PlatformMetricsFactory;
import com.swirlds.common.metrics.PlatformMetricsProvider;
import com.swirlds.common.metrics.config.MetricsConfig;
import com.swirlds.common.metrics.platform.prometheus.PrometheusConfig;
import com.swirlds.common.metrics.platform.prometheus.PrometheusEndpoint;
import com.swirlds.config.api.Configuration;
import com.swirlds.metrics.api.Metrics;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * The default implementation of {@link PlatformMetricsProvider}
 * FUTURE: Follow our naming patterns and rename to PlatformMetricsProviderImpl
 *
 * @param <KEY> the type of the unique identifier for separate instances of metrics
 */
public class DefaultMetricsProvider<KEY> implements PlatformMetricsProvider<KEY>, Lifecycle {

    private static final Logger logger = LogManager.getLogger(DefaultMetricsProvider.class);

    @NonNull
    private final PlatformMetricsFactory factory;

    @NonNull
    private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(
            getStaticThreadManager().createThreadFactory("platform-core", "MetricsThread"));

    @NonNull
    private final MetricKeyRegistry<KEY> metricKeyRegistry = new MetricKeyRegistry<>();

    @NonNull
    private final DefaultPlatformMetrics<KEY> globalMetrics;

    @NonNull
    private final ConcurrentMap<KEY, DefaultPlatformMetrics<KEY>> platformMetrics = new ConcurrentHashMap<>();

    @NonNull
    private final Map<KEY, List<Runnable>> unsubscribers = new HashMap<>();

    @Nullable
    private final PrometheusEndpoint<KEY> prometheusEndpoint;

    @NonNull
    private final SnapshotService<KEY> snapshotService;
    private final @NonNull MetricsConfig metricsConfig;
    private final @NonNull Configuration configuration;

    private @NonNull LifecyclePhase lifecyclePhase = LifecyclePhase.NOT_STARTED;

    /**
     * Constructor of {@code DefaultMetricsProvider}
     */
    public DefaultMetricsProvider(@NonNull final Configuration configuration) {
        this.configuration = Objects.requireNonNull(configuration, "configuration must not be null");

        metricsConfig = configuration.getConfigData(MetricsConfig.class);
        final PrometheusConfig prometheusConfig = configuration.getConfigData(PrometheusConfig.class);
        factory = new PlatformMetricsFactoryImpl(metricsConfig);

        globalMetrics = new DefaultPlatformMetrics<>(null, metricKeyRegistry, executor, factory, metricsConfig);

        // setup SnapshotService
        snapshotService = new SnapshotService<>(globalMetrics, executor, metricsConfig.getMetricsSnapshotDuration());

        // setup Prometheus endpoint
        PrometheusEndpoint<KEY> endpoint = null;
        if (!metricsConfig.disableMetricsOutput() && prometheusConfig.endpointEnabled()) {
            final InetSocketAddress address = new InetSocketAddress(prometheusConfig.endpointPortNumber());
            try {
                final HttpServer httpServer = HttpServer.create(address, prometheusConfig.endpointMaxBacklogAllowed());
                endpoint = new PrometheusEndpoint<>(httpServer);

                globalMetrics.subscribe(endpoint::handleMetricsChange);
                snapshotService.subscribe(endpoint::handleSnapshots);
            } catch (final IOException e) {
                logger.error(EXCEPTION.getMarker(), "Exception while setting up Prometheus endpoint", e);
            }
        }
        prometheusEndpoint = endpoint;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public @NonNull Metrics createGlobalMetrics() {
        return this.globalMetrics;
    }

    /**
     * {@inheritDoc}
     */
    @NonNull
    @Override
    public Metrics createPlatformMetrics(@NonNull final KEY key) {
        Objects.requireNonNull(key, "key must not be null");

        final DefaultPlatformMetrics<KEY> newMetrics =
                new DefaultPlatformMetrics<>(key, metricKeyRegistry, executor, factory, metricsConfig);

        final DefaultPlatformMetrics<KEY> oldMetrics = platformMetrics.putIfAbsent(key, newMetrics);
        if (oldMetrics != null) {
            throw new IllegalStateException(String.format("PlatformMetrics for %s already exists", key));
        }

        final Runnable unsubscribeGlobalMetrics = globalMetrics.subscribe(newMetrics::handleGlobalMetrics);
        unsubscribers.put(key, List.of(unsubscribeGlobalMetrics));

        if (lifecyclePhase == LifecyclePhase.STARTED) {
            newMetrics.start();
        }
        snapshotService.addPlatformMetric(newMetrics);

        if (!metricsConfig.disableMetricsOutput()) {
            final String folderName = metricsConfig.csvOutputFolder();
            final Path folderPath = Path.of(folderName.isBlank() ? FileUtils.getUserDir() : folderName);

            // setup LegacyCsvWriter
            if (!metricsConfig.csvFileName().isBlank()) {
                final LegacyCsvWriter<KEY> legacyCsvWriter = new LegacyCsvWriter<>(key, folderPath, configuration);
                final Runnable unsubscribeCsvWriter = snapshotService.subscribe(legacyCsvWriter::handleSnapshots);
                unsubscribers.put(key, List.of(unsubscribeCsvWriter, unsubscribeGlobalMetrics));
            }

            // setup Prometheus Endpoint
            if (prometheusEndpoint != null) {
                newMetrics.subscribe(prometheusEndpoint::handleMetricsChange);
            }
        }

        return newMetrics;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void removePlatformMetrics(@NonNull final KEY key) throws InterruptedException {
        Objects.requireNonNull(key, "key must not be null");

        final DefaultPlatformMetrics<KEY> metrics = platformMetrics.get(key);
        if (metrics == null) {
            throw new IllegalArgumentException(String.format("PlatformMetrics for %s does not exist", key));
        }

        metrics.shutdown();
        unsubscribers.remove(key).forEach(Runnable::run);
        snapshotService.removePlatformMetric(metrics);
        platformMetrics.remove(key);
    }

    @Override
    public @NonNull LifecyclePhase getLifecyclePhase() {
        return lifecyclePhase;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void start() {
        if (lifecyclePhase == LifecyclePhase.NOT_STARTED) {
            globalMetrics.start();
            for (final DefaultPlatformMetrics<KEY> metrics : platformMetrics.values()) {
                metrics.start();
            }
            snapshotService.start();
            lifecyclePhase = LifecyclePhase.STARTED;
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void stop() {
        if (lifecyclePhase == LifecyclePhase.STARTED) {
            snapshotService.shutdown();
            if (prometheusEndpoint != null) {
                prometheusEndpoint.close();
            }
            lifecyclePhase = LifecyclePhase.STOPPED;
        }
    }
}
