// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.swirlds.config.api.Configuration;
import com.swirlds.config.api.ConfigurationBuilder;
import com.swirlds.config.extensions.test.fixtures.ConfigUtils;
import com.swirlds.logging.api.internal.configuration.InternalLoggingConfig;
import com.swirlds.platform.builder.ModulesConfig;
import com.swirlds.platform.health.OSHealthCheckConfig;
import com.swirlds.platform.metrics.PlatformMetricsConfig;
import java.util.Arrays;
import java.util.Set;
import org.hiero.consensus.BasicConfig;
import org.hiero.consensus.FallenBehindConfig;
import org.hiero.consensus.PathsConfig;
import org.hiero.consensus.wiring.framework.WiringConfig;
import org.junit.jupiter.api.Test;

class PlatformConfigurationExtensionTests {

    @Test
    void testIfAllConfigDataTypesAreRegistered() {
        // given
        final Set<Class<? extends Record>> allRecordsFound =
                ConfigUtils.loadAllConfigDataRecords(Set.of("com.swirlds"));
        final Configuration config =
                ConfigurationBuilder.create().autoDiscoverExtensions().build();

        for (final Class<? extends Record> record : allRecordsFound) {
            // when
            final Object configData = config.getConfigData(record);

            // then
            assertThat(configData)
                    .as("Config data for " + record.getName() + " should be registered.")
                    .isNotNull();
        }
    }

    @Test
    void testConfigTypes() {
        final PlatformConfigurationExtension extension = new PlatformConfigurationExtension();

        assertThat(extension.getConfigDataTypes())
                .containsExactlyInAnyOrderElementsOf(Arrays.asList(
                        BasicConfig.class,
                        PathsConfig.class,
                        ModulesConfig.class,
                        FallenBehindConfig.class,
                        OSHealthCheckConfig.class,
                        PlatformMetricsConfig.class,
                        WiringConfig.class,
                        InternalLoggingConfig.class));
    }
}
