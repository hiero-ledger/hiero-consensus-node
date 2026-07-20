// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.swirlds.component.framework.WiringConfig;
import com.swirlds.config.api.Configuration;
import com.swirlds.config.api.ConfigurationBuilder;
import com.swirlds.config.extensions.test.fixtures.ConfigUtils;
import com.swirlds.logging.api.internal.configuration.InternalLoggingConfig;
import com.swirlds.platform.builder.ModulesConfig;
import com.swirlds.platform.health.OSHealthCheckConfig;
import com.swirlds.platform.metrics.PlatformMetricsConfig;
import com.swirlds.platform.uptime.UptimeConfig;
import com.swirlds.platform.wiring.PlatformSchedulersConfig;
import java.util.Arrays;
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

    @Test
    void testConfigTypes() {
        PlatformConfigurationExtension extension = new PlatformConfigurationExtension();

        assertThat(extension.getConfigDataTypes())
                .containsExactlyInAnyOrderElementsOf(Arrays.asList(
                        ModulesConfig.class,
                        OSHealthCheckConfig.class,
                        PlatformMetricsConfig.class,
                        PlatformSchedulersConfig.class,
                        UptimeConfig.class,
                        WiringConfig.class,
                        InternalLoggingConfig.class));
    }
}
