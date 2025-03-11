// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.config;

import static com.swirlds.common.io.utility.FileUtils.getAbsolutePath;
import static com.swirlds.common.io.utility.FileUtils.rethrowIO;

import com.swirlds.config.api.ConfigData;
import com.swirlds.config.api.ConfigProperty;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.nio.file.Path;

/**
 * Configurations related to paths.
 *
 * @param settingsUsedDir          the directory where the settings used file will be created on startup if and only if
 *                                 settings.txt exists
 * @param keysDirPath              path to data/keys/
 * @param appsDirPath              path to data/apps/
 * @param logPath                  path to log4j2.xml (which might not exist)
 * @param markerFilesDir           path to the directory where marker files are written.
 * @param writePlatformMarkerFiles whether to write marker files or not
 */
@ConfigData("paths")
public record PathsConfig(
        @ConfigProperty(defaultValue = ".") String settingsUsedDir,
        @ConfigProperty(defaultValue = "data/keys") String keysDirPath,
        @ConfigProperty(defaultValue = "data/apps") String appsDirPath,
        @ConfigProperty(defaultValue = "log4j2.xml") String logPath,
        @ConfigProperty(defaultValue = "data/saved/marker_files") String markerFilesDir,
        @ConfigProperty(defaultValue = "false") boolean writePlatformMarkerFiles) {

    /**
     * the directory where the settings used file will be created on startup if and only if settings.txt exists
     *
     * @return absolute path to settings directory
     */
    public Path getSettingsUsedDir() {
        return getAbsolutePath(settingsUsedDir);
    }

    /**
     * path to data/keys/
     *
     * @return absolute path to data/keys/
     */
    public Path getKeysDirPath() {
        return getAbsolutePath(keysDirPath);
    }

    /**
     * path to data/apps/
     *
     * @return absolute path to data/apps/
     */
    public Path getAppsDirPath() {
        return getAbsolutePath(appsDirPath);
    }

    /**
     * path to log4j2.xml (which might not exist)
     *
     * @return absolute path to log4j2.xml
     */
    public Path getLogPath() {
        return rethrowIO(() -> getAbsolutePath(logPath));
    }

    /**
     * path to the directory where marker files are written (which might not exist).  Null is returned when the string
     * is empty or set to "/dev/null" or when the feature for writing marker files is disabled.
     *
     * @return the absolute path to the directory where marker files are written or null if the path is empty or
     * "/dev/null" or disabled.
     */
    @Nullable
    public Path getMarkerFilesDir() {
        if (!writePlatformMarkerFiles || markerFilesDir.isEmpty() || markerFilesDir.equals("/dev/null")) {
            return null;
        }
        return getAbsolutePath(markerFilesDir);
    }
}
