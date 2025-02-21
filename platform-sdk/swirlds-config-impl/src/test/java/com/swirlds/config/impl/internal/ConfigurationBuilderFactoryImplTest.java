// SPDX-License-Identifier: Apache-2.0
package com.swirlds.config.impl.internal;

import com.swirlds.common.config.BasicCommonConfig;
import com.swirlds.common.config.StateCommonConfig;
import com.swirlds.config.api.Configuration;
import com.swirlds.config.api.ConfigurationBuilder;
import com.swirlds.config.api.spi.ConfigurationBuilderFactory;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class ConfigurationBuilderFactoryImplTest {

    @Test
    public void testNotSameResults() {
        // given
        final ConfigurationBuilderFactory factory = new ConfigurationBuilderFactoryImpl();

        // when
        final ConfigurationBuilder configurationBuilder1 = factory.create();
        final ConfigurationBuilder configurationBuilder2 = factory.create();

        // then
        Assertions.assertNotNull(configurationBuilder1);
        Assertions.assertNotNull(configurationBuilder2);
        Assertions.assertNotSame(configurationBuilder1, configurationBuilder2);
    }

    @Test
    void testDefaultBehaviorExtensionScanning() {
        // given
        final ConfigurationBuilder configurationBuilder =
                ConfigurationBuilder.create().autoDiscoverExtensions();
        final Configuration configuration = configurationBuilder.build();

        // then
        Assertions.assertFalse(configuration.getConfigDataTypes().isEmpty());
        Assertions.assertTrue(configuration.getConfigDataTypes().contains(BasicCommonConfig.class));
        Assertions.assertTrue(configuration.getConfigDataTypes().contains(StateCommonConfig.class));
    }

    @Test
    void testEmptyBuilderWithoutDiscovery() {
        // given
        final ConfigurationBuilder configurationBuilder = ConfigurationBuilder.create();

        // when
        final Configuration configuration = configurationBuilder.build();

        // then
        Assertions.assertTrue(configuration.getConfigDataTypes().isEmpty());
    }
}
