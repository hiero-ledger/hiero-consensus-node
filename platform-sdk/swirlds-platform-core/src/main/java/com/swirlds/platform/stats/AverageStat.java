// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.stats;

import com.swirlds.common.metrics.StatEntry;
import com.swirlds.metrics.api.Metrics;

/**
 * A metrics object to track an average number, without history. This class uses an {@link AtomicAverage} so it is both
 * thread safe and performant.
 */
public class AverageStat {
    /**
     * does not change very quickly
     */
    public static final double WEIGHT_SMOOTH = 0.01;
    /**
     * changes average quite rapidly
     */
    public static final double WEIGHT_VOLATILE = 0.1;

    private final AtomicAverage average;

    /**
     * @param metrics
     * 		reference to the metrics-system
     * @param category
     * 		the kind of statistic (stats are grouped or filtered by this)
     * @param name
     * 		a short name for the statistic
     * @param desc
     * 		a one-sentence description of the statistic
     * @param format
     * 		a string that can be passed to String.format() to format the statistic for the average number
     * @param weight
     * 		the weight used to calculate the average
     */
    public AverageStat(
            final Metrics metrics,
            final String category,
            final String name,
            final String desc,
            final String format,
            final double weight) {
        average = new AtomicAverage(weight);
        metrics.getOrCreate(new StatEntry.Config<>(category, name, Double.class, average::get)
                .withDescription(desc)
                .withFormat(format)
                .withReset(this::reset));
    }

    private void reset(final double unused) {
        average.reset();
    }

    /**
     * Update the average value
     *
     * @param value
     * 		the value to update with
     */
    public void update(final long value) {
        average.update(value);
    }

    /**
     * Updates the average with 1 or 0 depending on the boolean value
     *
     * @param value
     * 		the value used to update the average
     */
    public void update(final boolean value) {
        update(value ? 1 : 0);
    }
}
