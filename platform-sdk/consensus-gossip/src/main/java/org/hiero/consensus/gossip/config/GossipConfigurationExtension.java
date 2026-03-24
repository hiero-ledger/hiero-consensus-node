// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.gossip.config;

import com.swirlds.component.framework.schedulers.builders.TaskSchedulerConfiguration;
import com.swirlds.config.api.ConfigurationExtension;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Set;

/**
 * Registers configuration types for the gossip module.
 */
public class GossipConfigurationExtension implements ConfigurationExtension {

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public Set<Class<? extends Record>> getConfigDataTypes() {
        return Set.of(
                GossipConfig.class,
                GossipWiringConfig.class,
                ProtocolConfig.class,
                SocketConfig.class,
                SyncConfig.class,
                BroadcastConfig.class);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public Set<ConverterPair<?>> getConverters() {
        return Set.of(
                new ConverterPair<>(NetworkEndpoint.class, new NetworkEndpointConverter()),
                new ConverterPair<>(TaskSchedulerConfiguration.class, TaskSchedulerConfiguration::parse));
    }
}
