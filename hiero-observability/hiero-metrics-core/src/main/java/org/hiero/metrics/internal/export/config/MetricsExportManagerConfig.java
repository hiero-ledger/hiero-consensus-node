// SPDX-License-Identifier: Apache-2.0
package org.hiero.metrics.internal.export.config;

import static com.swirlds.config.api.ConfigProperty.NULL_DEFAULT_VALUE;

import com.swirlds.config.api.ConfigData;
import com.swirlds.config.api.ConfigProperty;
import java.util.Set;

@ConfigData("metrics.export.manager")
public record MetricsExportManagerConfig(
        @ConfigProperty(defaultValue = "true") boolean enabled,
        @ConfigProperty(defaultValue = NULL_DEFAULT_VALUE) Set<String> enabledExporters,
        @ConfigProperty(defaultValue = NULL_DEFAULT_VALUE) Set<String> disabledExporters) {

    public boolean isExporterEnabled(String exporterName) {
        if (!enabled) {
            return false;
        }
        if (disabledExporters != null && disabledExporters.contains(exporterName)) {
            return false;
        }
        if (enabledExporters != null && !enabledExporters.isEmpty()) {
            return enabledExporters.contains(exporterName);
        }
        return true; // enabled by default if not specified
    }
}
