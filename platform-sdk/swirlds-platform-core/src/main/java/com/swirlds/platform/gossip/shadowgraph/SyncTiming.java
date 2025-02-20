// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.gossip.shadowgraph;

/**
 * A type to record the points for gossip steps. At the end of a gossip session,
 * the results are reported to the relevant statistics accumulators which quantify
 * gossip performance.
 */
public class SyncTiming {
    /**
     * number of time points to record = {number of time intervals} + 1
     */
    private static final int NUM_TIME_POINTS = 6;

    /**
     * JVM time points, in nanoseconds
     */
    private final long[] t = new long[NUM_TIME_POINTS];

    /**
     * Set the 0th time point
     */
    public void start() {
        t[0] = now();
    }

    /**
     * Record the ith time point
     *
     * @param i
     * 		the ith time point to record
     */
    public void setTimePoint(final int i) {
        t[i] = now();
    }

    /**
     * Get the ith time point
     *
     * @param i
     * 		the index of the time point
     * @return the ith recorded time point
     */
    public long getTimePoint(final int i) {
        return t[i];
    }

    /**
     * Get the difference between two time points
     *
     * @param end
     * 		the ending point
     * @param start
     * 		the starting point
     * @return the difference
     */
    public long getPointDiff(final int end, final int start) {
        return t[end] - t[start];
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
