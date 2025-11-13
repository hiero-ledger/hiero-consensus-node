// SPDX-License-Identifier: Apache-2.0
package org.hiero.metrics.internal.export.config;

import static com.swirlds.config.api.ConfigProperty.NULL_DEFAULT_VALUE;

import com.swirlds.config.api.ConfigData;
import com.swirlds.config.api.ConfigProperty;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Set;

/**
 * Configuration for the {@link org.hiero.metrics.api.export.MetricsExportManager}.
 *
 * @param enabled whether the export manager is enabled or disabled and no-op (default: true)
 * @param enabledExporters comma-separated list of enabled exporter factories (default: null, meaning all exporters are enabled)
 * @param disabledExporters comma-separated list of disabled exporter factories (default: null, meaning no exporters are disabled)
 */
@ConfigData("metrics.export.manager")
public record MetricsExportManagerConfig(
        @ConfigProperty(defaultValue = "true") boolean enabled,
        @ConfigProperty(defaultValue = NULL_DEFAULT_VALUE) Set<String> enabledExporters,
        @ConfigProperty(defaultValue = NULL_DEFAULT_VALUE) Set<String> disabledExporters) {

    /**
     * Check if the exporter factory with the given name is enabled based on the configuration.
     */
    public boolean isExporterEnabled(@NonNull String exporterFactory) {
        if (!enabled) {
            return false;
        }
        if (disabledExporters != null && disabledExporters.contains(exporterFactory)) {
            return false;
        }
        if (enabledExporters != null && !enabledExporters.isEmpty()) {
            return enabledExporters.contains(exporterFactory);
        }
        return true; // enabled by default if not specified
    }
}
