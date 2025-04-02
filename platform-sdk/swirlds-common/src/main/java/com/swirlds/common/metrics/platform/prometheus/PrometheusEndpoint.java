// SPDX-License-Identifier: Apache-2.0
package com.swirlds.common.metrics.platform.prometheus;

import static com.swirlds.common.metrics.platform.DefaultPlatformMetrics.EXCEPTION_RATE_THRESHOLD;
import static com.swirlds.common.metrics.platform.DefaultPlatformMetrics.calculateMetricKey;
import static com.swirlds.common.metrics.platform.prometheus.PrometheusEndpoint.AdapterType.GLOBAL;
import static com.swirlds.common.metrics.platform.prometheus.PrometheusEndpoint.AdapterType.PLATFORM;
import static com.swirlds.logging.legacy.LogMarker.EXCEPTION;
import static com.swirlds.logging.legacy.LogMarker.STARTUP;

import com.sun.net.httpserver.HttpServer;
import com.swirlds.base.utility.AutoCloseableNonThrowing;
import com.swirlds.common.metrics.FunctionGauge;
import com.swirlds.common.metrics.IntegerPairAccumulator;
import com.swirlds.common.metrics.RunningAverageMetric;
import com.swirlds.common.metrics.SpeedometerMetric;
import com.swirlds.common.metrics.StatEntry;
import com.swirlds.common.metrics.platform.DefaultPlatformMetrics;
import com.swirlds.common.metrics.platform.MetricsEvent;
import com.swirlds.common.metrics.platform.SnapshotEvent;
import com.swirlds.common.utility.ThresholdLimitingHandler;
import com.swirlds.metrics.api.Counter;
import com.swirlds.metrics.api.Metric;
import com.swirlds.metrics.api.Metrics;
import com.swirlds.metrics.api.snapshot.Snapshot;
import io.prometheus.client.CollectorRegistry;
import io.prometheus.client.exporter.HTTPServer;
import java.io.IOException;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hiero.consensus.model.node.NodeId;

/**
 * A Prometheus endpoint that shows all {@link Metric}s.
 */
public class PrometheusEndpoint implements AutoCloseableNonThrowing {

    private static final Logger logger = LogManager.getLogger(PrometheusEndpoint.class);

    /** Prometheus-label to differentiate between nodes */
    public static final String NODE_LABEL = "node";
    /** Prometheus-label to differentiate between value-types (mean, min, max, stddev) */
    public static final String TYPE_LABEL = "type";

    private static final String TIME_METRIC_KEY =
            DefaultPlatformMetrics.calculateMetricKey(Metrics.INFO_CATEGORY, "time");

    /** Scope of a metric */
    public enum AdapterType {
        GLOBAL,
        PLATFORM
    }

    private final CollectorRegistry registry;
    private final Map<String, MetricAdapter> adapters = new ConcurrentHashMap<>();
    private final HTTPServer httpServer;
    private final ThresholdLimitingHandler<Throwable> exceptionRateLimiter =
            new ThresholdLimitingHandler<>(EXCEPTION_RATE_THRESHOLD);

    /**
     * Constructor of the {@code PrometheusEndpoint}
     *
     * @param httpServer
     * 		The {@link HttpServer} to use for the HTTP-endpoint
     * @throws IllegalArgumentException if {@code httpServer} is {@code null}
     * @throws IOException if setting up the {@link HttpServer} fails
     */
    @SuppressWarnings("removal")
    public PrometheusEndpoint(final HttpServer httpServer) throws IOException {
        this(httpServer, new CollectorRegistry(false));
    }

    /**
     * This constructor should only be used for tests. Will become package private at some point.
     * @deprecated Use {@link PrometheusEndpoint#PrometheusEndpoint(HttpServer)}
     */
    @Deprecated(forRemoval = true)
    public PrometheusEndpoint(final HttpServer httpServer, final CollectorRegistry registry) throws IOException {
        Objects.requireNonNull(httpServer, "httpServer must not be null");
        this.registry = Objects.requireNonNull(registry, "registry must not be null");

        logger.info(
                STARTUP.getMarker(),
                "PrometheusEndpoint: Starting server listing on port: {}",
                httpServer.getAddress().getPort());

        this.httpServer = new HTTPServer.Builder()
                .withHttpServer(httpServer)
                .withRegistry(registry)
                .build();
    }

    /**
     * This method handles the addition and removal of {@link Metric}s. It should only be called by
     * the {@link com.swirlds.common.notification.NotificationEngine}.
     *
     * @param notification
     * 		the {@link MetricsEvent}
     * @throws NullPointerException in case {@code notification} parameter is {@code null}
     */
    public void handleMetricsChange(final MetricsEvent notification) {
        Objects.requireNonNull(notification, "notification must not be null");

        final Metric metric = notification.metric();
        final String metricKey = calculateMetricKey(metric);
        if (TIME_METRIC_KEY.equals(metricKey)) {
            // filter out the time metric, because Prometheus has its own mechanism to store the timestamp,
            // and it cannot handle often changing String-values
            return;
        }

        if (notification.type() == MetricsEvent.Type.ADDED) {
            adapters.computeIfAbsent(metricKey, key -> doCreate(notification.nodeId(), metric))
                    .incAndGetReferenceCount();
        } else {
            final MetricAdapter adapter = adapters.get(metricKey);
            if (adapter != null && adapter.decAndGetReferenceCount() == 0) {
                adapter.unregister(registry);
                adapters.remove(metricKey);
            }
        }
    }

    /**
     * This method handles new snapshots. It should only be called by
     * the {@link com.swirlds.common.notification.NotificationEngine}.
     *
     * @param notification
     * 		the {@link SnapshotEvent}
     */
    public void handleSnapshots(final SnapshotEvent notification) {
        Objects.requireNonNull(notification, "notification must not be null");

        for (final Snapshot snapshot : notification.snapshots()) {
            final String metricKey = calculateMetricKey(snapshot.metric());
            final MetricAdapter adapter = adapters.get(metricKey);
            if (adapter != null) {
                try {
                    adapter.update(snapshot, notification.nodeId());
                } catch (RuntimeException ex) {
                    exceptionRateLimiter.handle(
                            ex,
                            error -> logger.error(
                                    EXCEPTION.getMarker(),
                                    "Exception while trying to update Prometheus endpoint with snapshot {}",
                                    snapshot,
                                    ex));
                }
            }
        }
    }

    @SuppressWarnings("removal")
    private MetricAdapter doCreate(final NodeId nodeId, final Metric metric) {
        final AdapterType adapterType = nodeId == null ? GLOBAL : PLATFORM;
        if (metric instanceof Counter) {
            return new CounterAdapter(registry, metric, adapterType);
        } else if (metric instanceof RunningAverageMetric || metric instanceof SpeedometerMetric) {
            return new DistributionAdapter(registry, metric, adapterType);
        } else if (metric instanceof IntegerPairAccumulator<?>
                || metric instanceof FunctionGauge<?>
                || metric instanceof StatEntry) {
            return switch (metric.getDataType()) {
                case STRING -> new StringAdapter(registry, metric, adapterType);
                case BOOLEAN -> new BooleanAdapter(registry, metric, adapterType);
                default -> new NumberAdapter(registry, metric, adapterType);
            };
        } else {
            return new NumberAdapter(registry, metric, adapterType);
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void close() {
        httpServer.close();
    }
}
