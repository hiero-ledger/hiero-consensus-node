// SPDX-License-Identifier: Apache-2.0
package com.swirlds.common.io.config;

import com.swirlds.config.api.ConfigData;
import com.swirlds.config.api.ConfigProperty;
import java.nio.file.Path;
import org.hiero.base.file.FileSystemManager;

/**
 * Settings for {@link FileSystemManager}
 *
 * @param rootPath The path of the directory where temporary files are created
 * @param tmpDir the name of the directory where temporary files are created, relative to rootPath
 */
@ConfigData("fileSystem")
public record FileSystemConfig(
        @ConfigProperty(defaultValue = "data/saved") Path rootPath,
        @ConfigProperty(defaultValue = "swirlds-tmp") Path tmpDir) {}
