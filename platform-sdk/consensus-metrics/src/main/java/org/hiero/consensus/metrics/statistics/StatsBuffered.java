// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.metrics.statistics;

import com.swirlds.metrics.api.Metrics;
import org.hiero.consensus.metrics.statistics.internal.StatsBuffer;

/**
 * A statistic such as StatsSpeedometer or StatsRunningAverage should implement this if it will
 * contain a
 * {@link StatsBuffer} for recent history and another for all of history. The user can then retrieve them from
 * {@link Metrics}.
 */
public interface StatsBuffered {
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
