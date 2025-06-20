// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.stats;

import com.swirlds.common.metrics.StatEntry;
import com.swirlds.metrics.api.Metrics;

public class MaxStat {
    private final AtomicMax max;

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
     */
    public MaxStat(
            final Metrics metrics, final String category, final String name, final String desc, final String format) {
        max = new AtomicMax();
        metrics.getOrCreate(new StatEntry.Config<>(category, name, Long.class, max::get)
                .withDescription(desc)
                .withFormat(format)
                .withReset(this::resetMax)
                .withResetStatsStringSupplier(max::getAndReset));
    }

    private void resetMax(final double unused) {
        max.reset();
    }

    /**
     * Update the max value
     *
     * @param value
     * 		the value to update with
     */
    public void update(long value) {
        max.update(value);
    }
}
