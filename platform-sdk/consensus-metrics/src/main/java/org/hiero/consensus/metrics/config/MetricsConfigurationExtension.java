// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.metrics.config;

import com.google.auto.service.AutoService;
import com.swirlds.config.api.ConfigurationExtension;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Set;
import org.hiero.consensus.metrics.platform.prometheus.PrometheusConfig;

/**
 * Registers configuration types for the metrics module.
 */
@AutoService(ConfigurationExtension.class)
public class MetricsConfigurationExtension implements ConfigurationExtension {

    /**
     * {@inheritDoc}
     */
    @NonNull
    public Set<Class<? extends Record>> getConfigDataTypes() {
        return Set.of(MetricsConfig.class, PrometheusConfig.class);
    }
}
