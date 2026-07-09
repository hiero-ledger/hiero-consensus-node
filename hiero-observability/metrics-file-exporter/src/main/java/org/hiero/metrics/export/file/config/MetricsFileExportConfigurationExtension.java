// SPDX-License-Identifier: Apache-2.0
package org.hiero.metrics.export.file.config;

import com.swirlds.config.api.ConfigurationExtension;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Set;

/**
 * {@link ConfigurationExtension} for the Prometheus exposition format file exporter,
 * allowing to fetch {@link MetricsFileExportConfig}.
 */
public final class MetricsFileExportConfigurationExtension implements ConfigurationExtension {

    @NonNull
    @Override
    public Set<Class<? extends Record>> getConfigDataTypes() {
        return Set.of(MetricsFileExportConfig.class);
    }
}
