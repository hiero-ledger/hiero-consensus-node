// SPDX-License-Identifier: Apache-2.0
package com.swirlds.common.metrics.platform.prometheus;

import static com.swirlds.common.metrics.platform.prometheus.PrometheusEndpoint.AdapterType.GLOBAL;
import static com.swirlds.common.metrics.platform.prometheus.PrometheusEndpoint.AdapterType.PLATFORM;
import static com.swirlds.common.metrics.platform.prometheus.PrometheusEndpoint.NODE_LABEL;
import static com.swirlds.common.metrics.platform.prometheus.PrometheusEndpoint.TYPE_LABEL;

import com.swirlds.common.metrics.platform.prometheus.PrometheusEndpoint.AdapterType;
import com.swirlds.metrics.api.Metric;
import com.swirlds.metrics.api.snapshot.Snapshot;
import io.prometheus.client.Collector;
import io.prometheus.client.CollectorRegistry;
import io.prometheus.client.Gauge;
import java.util.Objects;
import org.hiero.consensus.model.node.NodeId;

/**
 * Adapter that synchronizes {@link com.swirlds.common.metrics.RunningAverageMetric} and
 * {@link com.swirlds.common.metrics.SpeedometerMetric} with the corresponding Prometheus {@link Collector}.
 */
public class DistributionAdapter extends AbstractMetricAdapter {

    private final Gauge gauge;

    /**
     * Constructor of {@code DistributionAdapter}.
     *
     * @param registry
     * 		The {@link CollectorRegistry} with which the Prometheus {@link Collector} should be registered
     * @param metric
     * 		The {@link Metric} which value should be reported to Prometheus
     * @param adapterType
     * 		Scope of the {@link Metric}, either {@link AdapterType#GLOBAL} or {@link AdapterType#PLATFORM}
     * @throws NullPointerException if any of the following parameters are {@code null}.
     *     <ul>
     *       <li>{@code registry}</li>
     *       <li>{@code metric}</li>
     *     </ul>
     */
    public DistributionAdapter(final CollectorRegistry registry, final Metric metric, final AdapterType adapterType) {
        super(adapterType, metric);
        Objects.requireNonNull(registry, "registry must not be null");
        Objects.requireNonNull(metric, "metric must not be null");
        final Gauge.Builder builder = assignCommonValues(new Gauge.Builder());
        if (adapterType == PLATFORM) {
            builder.labelNames(NODE_LABEL, TYPE_LABEL);
        } else {
            builder.labelNames(TYPE_LABEL);
        }
        this.gauge = builder.register(registry);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void update(final Snapshot snapshot, final NodeId nodeId) {
        Objects.requireNonNull(snapshot, "snapshot must not be null");
        if (adapterType != GLOBAL) {
            Objects.requireNonNull(nodeId, "nodeId must not be null");
        }
        for (final Snapshot.SnapshotEntry entry : snapshot.entries()) {
            final String valueType =
                    switch (entry.valueType()) {
                        case MIN -> "min";
                        case MAX -> "max";
                        case STD_DEV -> "stddev";
                        default -> "mean";
                    };
            final Gauge.Child child =
                    adapterType == GLOBAL ? gauge.labels(valueType) : gauge.labels(nodeId.toString(), valueType);
            child.set(((Number) entry.value()).doubleValue());
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void unregister(final CollectorRegistry registry) {
        registry.unregister(gauge);
    }
}
