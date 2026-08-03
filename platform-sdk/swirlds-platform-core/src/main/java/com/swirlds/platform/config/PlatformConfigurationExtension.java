// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.config;

import com.swirlds.common.platform.NodeIdConverter;
import com.swirlds.component.framework.WiringConfig;
import com.swirlds.component.framework.schedulers.builders.TaskSchedulerConfiguration;
import com.swirlds.config.api.ConfigurationExtension;
import com.swirlds.logging.api.internal.configuration.InternalLoggingConfig;
import com.swirlds.platform.builder.ModulesConfig;
import com.swirlds.platform.health.OSHealthCheckConfig;
import com.swirlds.platform.metrics.PlatformMetricsConfig;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Set;
import org.hiero.consensus.BasicConfig;
import org.hiero.consensus.FallenBehindConfig;
import org.hiero.consensus.PathsConfig;
import org.hiero.consensus.model.node.NodeId;

/**
 * Registers configuration types for the platform.
 */
public class PlatformConfigurationExtension implements ConfigurationExtension {

    /**
     * {@inheritDoc}
     */
    @NonNull
    public Set<Class<? extends Record>> getConfigDataTypes() {

        // Please keep lists in this method alphabetized (enforced by unit test).

        // Load Configuration Definitions
        return Set.of(
                BasicConfig.class,
                PathsConfig.class,
                ModulesConfig.class,
                FallenBehindConfig.class,
                OSHealthCheckConfig.class,
                PlatformMetricsConfig.class,
                WiringConfig.class,
                InternalLoggingConfig.class);
    }

    @NonNull
    @Override
    public Set<ConverterPair<?>> getConverters() {
        return Set.of(
                new ConverterPair<>(TaskSchedulerConfiguration.class, TaskSchedulerConfiguration::parse),
                new ConverterPair<>(NodeId.class, new NodeIdConverter()));
    }
}
