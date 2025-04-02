// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.config;

import com.google.auto.service.AutoService;
import com.swirlds.common.config.BasicCommonConfig;
import com.swirlds.common.config.StateCommonConfig;
import com.swirlds.common.crypto.config.CryptoConfig;
import com.swirlds.common.io.config.FileSystemManagerConfig;
import com.swirlds.common.io.config.TemporaryFileConfig;
import com.swirlds.common.merkle.synchronization.config.ReconnectConfig;
import com.swirlds.common.metrics.config.MetricsConfig;
import com.swirlds.common.metrics.platform.prometheus.PrometheusConfig;
import com.swirlds.common.platform.NodeIdConverter;
import com.swirlds.component.framework.WiringConfig;
import com.swirlds.component.framework.schedulers.builders.TaskSchedulerConfiguration;
import com.swirlds.config.api.ConfigurationExtension;
import com.swirlds.logging.api.internal.configuration.InternalLoggingConfig;
import com.swirlds.merkledb.config.MerkleDbConfig;
import com.swirlds.platform.consensus.ConsensusConfig;
import com.swirlds.platform.event.preconsensus.PcesConfig;
import com.swirlds.platform.eventhandling.EventConfig;
import com.swirlds.platform.gossip.ProtocolConfig;
import com.swirlds.platform.gossip.config.GossipConfig;
import com.swirlds.platform.gossip.config.NetworkEndpoint;
import com.swirlds.platform.gossip.config.NetworkEndpointConverter;
import com.swirlds.platform.gossip.sync.config.SyncConfig;
import com.swirlds.platform.health.OSHealthCheckConfig;
import com.swirlds.platform.network.SocketConfig;
import com.swirlds.platform.system.status.PlatformStatusConfig;
import com.swirlds.platform.uptime.UptimeConfig;
import com.swirlds.platform.wiring.PlatformSchedulersConfig;
import com.swirlds.virtualmap.config.VirtualMapConfig;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Set;
import org.hiero.consensus.event.creator.impl.EventCreationConfig;
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
                BasicCommonConfig.class,
                BasicConfig.class,
                ConsensusConfig.class,
                CryptoConfig.class,
                EventConfig.class,
                EventCreationConfig.class,
                MerkleDbConfig.class,
                MetricsConfig.class,
                OSHealthCheckConfig.class,
                PathsConfig.class,
                PcesConfig.class,
                PlatformSchedulersConfig.class,
                PlatformStatusConfig.class,
                PrometheusConfig.class,
                ProtocolConfig.class,
                ReconnectConfig.class,
                SocketConfig.class,
                StateCommonConfig.class,
                StateConfig.class,
                SyncConfig.class,
                TemporaryFileConfig.class,
                FileSystemManagerConfig.class,
                ThreadConfig.class,
                TransactionConfig.class,
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
