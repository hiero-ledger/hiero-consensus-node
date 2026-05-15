// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.config;

import static org.hiero.base.file.FileUtils.getAbsolutePath;

import com.swirlds.config.api.ConfigData;
import com.swirlds.config.api.ConfigProperty;
import java.nio.file.Path;

/**
 * Configurations related to paths.
 *
 * @param settingsUsedDir          the directory where the settings used file will be created on startup if and only if
 *                                 settings.txt exists
 * @param keysDirPath              path to the keys directory
 * @param savedStateDir            path to where saved states and other state related files are stored
 * @param tmpDir                   path to the directory where temporary files are created, relative to {@code savedStateDir}
 */
@ConfigData("paths")
public record PathsConfig(
        @ConfigProperty(defaultValue = ".") Path settingsUsedDir,
        @ConfigProperty(defaultValue = "data/keys") Path keysDirPath,
        @ConfigProperty(defaultValue = "data/saved") Path savedStateDir,
        @ConfigProperty(defaultValue = "swirlds-tmp") Path tmpDir) {

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
}
