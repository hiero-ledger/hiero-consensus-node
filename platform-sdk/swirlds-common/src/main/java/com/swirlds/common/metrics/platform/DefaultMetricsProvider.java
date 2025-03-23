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
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hiero.consensus.model.node.NodeId;

/**
 * The default implementation of {@link PlatformMetricsProvider}
 * FUTURE: Follow our naming patterns and rename to PlatformMetricsProviderImpl
 */
public class DefaultMetricsProvider implements PlatformMetricsProvider, Lifecycle {

    private static final Logger logger = LogManager.getLogger(DefaultMetricsProvider.class);

    private final @NonNull PlatformMetricsFactory factory;
    private final @NonNull ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(
            getStaticThreadManager().createThreadFactory("platform-core", "MetricsThread"));

    private final @NonNull MetricKeyRegistry metricKeyRegistry = new MetricKeyRegistry();
    private final @NonNull DefaultPlatformMetrics globalMetrics;
    private final @NonNull ConcurrentMap<NodeId, DefaultPlatformMetrics> platformMetrics = new ConcurrentHashMap<>();
    private final @Nullable PrometheusEndpoint prometheusEndpoint;
    private final @NonNull SnapshotService snapshotService;
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

        globalMetrics = new DefaultPlatformMetrics(null, metricKeyRegistry, executor, factory, metricsConfig);

        // setup SnapshotService
        snapshotService = new SnapshotService(globalMetrics, executor, metricsConfig.getMetricsSnapshotDuration());

        // setup Prometheus endpoint
        PrometheusEndpoint endpoint = null;
        if (!metricsConfig.disableMetricsOutput() && prometheusConfig.endpointEnabled()) {
            final InetSocketAddress address = new InetSocketAddress(prometheusConfig.endpointPortNumber());
            try {
                final HttpServer httpServer = HttpServer.create(address, prometheusConfig.endpointMaxBacklogAllowed());
                endpoint = new PrometheusEndpoint(httpServer);

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
    @Override
    public @NonNull Metrics createPlatformMetrics(@NonNull final NodeId nodeId) {
        Objects.requireNonNull(nodeId, "selfId must not be null");

        final DefaultPlatformMetrics newMetrics =
                new DefaultPlatformMetrics(nodeId, metricKeyRegistry, executor, factory, metricsConfig);

        final DefaultPlatformMetrics oldMetrics = platformMetrics.putIfAbsent(nodeId, newMetrics);
        if (oldMetrics != null) {
            throw new IllegalStateException(String.format("PlatformMetrics for %s already exists", nodeId));
        }
        globalMetrics.subscribe(newMetrics::handleGlobalMetrics);

        if (lifecyclePhase == LifecyclePhase.STARTED) {
            newMetrics.start();
        }
        snapshotService.addPlatformMetric(newMetrics);

        if (!metricsConfig.disableMetricsOutput()) {
            final String folderName = metricsConfig.csvOutputFolder();
            final Path folderPath = Path.of(folderName.isBlank() ? FileUtils.getUserDir() : folderName);

            // setup LegacyCsvWriter
            if (!metricsConfig.csvFileName().isBlank()) {
                final LegacyCsvWriter legacyCsvWriter = new LegacyCsvWriter(nodeId, folderPath, configuration);
                snapshotService.subscribe(legacyCsvWriter::handleSnapshots);
            }

            // setup Prometheus Endpoint
            if (prometheusEndpoint != null) {
                newMetrics.subscribe(prometheusEndpoint::handleMetricsChange);
            }
        }

        return newMetrics;
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
            for (final DefaultPlatformMetrics metrics : platformMetrics.values()) {
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
