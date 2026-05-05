// SPDX-License-Identifier: Apache-2.0
package com.swirlds.common.io.config;

import com.swirlds.config.api.ConfigData;
import com.swirlds.config.api.ConfigProperty;
import java.nio.file.Path;
import java.time.Duration;
import org.hiero.consensus.config.PathsConfig;

/**
 * Settings for the recycle bin
 *
 * @param dirName          the name of the recycle bin directory, relative to the root path defined in
 *                         {@link PathsConfig#savedStateDir}
 * @param maximumFileAge   the maximum age of a file in the recycle bin before it is deleted
 * @param collectionPeriod the period between recycle bin collection runs
 */
@ConfigData("recycleBin")
public record RecycleBinConfig(
        @ConfigProperty(defaultValue = "swirlds-recycle-bin") Path dirName,
        @ConfigProperty(defaultValue = "7d") Duration maximumFileAge,
        @ConfigProperty(defaultValue = "1d") Duration collectionPeriod) {}
