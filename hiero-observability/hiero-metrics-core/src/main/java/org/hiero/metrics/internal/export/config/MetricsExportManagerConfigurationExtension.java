// SPDX-License-Identifier: Apache-2.0
package org.hiero.metrics.internal.export.config;

import com.swirlds.config.api.ConfigurationExtension;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Set;

public final class MetricsExportManagerConfigurationExtension implements ConfigurationExtension {

    @NonNull
    @Override
    public Set<Class<? extends Record>> getConfigDataTypes() {
        return Set.of(MetricsExportManagerConfig.class);
    }
}
