// SPDX-License-Identifier: Apache-2.0
package com.swirlds.common.time;

import com.swirlds.base.time.Time;
import com.swirlds.base.units.UnitConstants;

/**
 * A wrapper around a {@link Time} instance that provides the lower 31 bits of the epoch, and calculates elapsed time.
 * Useful when storing the epoch in an int.
 */
public class IntegerEpochTime {
    private final Time time;

    /**
     * @param time the clock to use for the epoch
     */
    public IntegerEpochTime(final Time time) {
        this.time = time;
    }

    /**
     * @return the lower 31 bits of the current millisecond epoch according to the clock stored in this instance
     */
    public int getMilliTime() {
        return (int) (time.currentTimeMillis() % Integer.MAX_VALUE);
    }

    /**
     * @return the lower 31 bits of the current microsecond epoch according to the clock stored in this instance
     */
    public int getMicroTime() {
        return (int) ((time.nanoTime() / UnitConstants.MICROSECONDS_TO_NANOSECONDS) % Integer.MAX_VALUE);
    }

    /**
     * @param startTime a value previously returned by {@link #getMilliTime()}
     * @return the elapsed time in milliseconds from the start time until now
     */
    public int millisElapsed(final int startTime) {
        return elapsed(startTime, getMilliTime());
    }

    /**
     * @param startTime a value previously returned by {@link #getMicroTime()}
     * @return the elapsed time in microseconds from the start time until now
     */
    public int microsElapsed(final int startTime) {
        return elapsed(startTime, getMicroTime());
    }

    /**
     * @param startTime a value previously returned by {@link #getMilliTime()}
     * @param endTime   a value previously returned by {@link #getMilliTime()}
     * @return the elapsed time in from the start time until the end time
     */
    public static int elapsed(final int startTime, final int endTime) {
        if (endTime >= startTime) {
            return endTime - startTime;
        } else {
            // if the lower 31 bits of the epoch has rolled over, the start time will be bigger than current time
            return Integer.MAX_VALUE - startTime + endTime;
        }
    }
}
