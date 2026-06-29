// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.swirlds.config.api.Configuration;
import com.swirlds.config.api.ConfigurationBuilder;
import com.swirlds.config.extensions.test.fixtures.ConfigUtils;
import java.util.Set;
import org.junit.jupiter.api.Test;

class PlatformConfigurationExtensionTests {

    @Test
    void testIfAllConfigDataTypesAreRegistered() {
        // given
        final var allRecordsFound = ConfigUtils.loadAllConfigDataRecords(Set.of("com.swirlds"));
        final Configuration config =
                ConfigurationBuilder.create().autoDiscoverExtensions().build();

        for (Class<? extends Record> record : allRecordsFound) {
            // when
            final var configData = config.getConfigData(record);

            // then
            assertThat(configData)
                    .as("Config data for " + record.getName() + " should be registered.")
                    .isNotNull();
        }
    }
}
