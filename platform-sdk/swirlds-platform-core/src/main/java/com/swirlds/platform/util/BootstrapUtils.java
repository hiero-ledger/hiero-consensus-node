// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.util;

import static com.swirlds.logging.legacy.LogMarker.EXCEPTION;
import static com.swirlds.logging.legacy.LogMarker.STARTUP;
import static java.util.Objects.requireNonNull;

import com.swirlds.config.api.Configuration;
import com.swirlds.config.api.ConfigurationBuilder;
import com.swirlds.config.api.source.ConfigSource;
import com.swirlds.config.extensions.export.ConfigExport;
import com.swirlds.config.extensions.sources.LegacyFileConfigSource;
import com.swirlds.config.extensions.sources.YamlConfigSource;
import com.swirlds.platform.JVMPauseDetectorThread;
import com.swirlds.platform.config.PathsConfig;
import com.swirlds.platform.config.internal.ConfigMappings;
import com.swirlds.platform.config.internal.PlatformConfigUtils;
import com.swirlds.platform.health.OSHealthCheckConfig;
import com.swirlds.platform.health.OSHealthChecker;
import com.swirlds.platform.health.clock.OSClockSpeedSourceChecker;
import com.swirlds.platform.health.entropy.OSEntropyChecker;
import com.swirlds.platform.health.filesystem.OSFileSystemChecker;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hiero.base.constructable.ConstructableRegistry;
import org.hiero.base.constructable.ConstructableRegistryException;
import org.hiero.consensus.config.BasicConfig;

/**
 * Utility methods that are helpful when starting up a JVM.
 */
public final class BootstrapUtils {

    /**
     * The logger for this class
     */
    private static final Logger logger = LogManager.getLogger(BootstrapUtils.class);

    private BootstrapUtils() {}

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

    /**
     * Perform health all health checks
     *
     * @param settingsPath  the path to the settings.txt file
     * @param configuration the configuration
     */
    public static void performHealthChecks(
            @NonNull final Path settingsPath, @NonNull final Configuration configuration) {
        requireNonNull(configuration);
        final OSFileSystemChecker osFileSystemChecker = new OSFileSystemChecker(settingsPath);

        OSHealthChecker.performOSHealthChecks(
                configuration.getConfigData(OSHealthCheckConfig.class),
                List.of(
                        OSClockSpeedSourceChecker::performClockSourceSpeedCheck,
                        OSEntropyChecker::performEntropyChecks,
                        osFileSystemChecker::performFileSystemCheck));
    }

    /**
     * Add all classes to the constructable registry.
     */
    public static void setupConstructableRegistry() {
        try {
            ConstructableRegistry.getInstance().registerConstructables("");
        } catch (final ConstructableRegistryException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Instantiate and start the JVMPauseDetectorThread, if enabled via the
     * {@link BasicConfig#jvmPauseDetectorSleepMs()} setting.
     *
     * @param configuration the configuration object
     */
    public static void startJVMPauseDetectorThread(@NonNull final Configuration configuration) {
        requireNonNull(configuration);

        final BasicConfig basicConfig = configuration.getConfigData(BasicConfig.class);
        if (basicConfig.jvmPauseDetectorSleepMs() > 0) {
            final JVMPauseDetectorThread jvmPauseDetectorThread = new JVMPauseDetectorThread(
                    (pauseTimeMs, allocTimeMs) -> {
                        if (pauseTimeMs > basicConfig.jvmPauseReportMs()) {
                            logger.warn(
                                    EXCEPTION.getMarker(),
                                    "jvmPauseDetectorThread detected JVM paused for {} ms, allocation pause {} ms",
                                    pauseTimeMs,
                                    allocTimeMs);
                        }
                    },
                    basicConfig.jvmPauseDetectorSleepMs());
            jvmPauseDetectorThread.start();
            logger.debug(STARTUP.getMarker(), "jvmPauseDetectorThread started");
        }
    }

    /**
     * Writes all settings and config values to settingsUsed.txt
     *
     * @param configuration the configuration values to write
     */
    public static void writeSettingsUsed(@NonNull final Configuration configuration) {
        requireNonNull(configuration);
        final StringBuilder settingsUsedBuilder = new StringBuilder();

        // Add all settings values to the string builder
        final PathsConfig pathsConfig = configuration.getConfigData(PathsConfig.class);

        settingsUsedBuilder.append(System.lineSeparator());
        settingsUsedBuilder.append("------------- All Configuration -------------");
        settingsUsedBuilder.append(System.lineSeparator());

        // Add all config values to the string builder
        ConfigExport.addConfigContents(configuration, settingsUsedBuilder);

        // Write the settingsUsed.txt file
        final Path settingsUsedPath =
                pathsConfig.getSettingsUsedDir().resolve(PlatformConfigUtils.SETTING_USED_FILENAME);
        try (final OutputStream outputStream = new FileOutputStream(settingsUsedPath.toFile())) {
            outputStream.write(settingsUsedBuilder.toString().getBytes(StandardCharsets.UTF_8));
        } catch (final IOException | RuntimeException e) {
            logger.error(EXCEPTION.getMarker(), "Failed to write settingsUsed to file {}", settingsUsedPath, e);
        }
    }
}
