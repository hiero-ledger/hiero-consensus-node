// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.builder;

/**
 * Constants used in the platform build process.
 */
public final class PlatformBuildConstants {

    /**
     * The name of the log4j configuration file.
     */
    public static final String LOG4J_FILE_NAME = "log4j2.xml";

    /**
     * The name of the default configuration file (i.e. where the initial address book can be specified).
     */
    public static final String DEFAULT_CONFIG_FILE_NAME = "config.txt";

    /**
     * The name of the default settings file (i.e. where configuration comes from... we know this is confusing, API is a
     * work in progress).
     */
    public static final String DEFAULT_SETTINGS_FILE_NAME = "settings.txt";

    /**
     * The name of the default overrides file (i.e. where interface or host overrides can be specified).
     */
    public static final String DEFAULT_OVERRIDES_YAML_FILE_NAME = "data/config/node-overrides.yaml";

    private PlatformBuildConstants() {}
}
