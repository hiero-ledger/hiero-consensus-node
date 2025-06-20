// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.stats.cycle;

/**
 * Metrics that track a thread cycle
 */
public interface CycleMetrics {
    /**
     * Called when an interval has ended and the next interval begins.
     *
     * @param interval
     * 		the index of the interval that ended
     * @param durationNanos
     * 		the number of nanoseconds the interval took to complete
     */
    void intervalFinished(int interval, long durationNanos);

    /**
     * Called when a single cycle has ended
     *
     * @param intervalNanoTime
     * 		the time in nanoseconds spent on each interval
     */
    void cycleFinished(long[] intervalNanoTime);

    /**
     * Called when a thread stops being idle
     *
     * @param nanoTime
     * 		the time in nanoseconds spent idle
     */
    void idleTime(long nanoTime);

    /**
     * @return the number of intervals of the cycle
     */
    int getNumIntervals();
}
