// SPDX-License-Identifier: Apache-2.0
package com.swirlds.config.processor;

/**
 * Constants used by the config processor.
 */
public final class ConfigProcessorConstants {

    /**
     * The extension for java files.
     */
    public static final String JAVA_FILE_EXTENSION = ".java";

    /**
     * Suffix for the generated classes
     */
    public static final String CONSTANTS_CLASS_SUFFIX = "_";

    /**
     * Class name of the ConfigData annotation.
     */
    public static final String CONFIG_DATA_ANNOTATION = "com.swirlds.config.api.ConfigData";

    /**
     * private constructor for utility class.
     */
    private ConfigProcessorConstants() {}
}
