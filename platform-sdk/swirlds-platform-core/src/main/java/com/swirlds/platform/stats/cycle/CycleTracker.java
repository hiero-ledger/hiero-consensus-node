// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.stats.cycle;

import com.swirlds.base.time.Time;
import java.util.Arrays;

/**
 * Tracks the time points of a thread cycle and passes on the information to the metrics
 */
public class CycleTracker {
    private final CycleMetrics metrics;
    /** provides the current time in nanoseconds */
    private final Time time;
    /** duration of each interval, in nanoseconds */
    private final long[] duration;

    private long lastTime;
    private int intervalStarted;

    public CycleTracker(final Time time, final CycleMetrics metrics) {
        this.metrics = metrics;
        this.time = time;
        this.duration = new long[metrics.getNumIntervals()];
        this.lastTime = now();
    }

    /**
     * Called when a cycle starts
     */
    public void startCycle() {
        Arrays.fill(duration, 0);
        final long now = now();
        metrics.idleTime(now - lastTime);
        lastTime = now;
        intervalStarted = 0;
    }

    /**
     * Mark the end of the interval specified
     *
     * @param i
     * 		the index of the interval that ended
     */
    public void intervalEnded(final int i) {
        if (i < 0) {
            throw new IllegalArgumentException(
                    "Time point must be greater than 0. Use startCycle() to mark the beginning of the cycle.");
        } else if (i >= metrics.getNumIntervals()) {
            throw new IllegalArgumentException(String.format(
                    "Time point must be less than %s. Use stopCycle() to mark the end of the cycle.",
                    metrics.getNumIntervals() - 1));
        }

        final long now = now();
        duration[i] = now - lastTime;
        lastTime = now;
        intervalStarted = i + 1;
        metrics.intervalFinished(i, duration[i]);
    }

    /**
     * Capture the end of the cycle time and update all stats for the time periods during the cycle.
     */
    public void cycleEnded() {
        if (intervalStarted < metrics.getNumIntervals()) {
            intervalEnded(intervalStarted);
        }
        metrics.cycleFinished(duration);
    }

    /**
     * Get the current system time value in ns
     *
     * @return current time in ns
     */
    private long now() {
        return time.nanoTime();
    }
}
