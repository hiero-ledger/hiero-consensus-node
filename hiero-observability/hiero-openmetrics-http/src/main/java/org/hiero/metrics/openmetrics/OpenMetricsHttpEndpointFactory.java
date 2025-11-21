// SPDX-License-Identifier: Apache-2.0
package org.hiero.metrics.openmetrics;

import com.swirlds.config.api.Configuration;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.io.IOException;
import java.io.UncheckedIOException;
import org.hiero.metrics.api.export.MetricsExporter;
import org.hiero.metrics.api.export.MetricsExporterFactory;
import org.hiero.metrics.openmetrics.config.OpenMetricsHttpEndpointConfig;

/**
 * Implementation of {@link MetricsExporterFactory} for creating OpenMetrics HTTP endpoint exporters.
 * Uses {@link OpenMetricsHttpEndpointConfig} for configuration.
 */
public final class OpenMetricsHttpEndpointFactory implements MetricsExporterFactory {

    @NonNull
    @Override
    public String name() {
        return "openmetrics-http-endpoint";
    }

    @Nullable
    @Override
    public MetricsExporter createExporter(@NonNull String metricsRegistryName, @NonNull Configuration configuration) {
        OpenMetricsHttpEndpointConfig exportConfig = configuration.getConfigData(OpenMetricsHttpEndpointConfig.class);
        if (!exportConfig.enabled()) {
            return null;
        }
        try {
            return new OpenMetricsHttpEndpoint(exportConfig);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
