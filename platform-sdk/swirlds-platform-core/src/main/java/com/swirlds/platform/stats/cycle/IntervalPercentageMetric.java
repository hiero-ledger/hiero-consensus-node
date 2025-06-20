// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.stats.cycle;

import com.swirlds.base.units.UnitConstants;
import com.swirlds.metrics.api.Metrics;

/**
 * Tracks the fraction of time spent in a single interval of a cycle
 */
public class IntervalPercentageMetric extends PercentageMetric {

    public IntervalPercentageMetric(final Metrics metrics, final CycleDefinition definition, final int intervalIndex) {
        super(
                metrics,
                definition.getCategory(),
                definition.getName() + "-" + definition.getIntervalName(intervalIndex),
                definition.getIntervalDescription(intervalIndex));
    }

    /**
     * Update the time taken for this interval
     *
     * @param cycleNanoTime
     * 		the number of nanoseconds the whole cycle lasted
     * @param intervalNanoTime
     * 		the number of nanoseconds this interval lasted
     */
    public void updateTime(final long cycleNanoTime, final long intervalNanoTime) {
        super.update(toMicros(cycleNanoTime), toMicros(intervalNanoTime));
    }

    private int toMicros(final long nanos) {
        return (int) (nanos * UnitConstants.NANOSECONDS_TO_MICROSECONDS);
    }
}
