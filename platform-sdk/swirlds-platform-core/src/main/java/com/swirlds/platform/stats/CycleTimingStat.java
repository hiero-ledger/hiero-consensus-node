// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.stats;

import com.swirlds.metrics.api.Metrics;
import com.swirlds.platform.stats.cycle.CycleDefinition;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

/**
 * A stat designed to track the amount of time spent in various parts of a cycle that is repeated over and over.
 */
public class CycleTimingStat {

    /** The average amount of time spent on a single cycle. */
    private final AverageTimeStat totalCycleTimeStat;

    /** The average amount of time spent on each interval in the cycle. */
    private final List<AverageTimeStat> timePointStats;

    /** The number of intervals in this cycle. */
    private final int numIntervals;

    /** JVM time points that define the beginning and end of each interval, in nanoseconds */
    private final long[] t;

    /**
     * <p>Creates a new instance whose number of time points is equal to the number of time intervals to be recorded.
     * For example, a cycle that requires 3 intervals to be recorded (interval 1, interval 2, and interval 3) should be
     * initialized with {@code numIntervals} equals to 3. Each part of the cycle is measured using the called shown
     * below:</p>
     *
     * <p>Interval 1: from {@code startCycle()} to {@code timePoint(1)}</p>
     * <p>Interval 2: from {@code timePoint(1)} to {@code timePoint(2)}</p>
     * <p>Interval 3: from {@code timePoint(2)} to {@code stopCycle()}</p>
     *
     * @param unit
     * 		the unit of time to measure all time intervals in this cycle
     * @param definition
     * 		the definition of the cycle state
     */
    public CycleTimingStat(final Metrics metrics, final ChronoUnit unit, final CycleDefinition definition) {

        this.numIntervals = definition.getNumIntervals();
        t = new long[numIntervals + 1];

        timePointStats = new ArrayList<>(numIntervals);
        for (int i = 0; i < numIntervals; i++) {
            timePointStats.add(new AverageTimeStat(
                    metrics,
                    unit,
                    definition.getCategory(),
                    definition.getName() + "_" + definition.getIntervalName(i),
                    definition.getIntervalDescription(i),
                    AverageStat.WEIGHT_VOLATILE));
        }

        totalCycleTimeStat = new AverageTimeStat(
                metrics,
                unit,
                definition.getCategory(),
                definition.getName() + "_total",
                "average total time spend in the " + definition.getName() + " cycle.",
                AverageStat.WEIGHT_VOLATILE);
    }

    /**
     * Mark the end of the current interval as the time of invocation and begin the next interval.
     *
     * @param i
     * 		the ith time point to record
     */
    public void setTimePoint(final int i) {
        if (i <= 0) {
            throw new IllegalArgumentException(
                    "Time point must be greater than 0. Use startCycle() to mark the beginning of the cycle.");
        } else if (i >= numIntervals) {
            throw new IllegalArgumentException(String.format(
                    "Time point must be less than %s. Use stopCycle() to mark the end of the cycle.",
                    numIntervals - 1));
        } else {
            t[i] = now();
        }
    }

    public void startCycle() {
        t[0] = now();
    }

    /**
     * Capture the end of the cycle time and update all stats for the time periods during the cycle.
     */
    public void stopCycle() {
        t[numIntervals] = now();

        for (int i = 0; i < t.length - 1; i++) {
            timePointStats.get(i).update(t[i], t[i + 1]);
        }

        totalCycleTimeStat.update(t[0], t[t.length - 1]);
    }

    /**
     * Get the current system time value in ns
     *
     * @return current time in ns
     */
    private static long now() {
        return System.nanoTime();
    }
}
