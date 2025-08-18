// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.util;

import com.swirlds.common.context.PlatformContext;
import com.swirlds.common.utility.throttle.RateLimitedLogger;
import com.swirlds.logging.legacy.LogMarker;
import com.swirlds.platform.config.PathsConfig;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Writes marker files with the given filename to disk in the configured directory.  If there is no configured
 * directory, no marker files are written.  The marker files are written at most once until the program restarts.
 */
public class MarkerFileWriter {

    /**
     * The logger for this class.
     */
    private static final Logger logger = LogManager.getLogger(MarkerFileWriter.class);

    /**
     * Rate limited logger for failure to write marker files.
     */
    private final RateLimitedLogger failedToWriteMarkerFileLogger;

    /**
     * The directory where the marker files are written.  If null, no marker files are written.
     */
    private final Path markerFileDirectory;

    /**
     * A map of marker file names to whether or not the file has been written.  This is used to ensure that each marker
     * file is written at most once until the program restarts.
     */
    private final Map<String, Boolean> markerFilesWritten = new ConcurrentHashMap<>();

    /**
     * Creates a new {@link MarkerFileWriter} with the given {@link PlatformContext}.  If the marker file writer is
     * enabled, files are written to the configured directory.
     *
     * @param platformContext the platform context containing configuration.
     */
    public MarkerFileWriter(@NonNull final PlatformContext platformContext) {
        final boolean enabled = platformContext
                .getConfiguration()
                .getConfigData(PathsConfig.class)
                .writePlatformMarkerFiles();
        final Path markerFileDirectoryPath = platformContext
                .getConfiguration()
                .getConfigData(PathsConfig.class)
                .getMarkerFilesDir();
        failedToWriteMarkerFileLogger = new RateLimitedLogger(logger, platformContext.getTime(), Duration.ofMinutes(1));
        Path directory = null;
        if (enabled) {
            if (markerFileDirectoryPath != null) {
                try {
                    Files.createDirectories(markerFileDirectoryPath);
                    directory = markerFileDirectoryPath;
                } catch (final IOException e) {
                    logger.error(
                            LogMarker.EXCEPTION.getMarker(),
                            "Failed to create marker file directory: {}",
                            markerFileDirectoryPath,
                            e);
                }
            } else {
                logger.error(LogMarker.STARTUP.getMarker(), "Marker file writing is turned on but directory is null.");
            }
        }
        markerFileDirectory = directory;
    }

    /**
     * Writes a marker file with the given filename to the configured directory.
     *
     * @param filename the name of the marker file to write.
     */
    public void writeMarkerFile(@NonNull final String filename) {
        if (markerFileDirectory == null) {
            // Configuration did not set a marker file directory.  No need to write marker file.
            return;
        }
        markerFilesWritten.computeIfAbsent(filename, key -> {
            final Path markerFile = markerFileDirectory.resolve(filename);
            try {
                if (Files.exists(markerFile)) {
                    // Log at a warn level because there may be cases, like with coin rounds,
                    // where there is already the same marker file on disk.
                    failedToWriteMarkerFileLogger.warn(
                            LogMarker.ERROR.getMarker(), "Marker file already exists: {}", markerFile);
                    return true;
                }
                Files.createFile(markerFile);
            } catch (final IOException e) {
                failedToWriteMarkerFileLogger.error(
                        LogMarker.EXCEPTION.getMarker(), "Failed to create marker file: {}", markerFile, e);
            }
            return true;
        });
    }

    /**
     * Returns the directory where the marker files are written.  May be null.
     *
     * @return the directory where the marker files are written.  This may be null.
     */
    @Nullable
    public Path getMarkerFileDirectory() {
        return markerFileDirectory;
    }
}
