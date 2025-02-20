// SPDX-License-Identifier: Apache-2.0
package com.swirlds.common.io.config;

import com.swirlds.common.config.StateCommonConfig;
import com.swirlds.config.api.ConfigData;
import com.swirlds.config.api.ConfigProperty;

/**
 * Settings for temporary files
 *
 * @param temporaryFilePath
 * 		The directory where temporary files are created.
 */
@ConfigData("temporaryFiles")
public record TemporaryFileConfig(@ConfigProperty(defaultValue = "swirlds-tmp") String temporaryFilePath) {

    /**
     * Returns the real path to the temporary files that depends on the {@link StateCommonConfig#savedStateDirectory()}
     * property.
     *
     * @param stateConfig
     * 		the state config object
     * @return the location where temporary files are stored
     */
    public String getTemporaryFilePath(final StateCommonConfig stateConfig) {
        return stateConfig.savedStateDirectory().resolve(temporaryFilePath()).toString();
    }
}
