// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.config;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.swirlds.config.api.Configuration;
import com.swirlds.config.api.ConfigurationBuilder;
import com.swirlds.config.extensions.sources.SimpleConfigSource;
import com.swirlds.platform.config.internal.ConfigMappings;
import java.time.Duration;
import org.hiero.consensus.gossip.config.GossipConfig;
import org.junit.jupiter.api.Test;

class ConfigMappingsTest {
    @Test
    void testAliases() {
        final Duration valueHangingThreadDuration = Duration.ofSeconds(123);

        final SimpleConfigSource configSource =
                new SimpleConfigSource().withValue("hangingThreadDuration", String.valueOf(valueHangingThreadDuration));
        final Configuration configuration = ConfigurationBuilder.create()
                .withSource(ConfigMappings.addConfigMapping(configSource))
                .withConfigDataType(GossipConfig.class)
                .build();

        final GossipConfig gossipConfig = configuration.getConfigData(GossipConfig.class);
        assertEquals(valueHangingThreadDuration, gossipConfig.hangingThreadDuration());
    }
}
