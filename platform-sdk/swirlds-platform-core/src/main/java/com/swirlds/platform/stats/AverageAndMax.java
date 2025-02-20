// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.stats;

import com.swirlds.metrics.api.Metrics;

/**
 * A metrics object to track an average number, without history. This class uses an {@link AtomicAverage} so it is both
 * thread safe and performant.
 */
public class AverageAndMax {
    private static final String FORMAT_MAX = "%10d";
    private final AverageStat averageStat;
    private final MaxStat maxStat;

    /**
     * @param metrics
     * 		reference to the metrics-system
     * @param category
     * 		the kind of statistic (stats are grouped or filtered by this)
     * @param name
     * 		a short name for the statistic
     * @param desc
     * 		a one-sentence description of the statistic
     * @param averageFormat
     * 		a string that can be passed to String.format() to format the statistic for the average number
     */
    public AverageAndMax(
            final Metrics metrics,
            final String category,
            final String name,
            final String desc,
            final String averageFormat) {
        this(metrics, category, name, desc, averageFormat, AverageStat.WEIGHT_SMOOTH);
    }

    /**
     * @param metrics
     * 		reference to the metrics-system
     * @param category
     * 		the kind of statistic (stats are grouped or filtered by this)
     * @param name
     * 		a short name for the statistic
     * @param desc
     * 		a one-sentence description of the statistic
     * @param averageFormat
     * 		a string that can be passed to String.format() to format the statistic for the average number
     * @param weight
     * 		the weight used to calculate the average
     */
    public AverageAndMax(
            final Metrics metrics,
            final String category,
            final String name,
            final String desc,
            final String averageFormat,
            final double weight) {
        averageStat = new AverageStat(metrics, category, name, desc, averageFormat, weight);
        maxStat = new MaxStat(metrics, category, name + "MAX", "max value of " + name, FORMAT_MAX);
    }

    public void update(final long value) {
        averageStat.update(value);
        maxStat.update(value);
    }
}
