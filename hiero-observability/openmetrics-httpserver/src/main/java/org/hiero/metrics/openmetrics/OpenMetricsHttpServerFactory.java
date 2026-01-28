// SPDX-License-Identifier: Apache-2.0
package org.hiero.metrics.openmetrics;

import com.swirlds.config.api.Configuration;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;
import org.hiero.metrics.core.Label;
import org.hiero.metrics.core.MetricsExporter;
import org.hiero.metrics.core.MetricsExporterFactory;
import org.hiero.metrics.openmetrics.config.OpenMetricsHttpServerConfig;

/**
 * Implementation of {@link MetricsExporterFactory} for creating OpenMetrics HTTP server exporters.
 * Uses {@link OpenMetricsHttpServerConfig} for configuration.
 */
public final class OpenMetricsHttpServerFactory implements MetricsExporterFactory {

    @Nullable
    @Override
    public MetricsExporter createExporter(
            @NonNull List<Label> registryGlobalLabels, @NonNull Configuration configuration) {
        OpenMetricsHttpServerConfig exportConfig = configuration.getConfigData(OpenMetricsHttpServerConfig.class);

        if (!exportConfig.enabled()) {
            return null;
        }

        try {
            return new OpenMetricsHttpServer(exportConfig);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
