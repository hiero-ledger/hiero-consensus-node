// SPDX-License-Identifier: Apache-2.0
package com.swirlds.common.utility;

import com.swirlds.base.time.Time;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/**
 * A simple stop watch that can be used to measure elapsed time.
 */
public class StopWatch {
    private final Time time;
    private long startTime;
    private long endTime;
    private boolean running;
    private boolean suspended;

    /**
     * Creates a new StopWatch.
     */
    public StopWatch() {
        this(Time.getCurrent());
    }

    /**
     * Creates a new StopWatch.
     *
     * @param time the time to use to be able to mock time
     */
    public StopWatch(@NonNull final Time time) {
        this.time = Objects.requireNonNull(time);
        this.running = false;
        this.suspended = false;
    }

    /**
     * Starts StopWatch
     */
    public void start() {
        if (running) {
            throw new IllegalStateException("StopWatch is already running.");
        }
        this.running = true;
        this.startTime = time.nanoTime();
    }

    /**
     * Stops StopWatch only after being running
     */
    public void stop() {
        final long nanoTime = time.nanoTime();
        if (!running || suspended) {
            throw new IllegalStateException("StopWatch is not running.");
        }
        this.endTime = nanoTime;
        this.running = false;
    }

    /**
     * Suspends StopWatch only after being started
     */
    public void suspend() {
        final long nanoTime = time.nanoTime();
        if (!running || suspended) {
            throw new IllegalStateException("StopWatch is not running or is already suspended.");
        }
        this.endTime = nanoTime;
        this.suspended = true;
    }

    /**
     * Resumes StopWatch only after being suspended
     */
    public void resume() {
        final long nanoTime = time.nanoTime();
        if (!suspended) {
            throw new IllegalStateException("StopWatch is not suspended.");
        }
        this.startTime += (nanoTime - endTime); // Adjusting the startTime
        this.suspended = false;
    }

    /**
     * Returns the elapsed time in nanoseconds.
     *
     * @return the elapsed time in nanoseconds
     * @throws IllegalStateException if the StopWatch has not started yet
     */
    public long getElapsedTimeNano() {
        final long nanoTime = time.nanoTime();
        if (startTime == 0 && endTime == 0) {
            throw new IllegalStateException("StopWatch has not started yet.");
        }

        if (running) {
            return nanoTime - startTime;
        }

        return endTime - startTime;
    }

    /**
     * Returns if StopWatch is running
     *
     * @return true if StopWatch is running, false otherwise
     */
    public boolean isRunning() {
        return running;
    }

    /**
     * Returns if StopWatch is suspended
     *
     * @return true if StopWatch is suspended, false otherwise
     */
    public boolean isSuspended() {
        return suspended;
    }

    /**
     * Returns if StopWatch is stopped
     *
     * @return true if StopWatch is stopped, false otherwise
     */
    public boolean isStopped() {
        return !running && !suspended;
    }

    /**
     * Resets StopWatch
     */
    public void reset() {
        this.startTime = 0;
        this.endTime = 0;
        this.running = false;
    }

    /**
     * Returns the elapsed time in the specified time unit.
     *
     * @param unit
     * 		the time unit to return the elapsed time in
     * @return the elapsed time in the specified {@code unit}
     * @throws IllegalStateException if the StopWatch has not started yet
     */
    public long getTime(@NonNull final TimeUnit unit) {
        Objects.requireNonNull(unit, "unit is null");

        return switch (unit) {
            case NANOSECONDS -> getElapsedTimeNano();
            case MICROSECONDS -> TimeUnit.NANOSECONDS.toMicros(getElapsedTimeNano());
            case MILLISECONDS -> TimeUnit.NANOSECONDS.toMillis(getElapsedTimeNano());
            case SECONDS -> TimeUnit.NANOSECONDS.toSeconds(getElapsedTimeNano());
            case MINUTES -> TimeUnit.NANOSECONDS.toMinutes(getElapsedTimeNano());
            case HOURS -> TimeUnit.NANOSECONDS.toHours(getElapsedTimeNano());
            case DAYS -> TimeUnit.NANOSECONDS.toDays(getElapsedTimeNano());
        };
    }
}
