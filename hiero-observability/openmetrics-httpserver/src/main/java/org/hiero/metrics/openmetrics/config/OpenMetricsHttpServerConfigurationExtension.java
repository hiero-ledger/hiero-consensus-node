// SPDX-License-Identifier: Apache-2.0
package org.hiero.metrics.openmetrics.config;

import com.swirlds.config.api.ConfigurationExtension;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Set;

/**
 * {@link ConfigurationExtension} for the OpenMetrics HTTP endpoint,
 * allowing to fetch {@link OpenMetricsHttpServerConfig}.
 */
public final class OpenMetricsHttpServerConfigurationExtension implements ConfigurationExtension {

    @NonNull
    @Override
    public Set<Class<? extends Record>> getConfigDataTypes() {
        return Set.of(OpenMetricsHttpServerConfig.class);
    }
}
