// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.metrics.statistics;

import com.swirlds.metrics.api.Metrics;
import org.hiero.consensus.metrics.StatEntry;

public class MinStat {
    private final AtomicMin min;

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
     * @param minCeiling
     *      a default 'min' value, to avoid exploding metrics if value is never updated
     */
    public MinStat(
            final Metrics metrics,
            final String category,
            final String name,
            final String desc,
            final String format,
            final long minCeiling) {
        min = new AtomicMin(minCeiling);
        metrics.getOrCreate(new StatEntry.Config<>(category, name, Long.class, min::get)
                .withDescription(desc)
                .withFormat(format)
                .withReset(this::resetMin)
                .withResetStatsStringSupplier(min::getAndReset));
    }

    private void resetMin(final double unused) {
        min.reset();
    }

    /**
     * Update the max value
     *
     * @param value
     * 		the value to update with
     */
    public void update(long value) {
        min.update(value);
    }
}
