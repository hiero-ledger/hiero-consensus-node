// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.config;

import com.swirlds.config.api.ConfigurationBuilder;
import com.swirlds.config.api.source.ConfigSource;
import com.swirlds.config.extensions.sources.LegacyFileConfigSource;
import com.swirlds.config.extensions.sources.YamlConfigSource;
import com.swirlds.platform.config.internal.ConfigMappings;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.io.IOException;
import java.nio.file.Path;

/**
 * A utility class for setting up the configuration including consensus-layer sources and mappings.
 */
public class ConfigurationSetupUtils {

    private ConfigurationSetupUtils() {}

    /**
     * Load the configuration for the platform without overrides.
     *
     * @param configurationBuilder the configuration builder to setup
     * @param settingsPath         the path to the settings.txt file
     * @throws IOException if there is a problem reading the configuration files
     */
    public static void setupConfigBuilder(
            @NonNull final ConfigurationBuilder configurationBuilder, @NonNull final Path settingsPath)
            throws IOException {
        setupConfigBuilder(configurationBuilder, settingsPath, null);
    }

    /**
     * Load the configuration for the platform.
     *
     * @param configurationBuilder the configuration builder to setup
     * @param settingsPath         the path to the settings.txt file
     * @param nodeOverridesPath    the path to the node-overrides.yaml file
     * @throws IOException if there is a problem reading the configuration files
     */
    public static void setupConfigBuilder(
            @NonNull final ConfigurationBuilder configurationBuilder,
            @NonNull final Path settingsPath,
            @Nullable final Path nodeOverridesPath)
            throws IOException {

        final ConfigSource settingsConfigSource = LegacyFileConfigSource.ofSettingsFile(settingsPath);
        final ConfigSource mappedSettingsConfigSource = ConfigMappings.addConfigMapping(settingsConfigSource);
        configurationBuilder.autoDiscoverExtensions().withSource(mappedSettingsConfigSource);

        if (nodeOverridesPath != null) {
            final ConfigSource yamlConfigSource = new YamlConfigSource(nodeOverridesPath);
            configurationBuilder.withSource(yamlConfigSource);
        }
    }
}
