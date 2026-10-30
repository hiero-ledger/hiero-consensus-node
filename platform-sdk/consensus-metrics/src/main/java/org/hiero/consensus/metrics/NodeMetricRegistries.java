// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.metrics;

import edu.umd.cs.findbugs.annotations.NonNull;
import org.hiero.metrics.core.Label;
import org.hiero.metrics.core.MetricRegistry;

/**
 * Creates {@link MetricRegistry} builders for a node, so every registry acting on behalf of a node carries the
 * same global labels and discovers the same metric providers, whether or not it exports.
 */
public final class NodeMetricRegistries {

    /** Name of the global label identifying the node a registry belongs to, as on the old Prometheus endpoint. */
    public static final String NODE_LABEL = "node";

    private NodeMetricRegistries() {
        throw new UnsupportedOperationException("Utility class");
    }

    /**
     * Creates a registry builder for a node, labelled with its id and with metric provider discovery enabled.
     * Callers that should publish metrics add {@link MetricRegistry.Builder#discoverMetricsExporter} before
     * building.
     *
     * @param nodeId the id of the node the registry belongs to, used as the {@value #NODE_LABEL} global label
     * @return the registry builder, never {@code null}
     */
    @NonNull
    public static MetricRegistry.Builder builder(final long nodeId) {
        return MetricRegistry.builder()
                .addGlobalLabel(new Label(NODE_LABEL, Long.toString(nodeId)))
                .discoverMetricProviders();
    }
}
