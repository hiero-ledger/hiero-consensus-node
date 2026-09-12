// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.fakes.noop;

import edu.umd.cs.findbugs.annotations.NonNull;
import org.hiero.metrics.core.Label;
import org.hiero.metrics.core.MetricRegistry;
import org.hiero.metrics.core.MetricsRegistrationProvider;

/**
 * Creates {@link MetricRegistry} instances for tools, tests and benchmarks: registries that have no
 * {@link org.hiero.metrics.core.MetricsExporter}, so metrics are registered and updated but never leave the
 * process. Without an exporter the registry also skips snapshotting entirely, so the registries produced here
 * are close to free.
 * <p>
 * This is the {@link MetricRegistry} counterpart of {@link NoOpMetrics}, and exists for the same reason: a
 * node that is not really being observed still has to hand its components something to record into.
 * <p>
 * Metric provider discovery is deliberately left <b>on</b>. Code that has been migrated to this framework
 * looks its metrics up with {@link MetricRegistry#getMetric(org.hiero.metrics.core.MetricKey)}, which fails
 * if the declaring module's {@link MetricsRegistrationProvider} was never consulted; turning discovery off
 * here would break migrated code under test only.
 */
public final class NoOpMetricRegistries {

    /** Name of the global label identifying the node a registry belongs to. */
    public static final String NODE_LABEL_NAME = "node";

    private NoOpMetricRegistries() {
        throw new UnsupportedOperationException("Utility class");
    }

    /**
     * Creates an exporter-free registry that is not tied to a particular node.
     *
     * @return the created registry, never {@code null}
     */
    @NonNull
    public static MetricRegistry create() {
        return MetricRegistry.builder().discoverMetricProviders().build();
    }

    /**
     * Creates an exporter-free registry acting on behalf of a node, labelled with its id.
     * <p>
     * The label matters because several of these registries coexist in one JVM whenever a simulated network
     * runs every node in-process; without it their metrics would be indistinguishable.
     *
     * @param nodeId the id of the node this registry belongs to, used as the {@value #NODE_LABEL_NAME} global
     *               label
     * @return the created registry, never {@code null}
     */
    @NonNull
    public static MetricRegistry create(final long nodeId) {
        return MetricRegistry.builder()
                .addGlobalLabel(new Label(NODE_LABEL_NAME, Long.toString(nodeId)))
                .discoverMetricProviders()
                .build();
    }
}
