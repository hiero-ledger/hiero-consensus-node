// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.config;

import static com.swirlds.common.io.utility.FileUtils.getAbsolutePath;

import com.swirlds.config.api.Configuration;
import com.swirlds.config.api.ConfigurationBuilder;
import com.swirlds.config.api.source.ConfigSource;
import com.swirlds.config.extensions.sources.LegacyFileConfigSource;
import com.swirlds.logging.legacy.LogMarker;
import com.swirlds.platform.config.internal.ConfigMappings;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * A utility class for building a basic configuration with the default configuration sources and paths.
 * <p>
 * Can be used in cli tools to build a basic configuration.
 */
public class DefaultConfiguration {
    private static final Logger logger = LogManager.getLogger(DefaultConfiguration.class);

    private DefaultConfiguration() {
        // Avoid instantiation for utility class
    }

    /**
     * Build a basic configuration with the default configuration sources and paths. Reads configuration form
     * "settings.txt".
     *
     * @param configurationBuilder the configuration builder that will be used to build the configuration
     * @return the configuration object
     * @throws IOException if there is an error reading the configuration files
     */
    @NonNull
    public static Configuration buildBasicConfiguration(@NonNull final ConfigurationBuilder configurationBuilder)
            throws IOException {
        return buildBasicConfiguration(configurationBuilder, getAbsolutePath("settings.txt"), Collections.emptyList());
    }

    /**
     * Build a basic configuration with the default configuration sources and paths.
     *
     * @param configurationBuilder the configuration builder that will be used to build the configuration
     * @param settingsPath         the path to the settings.txt file
     * @return the configuration object
     * @throws IOException if there is an error reading the configuration files
     */
    @NonNull
    public static Configuration buildBasicConfiguration(
            @NonNull final ConfigurationBuilder configurationBuilder, @NonNull final Path settingsPath)
            throws IOException {
        return buildBasicConfiguration(configurationBuilder, settingsPath, Collections.emptyList());
    }

    /**
     * Build a basic configuration with the default configuration sources.
     *
     * @param configurationBuilder the configuration builder that will be used to build the configuration
     * @param settingsPath         the path to the settings.txt file
     * @param configurationPaths   additional paths to configuration files
     * @return the configuration object
     * @throws IOException if there is an error reading the configuration files
     */
    @NonNull
    public static Configuration buildBasicConfiguration(
            @NonNull final ConfigurationBuilder configurationBuilder,
            @NonNull final Path settingsPath,
            @NonNull final List<Path> configurationPaths)
            throws IOException {
        final ConfigSource settingsConfigSource = LegacyFileConfigSource.ofSettingsFile(settingsPath);
        final ConfigSource mappedSettingsConfigSource = ConfigMappings.addConfigMapping(settingsConfigSource);

        configurationBuilder.autoDiscoverExtensions().withSource(mappedSettingsConfigSource);

        for (final Path configurationPath : configurationPaths) {
            logger.info(LogMarker.CONFIG.getMarker(), "Loading configuration from {}", configurationPath);
            configurationBuilder.withSource(new LegacyFileConfigSource(configurationPath));
        }

        return configurationBuilder.build();
    }
}
