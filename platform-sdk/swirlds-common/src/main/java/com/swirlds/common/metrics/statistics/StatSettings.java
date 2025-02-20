// SPDX-License-Identifier: Apache-2.0
package com.swirlds.common.metrics.statistics;

/**
 * Settings for statistics.
 */
public interface StatSettings {

    /**
     * number of bins to store for the history (in StatsBuffer etc.)
     */
    int getBufferSize();

    /**
     * number of seconds covered by "recent" history (in StatsBuffer etc.)
     */
    double getRecentSeconds();

    /**
     * number of seconds that the "all" history window skips at the start
     */
    double getSkipSeconds();
}
