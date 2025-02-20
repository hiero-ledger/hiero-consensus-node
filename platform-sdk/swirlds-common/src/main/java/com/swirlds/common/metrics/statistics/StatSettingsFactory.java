// SPDX-License-Identifier: Apache-2.0
package com.swirlds.common.metrics.statistics;

/**
 * This object is used to configure general statistics settings.
 */
public final class StatSettingsFactory {

    private static StatSettings settings;

    private StatSettingsFactory() {}

    /**
     * Specify the settings that should be used for the statistics.
     */
    public static synchronized void configure(StatSettings settings) {
        StatSettingsFactory.settings = settings;
    }

    /**
     * Get the settings for statistics.
     */
    public static synchronized StatSettings get() {
        if (settings == null) {
            settings = getDefaultSettings();
        }
        return settings;
    }

    /**
     * Get default statistic settings. Useful for testing.
     */
    private static StatSettings getDefaultSettings() {
        return new StatSettings() {

            @Override
            public int getBufferSize() {
                return 100;
            }

            @Override
            public double getRecentSeconds() {
                return 63;
            }

            @Override
            public double getSkipSeconds() {
                return 60;
            }
        };
    }
}
