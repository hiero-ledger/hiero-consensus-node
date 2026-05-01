// SPDX-License-Identifier: Apache-2.0
package com.swirlds.common.io.config;

import com.swirlds.common.io.filesystem.FileSystemManager;
import com.swirlds.config.api.ConfigData;
import com.swirlds.config.api.ConfigProperty;

/**
 * Settings for {@link FileSystemManager}
 *
 * @param rootPath The directory where temporary files are created.
 * @param tmpDir the directory where temporary files are created, relative to rootPath
 */
@ConfigData("fileSystem")
public record FileSystemConfig(
        @ConfigProperty(defaultValue = "data/saved") String rootPath,
        @ConfigProperty(defaultValue = "swirlds-tmp") String tmpDir) {}
