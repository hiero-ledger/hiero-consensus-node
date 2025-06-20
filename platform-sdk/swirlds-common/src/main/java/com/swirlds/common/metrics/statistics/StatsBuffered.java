// SPDX-License-Identifier: Apache-2.0
package com.swirlds.common.metrics.statistics;

import com.swirlds.common.metrics.statistics.internal.StatsBuffer;
import com.swirlds.metrics.api.Metrics;

/**
 * A statistic such as StatsSpeedometer or StatsRunningAverage should implement this if it will
 * contain a
 * {@link StatsBuffer} for recent history and another for all of history. The user can then retrieve them from
 * {@link Metrics}.
 */
public interface StatsBuffered {
    /**
     * get the entire history of values of this statistic. The caller should not modify it.
     *
     * @return A {@link StatsBuffer} object keeps all history statistic.
     */
    StatsBuffer getAllHistory();

    /**
     * get the recent history of values of this statistic. The caller should not modify it.
     *
     * @return A {@link StatsBuffer} object keeps recent history of this statistic.
     */
    StatsBuffer getRecentHistory();

    /**
     * reset the statistic, and make it use the given halflife
     *
     * @param halflife
     * 		half of the exponential weighting comes from the last halfLife seconds
     */
    void reset(double halflife);

    /**
     * get average of values per cycle()
     *
     * @return average of values
     */
    double getMean();

    /**
     * get maximum value from all the values of this statistic
     *
     * @return maximum value
     */
    double getMax();

    /**
     * get minimum value from all the values of this statistic
     *
     * @return minimum value
     */
    double getMin();

    /**
     * get standard deviation of this statistic
     *
     * @return standard deviation
     */
    double getStdDev();
}
