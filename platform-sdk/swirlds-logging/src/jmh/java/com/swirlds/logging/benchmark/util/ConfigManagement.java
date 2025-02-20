// SPDX-License-Identifier: Apache-2.0
package com.swirlds.logging.benchmark.util;

import com.swirlds.config.api.Configuration;
import com.swirlds.config.api.ConfigurationBuilder;
import com.swirlds.config.extensions.sources.SystemEnvironmentConfigSource;
import com.swirlds.logging.benchmark.config.Constants;
import edu.umd.cs.findbugs.annotations.NonNull;

/**
 * Utility class for configuring benchmark handling of
 * <li>timestamp formatting
 * <li>outputFile deletion
 */
public class ConfigManagement {

    public static final Configuration CONFIGURATION = ConfigurationBuilder.create()
            .withSources(SystemEnvironmentConfigSource.getInstance())
            .build();

    private ConfigManagement() {}

    /**
     * Reads the value from ENABLE_TIME_FORMATTING system variable or returns {@link Constants#ENABLE_TIME_FORMATTING}
     */
    public static boolean formatTimestamp() {
        return getEnvOrElse(Constants.ENABLE_TIME_FORMATTING_ENV, Constants.ENABLE_TIME_FORMATTING);
    }

    /**
     * Reads the value from DELETE_OUTPUT_FOLDER system variable or returns {@link Constants#DELETE_OUTPUT_FOLDER}
     */
    public static boolean deleteOutputFolder() {
        return getEnvOrElse(Constants.DELETE_OUTPUT_FOLDER_ENV, Constants.DELETE_OUTPUT_FOLDER);
    }

    private static boolean getEnvOrElse(final @NonNull String deleteOutputFilesEnv, final boolean deleteOutputFiles) {
        return Boolean.TRUE.equals(CONFIGURATION.getValue(deleteOutputFilesEnv, Boolean.class, deleteOutputFiles));
    }
}
