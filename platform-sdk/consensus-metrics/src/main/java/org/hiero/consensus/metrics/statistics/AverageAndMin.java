// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.metrics.statistics;

import com.swirlds.metrics.api.Metrics;

/**
 * A metrics object to track an average and min number without history. This class uses an {@link AtomicAverage} and
 * {@link AtomicMin} so it is both thread safe and performant.
 */
public class AverageAndMin {
    private final AverageStat averageStat;
    private final MinStat minStat;

    /**
     * @param metrics       reference to the metrics-system
     * @param category      the kind of statistic (stats are grouped or filtered by this)
     * @param name          a short name for the statistic
     * @param desc          a one-sentence description of the statistic
     * @param averageFormat a string that can be passed to String.format() to format the statistic for the average
     *                      number
     */
    public AverageAndMin(
            final Metrics metrics,
            final String category,
            final String name,
            final String desc,
            final String averageFormat) {
        this(metrics, category, name, desc, averageFormat, AverageStat.WEIGHT_SMOOTH, Long.MAX_VALUE);
    }

    /**
     * @param metrics       reference to the metrics-system
     * @param category      the kind of statistic (stats are grouped or filtered by this)
     * @param name          a short name for the statistic
     * @param desc          a one-sentence description of the statistic
     * @param averageFormat a string that can be passed to String.format() to format the statistic for the average
     *                      number
     * @param weight        the weight used to calculate the average
     * @param minCeiling    a default 'min' value, to avoid exploding metrics if value is never updated
     */
    public AverageAndMin(
            final Metrics metrics,
            final String category,
            final String name,
            final String desc,
            final String averageFormat,
            final double weight,
            final long minCeiling) {
        averageStat = new AverageStat(metrics, category, name, desc, averageFormat, weight);
        minStat = new MinStat(metrics, category, name + "MIN", "min value of " + name, "%d", minCeiling);
    }

    public void update(final long value) {
        averageStat.update(value);
        minStat.update(value);
    }
}
