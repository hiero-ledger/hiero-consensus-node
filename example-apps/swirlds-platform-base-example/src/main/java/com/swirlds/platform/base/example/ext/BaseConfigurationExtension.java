// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.base.example.ext;

import com.google.auto.service.AutoService;
import com.swirlds.common.config.BasicCommonConfig;
import com.swirlds.common.metrics.config.MetricsConfig;
import com.swirlds.common.metrics.platform.prometheus.PrometheusConfig;
import com.swirlds.config.api.ConfigurationExtension;
import com.swirlds.platform.base.example.server.BaseExampleRestApiConfig;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Set;

/**
 * Registers configuration types for the platform.
 */
@AutoService(ConfigurationExtension.class)
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
