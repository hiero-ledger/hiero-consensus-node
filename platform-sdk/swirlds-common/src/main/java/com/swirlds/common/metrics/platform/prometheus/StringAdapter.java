// SPDX-License-Identifier: Apache-2.0
package com.swirlds.common.metrics.platform.prometheus;

import static com.swirlds.common.metrics.platform.prometheus.PrometheusEndpoint.AdapterType.GLOBAL;
import static com.swirlds.common.metrics.platform.prometheus.PrometheusEndpoint.AdapterType.PLATFORM;
import static com.swirlds.common.metrics.platform.prometheus.PrometheusEndpoint.NODE_LABEL;

import com.swirlds.common.metrics.platform.prometheus.PrometheusEndpoint.AdapterType;
import com.swirlds.metrics.api.Metric;
import com.swirlds.metrics.api.snapshot.Snapshot;
import io.prometheus.client.Collector;
import io.prometheus.client.CollectorRegistry;
import io.prometheus.client.Info;
import java.util.Objects;
import org.hiero.consensus.model.node.NodeId;

/**
 * Adapter that synchronizes a {@link Metric} with a single value of {@link Metric#getDataType() type} {@code String}
 * with the corresponding Prometheus {@link Collector}.
 */
public class StringAdapter extends AbstractMetricAdapter {

    private final Info info;

    /**
     * Constructor of {@code StringAdapter}.
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
    public StringAdapter(final CollectorRegistry registry, final Metric metric, final AdapterType adapterType) {
        super(adapterType, metric, false);
        Objects.requireNonNull(registry, "registry must not be null");
        Objects.requireNonNull(metric, "metric must not be null");
        final Info.Builder builder = assignCommonValues(new Info.Builder());
        if (adapterType == PLATFORM) {
            builder.labelNames(NODE_LABEL);
        }
        this.info = builder.register(registry);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void update(final Snapshot snapshot, final NodeId nodeId) {
        Objects.requireNonNull(snapshot, "snapshot must not be null");
        final String newValue = Objects.toString(snapshot.getValue());
        if (adapterType == GLOBAL) {
            info.info("value", newValue);
        } else {
            Objects.requireNonNull(nodeId, "nodeId must not be null");
            final Info.Child child = info.labels(nodeId.toString());
            child.info("value", newValue);
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void unregister(final CollectorRegistry registry) {
        registry.unregister(info);
    }
}
