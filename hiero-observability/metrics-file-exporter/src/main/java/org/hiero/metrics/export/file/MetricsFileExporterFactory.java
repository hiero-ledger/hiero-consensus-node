// SPDX-License-Identifier: Apache-2.0
package org.hiero.metrics.export.file;

import com.swirlds.config.api.Configuration;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.List;
import org.hiero.metrics.core.Label;
import org.hiero.metrics.core.MetricsExporter;
import org.hiero.metrics.core.MetricsExporterFactory;
import org.hiero.metrics.export.file.config.MetricsFileExportConfig;

/**
 * Implementation of {@link MetricsExporterFactory} for creating OpenMetrics file exporters.
 * Uses {@link MetricsFileExportConfig} for configuration.
 */
public final class MetricsFileExporterFactory implements MetricsExporterFactory {

    @Nullable
    @Override
    public MetricsExporter createExporter(
            @NonNull List<Label> registryGlobalLabels, @NonNull Configuration configuration) {
        MetricsFileExportConfig exportConfig = configuration.getConfigData(MetricsFileExportConfig.class);

        if (!exportConfig.enabled()) {
            return null;
        }

        return new MetricsFileExporter(exportConfig);
    }
}
