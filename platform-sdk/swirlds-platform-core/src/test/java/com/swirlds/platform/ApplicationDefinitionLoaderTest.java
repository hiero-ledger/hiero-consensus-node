// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform;

import com.swirlds.config.api.ConfigurationBuilder;
import com.swirlds.platform.config.PathsConfig;
import com.swirlds.platform.config.legacy.ConfigurationException;
import com.swirlds.platform.config.legacy.LegacyConfigProperties;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * FUTURE WORK: More tests can currently not be written since the ApplicationDefinitionLoader internally tries to load jar
 * files from the classpath.
 */
class ApplicationDefinitionLoaderTest {

    @Test
    void testNullParam() {
        final PathsConfig defaultPathsConfig = ConfigurationBuilder.create()
                .withConfigDataType(PathsConfig.class)
                .build()
                .getConfigData(PathsConfig.class);

        Assertions.assertThrows(
                NullPointerException.class, () -> ApplicationDefinitionLoader.load(defaultPathsConfig, null));
    }

    @Test
    void testEmptyParams() {
        // given
        final LegacyConfigProperties configProperties = new LegacyConfigProperties();

        final PathsConfig defaultPathsConfig = ConfigurationBuilder.create()
                .withConfigDataType(PathsConfig.class)
                .build()
                .getConfigData(PathsConfig.class);

        // then
        Assertions.assertThrows(
                ConfigurationException.class,
                () -> ApplicationDefinitionLoader.load(defaultPathsConfig, configProperties),
                "Configuration properties must contain application definition");
    }
}
