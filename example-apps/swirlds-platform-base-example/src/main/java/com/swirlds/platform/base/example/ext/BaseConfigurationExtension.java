// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.base.example.ext;

import com.swirlds.config.api.ConfigurationExtension;
import com.swirlds.platform.base.example.server.BaseExampleRestApiConfig;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Set;
import org.hiero.consensus.concurrent.config.BasicCommonConfig;
import org.hiero.consensus.metrics.config.MetricsConfig;
import org.hiero.consensus.metrics.platform.prometheus.PrometheusConfig;

/**
 * Registers configuration types for the platform.
 */
public class BaseConfigurationExtension implements ConfigurationExtension {

    /**
     * {@inheritDoc}
     */
    @NonNull
    public Set<Class<? extends Record>> getConfigDataTypes() {

        // Load Configuration Definitions
        return Set.of(
                BaseExampleRestApiConfig.class, BasicCommonConfig.class, MetricsConfig.class, PrometheusConfig.class);
    }
}
