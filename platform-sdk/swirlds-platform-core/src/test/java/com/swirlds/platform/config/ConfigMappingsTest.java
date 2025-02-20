// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.config;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.swirlds.config.api.Configuration;
import com.swirlds.config.api.ConfigurationBuilder;
import com.swirlds.config.extensions.sources.SimpleConfigSource;
import com.swirlds.platform.config.internal.ConfigMappings;
import com.swirlds.platform.consensus.ConsensusConfig;
import org.junit.jupiter.api.Test;

class ConfigMappingsTest {
    @Test
    void testAliases() {
        final int valueNonAncient = 10;
        final int valueExpired = 20;
        final int valueCoin = 30;

        final SimpleConfigSource configSource = new SimpleConfigSource()
                .withValue("state.roundsNonAncient", String.valueOf(valueNonAncient))
                .withValue("state.roundsExpired", String.valueOf(valueExpired))
                .withValue("coinFreq", String.valueOf(valueCoin));
        final Configuration configuration = ConfigurationBuilder.create()
                .withSource(ConfigMappings.addConfigMapping(configSource))
                .withConfigDataType(ConsensusConfig.class)
                .build();

        final ConsensusConfig consensusConfig = configuration.getConfigData(ConsensusConfig.class);
        assertEquals(valueNonAncient, consensusConfig.roundsNonAncient());
        assertEquals(valueExpired, consensusConfig.roundsExpired());
        assertEquals(valueCoin, consensusConfig.coinFreq());
    }
}
