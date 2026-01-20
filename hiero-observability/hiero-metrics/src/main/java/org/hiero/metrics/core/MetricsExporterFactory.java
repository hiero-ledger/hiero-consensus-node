// SPDX-License-Identifier: Apache-2.0
package org.hiero.metrics.core;

import com.swirlds.config.api.Configuration;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.List;

/**
 * A factory interface for creating {@link MetricsExporter} instances based on the provided configuration.
 */
@FunctionalInterface
public interface MetricsExporterFactory {

    /**
     * Creates a new {@link MetricsExporter} instance based on the provided metrics registry global labels
     * and configuration. Labels could be used to distinguish exporters created for different registries.
     * May return {@code null} if the factory cannot create an exporter with the given configuration
     * (e.g. disabled flag or wrong configuration).
     *
     * @param registryGlobalLabels global labels of metrics registry to export, could be empty, but never {@code null}
     * @param configuration the configuration to use for creating the exporter, must not be {@code null}
     * @return a new instance of {@link MetricsExporter}, or {@code null} if not able to create one.
     */
    @Nullable
    MetricsExporter createExporter(@NonNull List<Label> registryGlobalLabels, @NonNull Configuration configuration);
}
