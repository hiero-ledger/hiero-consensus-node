// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.config;

import com.google.auto.service.AutoService;
import com.swirlds.common.config.StateCommonConfig;
import com.swirlds.common.io.config.FileSystemManagerConfig;
import com.swirlds.common.io.config.TemporaryFileConfig;
import com.swirlds.common.merkle.synchronization.config.ReconnectConfig;
import com.swirlds.common.platform.NodeIdConverter;
import com.swirlds.component.framework.WiringConfig;
import com.swirlds.component.framework.schedulers.builders.TaskSchedulerConfiguration;
import com.swirlds.config.api.ConfigurationExtension;
import com.swirlds.logging.api.internal.configuration.InternalLoggingConfig;
import com.swirlds.merkledb.config.MerkleDbConfig;
import com.swirlds.platform.gossip.ProtocolConfig;
import com.swirlds.platform.gossip.config.GossipConfig;
import com.swirlds.platform.gossip.config.NetworkEndpoint;
import com.swirlds.platform.gossip.config.NetworkEndpointConverter;
import com.swirlds.platform.gossip.sync.config.SyncConfig;
import com.swirlds.platform.health.OSHealthCheckConfig;
import com.swirlds.platform.metrics.PlatformMetricsConfig;
import com.swirlds.platform.network.SocketConfig;
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
@AutoService(ConfigurationExtension.class)
public class PlatformConfigurationExtension implements ConfigurationExtension {

    /**
     * {@inheritDoc}
     */
    @NonNull
    public Set<Class<? extends Record>> getConfigDataTypes() {

        // Please keep lists in this method alphabetized (enforced by unit test).

        // Load Configuration Definitions
        return Set.of(
                AddressBookConfig.class,
                BasicConfig.class,
                MerkleDbConfig.class,
                OSHealthCheckConfig.class,
                PathsConfig.class,
                PlatformMetricsConfig.class,
                PlatformSchedulersConfig.class,
                PlatformStatusConfig.class,
                ProtocolConfig.class,
                ReconnectConfig.class,
                SocketConfig.class,
                StateCommonConfig.class,
                StateConfig.class,
                SyncConfig.class,
                TemporaryFileConfig.class,
                FileSystemManagerConfig.class,
                ThreadConfig.class,
                UptimeConfig.class,
                VirtualMapConfig.class,
                WiringConfig.class,
                InternalLoggingConfig.class,
                GossipConfig.class);
    }

    @NonNull
    @Override
    public Set<ConverterPair<?>> getConverters() {
        return Set.of(
                new ConverterPair<>(TaskSchedulerConfiguration.class, TaskSchedulerConfiguration::parse),
                new ConverterPair<>(NetworkEndpoint.class, new NetworkEndpointConverter()),
                new ConverterPair<>(NodeId.class, new NodeIdConverter()));
    }
}
