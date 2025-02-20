// SPDX-License-Identifier: Apache-2.0
package com.swirlds.common.config;

import com.swirlds.config.api.Configuration;
import com.swirlds.config.api.ConfigurationBuilder;
import com.swirlds.config.extensions.test.fixtures.TestConfigBuilder;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class BasicCommonConfigTest {

    @Test
    public void testDefaultValuesValid() {
        // given
        final ConfigurationBuilder builder = ConfigurationBuilder.create().withConfigDataType(BasicCommonConfig.class);

        // then
        Assertions.assertDoesNotThrow(builder::build, "All default values of BasicConfig should be valid");
    }

    @Test
    void propertiesHasNoPrefix() {
        // given
        final Configuration configuration = new TestConfigBuilder()
                .withValue(BasicCommonConfig_.SHOW_INTERNAL_STATS, "true")
                .getOrCreateConfig();
        final BasicCommonConfig basicConfig = configuration.getConfigData(BasicCommonConfig.class);

        // then
        Assertions.assertTrue(basicConfig.showInternalStats());
    }
}
