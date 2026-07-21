// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus;

import static com.swirlds.logging.legacy.LogMarker.EXCEPTION;
import static com.swirlds.logging.legacy.LogMarker.STARTUP;
import static com.swirlds.platform.builder.PlatformBuildConstants.DEFAULT_SETTINGS_FILE_NAME;
import static java.util.Objects.requireNonNull;
import static org.hiero.base.file.FileUtils.getAbsolutePath;

import com.swirlds.config.api.Configuration;
import com.swirlds.config.extensions.export.ConfigExport;
import com.swirlds.platform.JVMPauseDetectorThread;
import com.swirlds.platform.config.internal.PlatformConfigUtils;
import com.swirlds.platform.health.OSHealthCheckConfig;
import com.swirlds.platform.health.OSHealthChecker;
import com.swirlds.platform.health.clock.OSClockSpeedSourceChecker;
import com.swirlds.platform.health.entropy.OSEntropyChecker;
import com.swirlds.platform.health.filesystem.OSFileSystemChecker;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hiero.consensus.config.BasicConfig;
import org.hiero.consensus.config.PathsConfig;

/**
 * Performs setup that should be done only once per JVM.
 */
public final class ConsensusLayerStaticSetup {

    /**
     * The logger for this class
     */
    private static final Logger logger = LogManager.getLogger(ConsensusLayerStaticSetup.class);

    /**
     * The path to the settings file (i.e. the file with the optional settings).
     */
    private static final Path DEFAULT_SETTINGS_PATH = getAbsolutePath(DEFAULT_SETTINGS_FILE_NAME);

    private static boolean staticSetupCompleted = false;

    private ConsensusLayerStaticSetup() {}

    /**
     * Setup static setup of the consensus layer. If running multiple platforms in the same JVM and this method is
     * called more than once then this method becomes a no-op.
     *
     * @param configuration the configuration for this node
     */
    public static void setup(@NonNull final Configuration configuration) {
        if (staticSetupCompleted) {
            // Only setup static utilities once
            return;
        }
        staticSetupCompleted = true;

        performHealthChecks(DEFAULT_SETTINGS_PATH, configuration);
        writeSettingsUsed(configuration);

        // Initialize JVMPauseDetectorThread, if enabled via settings
        startJVMPauseDetectorThread(configuration);
    }

    /**
     * Perform health all health checks
     *
     * @param settingsPath  the path to the settings.txt file
     * @param configuration the configuration
     */
    private static void performHealthChecks(
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
     * Instantiate and start the JVMPauseDetectorThread, if enabled via the
     * {@link BasicConfig#jvmPauseDetectorSleepMs()} setting.
     *
     * @param configuration the configuration object
     */
    private static void startJVMPauseDetectorThread(@NonNull final Configuration configuration) {
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
    private static void writeSettingsUsed(@NonNull final Configuration configuration) {
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
