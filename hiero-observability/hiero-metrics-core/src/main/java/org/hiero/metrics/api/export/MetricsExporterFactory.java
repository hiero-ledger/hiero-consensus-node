// SPDX-License-Identifier: Apache-2.0
package org.hiero.metrics.api.export;

import com.swirlds.config.api.Configuration;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Optional;

/**
 * A factory for creating {@link MetricsExporter} instances based on the provided configuration.
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
     * Returns an empty {@link Optional} if the factory cannot create an exporter with the given configuration
     * (e.g. disabled flag or wrong configuration).
     *
     * @param configuration the configuration to use for creating the exporter, must not be {@code null}
     * @return a new instance of {@link MetricsExporter}, never {@code null}, wrapped into an {@link Optional}
     */
    @NonNull
    Optional<MetricsExporter> createExporter(@NonNull Configuration configuration);
}
