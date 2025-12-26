// SPDX-License-Identifier: Apache-2.0
package org.hiero.metrics.api.export;

import com.swirlds.config.api.Configuration;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;

/**
 * A factory for creating {@link MetricsExporter} instances based on the provided configuration and metric registry name.
 * Exporters created by this factory must be either {@link PullingMetricsExporter} or {@link PushingMetricsExporter}.
 */
public interface MetricsExporterFactory {

    /**
     * @return the name of the exporter factory, never {@code null} or blank.
     */
    @NonNull
    String name();

    /**
     * Creates a new {@link MetricsExporter} instance based on the provided configuration.
     * May return {@code null} if the factory cannot create an exporter with the given configuration
     * (e.g. disabled flag or wrong configuration).
     *
     * @param metricsRegistryName the name of the metrics registry for which the exporter is created, must not be {@code null}
     * @param configuration the configuration to use for creating the exporter, must not be {@code null}
     * @return a new instance of {@link MetricsExporter}, or {@code null} if not able to create one.
     */
    @Nullable
    MetricsExporter createExporter(@NonNull String metricsRegistryName, @NonNull Configuration configuration);
}
