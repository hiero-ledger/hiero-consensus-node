// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.config;

import com.swirlds.common.config.StateCommonConfig;
import com.swirlds.common.io.config.FileSystemManagerConfig;
import com.swirlds.common.io.config.TemporaryFileConfig;
import com.swirlds.common.platform.NodeIdConverter;
import com.swirlds.component.framework.WiringConfig;
import com.swirlds.component.framework.schedulers.builders.TaskSchedulerConfiguration;
import com.swirlds.config.api.ConfigurationExtension;
import com.swirlds.logging.api.internal.configuration.InternalLoggingConfig;
import com.swirlds.merkledb.config.MerkleDbConfig;
import com.swirlds.platform.health.OSHealthCheckConfig;
import com.swirlds.platform.metrics.PlatformMetricsConfig;
import com.swirlds.platform.system.status.PlatformStatusConfig;
import com.swirlds.platform.uptime.UptimeConfig;
import com.swirlds.platform.wiring.PlatformSchedulersConfig;
import com.swirlds.virtualmap.config.VirtualMapConfig;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Set;
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
                MerkleDbConfig.class,
                OSHealthCheckConfig.class,
                PathsConfig.class,
                PlatformMetricsConfig.class,
                PlatformSchedulersConfig.class,
                PlatformStatusConfig.class,
                StateCommonConfig.class,
                StateConfig.class,
                TemporaryFileConfig.class,
                FileSystemManagerConfig.class,
                UptimeConfig.class,
                VirtualMapConfig.class,
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
