// SPDX-License-Identifier: Apache-2.0
package org.hiero.metrics.internal.export.config;

import com.google.auto.service.AutoService;
import com.swirlds.config.api.ConfigurationExtension;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Set;

@AutoService(ConfigurationExtension.class)
public final class MetricsExportManagerConfigurationExtension implements ConfigurationExtension {

    @NonNull
    @Override
    public Set<Class<? extends Record>> getConfigDataTypes() {
        return Set.of(MetricsExportManagerConfig.class);
    }
}
