// SPDX-License-Identifier: Apache-2.0
package com.swirlds.common.metrics.extensions;

import com.swirlds.base.time.Time;
import com.swirlds.common.metrics.FunctionGauge;
import com.swirlds.common.time.IntegerEpochTime;
import com.swirlds.common.utility.throttle.RateLimitedLogger;
import com.swirlds.logging.legacy.LogMarker;
import com.swirlds.metrics.api.FloatFormats;
import com.swirlds.metrics.api.Metrics;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongBinaryOperator;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hiero.base.utility.ByteUtils;
import org.hiero.consensus.model.utility.StackTrace;

/**
 * A utility that measures the fraction of time that is spent in one of two phases. For example, can be used to track
 * the overall busy time of a thread, or the busy time of a specific subtask. The granularity of this metric is in
 * microseconds.
 * <p>
 * This object must be measured at least once every 34 minutes or else it will overflow and return -1.
 * </p>
 */
public class StandardFractionalTimer implements FractionalTimer {
    private static final Logger logger = LogManager.getLogger(StandardFractionalTimer.class);

    /**
     * the initial value of status when the instance is created
     */
    private static final int INITIAL_STATUS = -1;

    /**
     * the value of start time when the metric has overflowed
     */
    private static final int OVERFLOW = -1;

    /**
     * if an error occurs, do not write a log statement more often than this
     */
    private static final Duration LOG_PERIOD = Duration.ofMinutes(5);

    /**
     * An instance that provides the current time
     */
    private final IntegerEpochTime time;

    /**
     * Used to atomically update and reset the time and status
     */
    private final AtomicLong accumulator;

    /**
     * limits the frequency of error log statements
     */
    private final RateLimitedLogger errorLogger;

    /**
     * This lambda is used to enter an active state.
     */
    private final LongBinaryOperator activationUpdate;

    /**
     * This lambda is used to enter an inactive state.
     */
    private final LongBinaryOperator deactivationUpdate;

    /**
     * A constructor where a custom {@link Time} instance could be supplied
     *
     * @param time provides the current time
     */
    public StandardFractionalTimer(@NonNull final Time time) {
        this.time = new IntegerEpochTime(time);
        this.accumulator = new AtomicLong(ByteUtils.combineInts(this.time.getMicroTime(), INITIAL_STATUS));
        this.errorLogger = new RateLimitedLogger(logger, time, LOG_PERIOD);

        activationUpdate = (currentState, now) -> statusUpdate(currentState, true, now);
        deactivationUpdate = (currentState, now) -> statusUpdate(currentState, false, now);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void registerMetric(
            @NonNull final Metrics metrics,
            @NonNull final String category,
            @NonNull final String name,
            @NonNull final String description) {
        metrics.getOrCreate(new FunctionGauge.Config<>(category, name, Double.class, this::getAndReset)
                .withDescription(description)
                .withUnit("fraction")
                .withFormat(FloatFormats.FORMAT_1_3));
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void activate(final long now) {
        accumulator.accumulateAndGet(now, activationUpdate);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void activate() {
        this.activate(time.getMicroTime());
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void deactivate(final long now) {
        accumulator.accumulateAndGet(now, deactivationUpdate);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void deactivate() {
        this.deactivate(time.getMicroTime());
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public double getActiveFraction() {
        final long pair = accumulator.get();
        return activeFraction(ByteUtils.extractLeftInt(pair), ByteUtils.extractRightInt(pair));
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public double getAndReset() {
        final long pair = accumulator.getAndUpdate(this::reset);
        return activeFraction(ByteUtils.extractLeftInt(pair), ByteUtils.extractRightInt(pair));
    }

    /**
     * Gets the fraction of time this object has been active since the last reset.
     *
     * @param measurementStart the micro epoch time when the last reset occurred
     * @param status           the current status of this object and the time spent in the opposite status
     * @return the fraction of time that this object has been active, where 0.0 means this object is not at all active,
     * and 1.0 means that this object is 100% active, or -1 if the metric has overflowed because it was not reset
     */
    private double activeFraction(final int measurementStart, final int status) {
        if (measurementStart < 0) {
            return OVERFLOW;
        }
        final int elapsedTime = time.microsElapsed(measurementStart);
        if (elapsedTime == 0) {
            return 0;
        }
        final int activeTime;
        if (isInactive(status)) {
            activeTime = Math.abs(status) - 1;
        } else {
            activeTime = elapsedTime - (status - 1);
        }
        return ((double) activeTime) / elapsedTime;
    }

    /**
     * Check if this timer is currently inactive.
     *
     * @param status the current status of this object
     * @return true if this timer is currently inactive
     */
    private boolean isInactive(final int status) {
        return status < 0;
    }

    /**
     * Update the state of this object. The state is the value stored in an atomic long.
     *
     * @param currentState     the current value of the atomic long. Two integers are packed into this long value. The
     *                         first four bytes represent the timestamp when we initially began the current measurement
     *                         period. The last four bytes represent the total time spent in the opposite status. The
     *                         right four bytes can be positive or negative. If positive, it means that the object is
     *                         currently active. If negative, it means that the object is currently inactive.
     * @param isBecomingActive true if the object is currently becoming active, false if it is currently becoming
     *                         inactive
     * @param now              the current time in microseconds
     * @return the new value that will be stored in the atomic long.
     */
    private long statusUpdate(final long currentState, final boolean isBecomingActive, final long now) {

        // the epoch time when the last reset occurred
        final int measurementStart = ByteUtils.extractLeftInt(currentState);
        // The current status is represented by the (+ -) sign. The number represents the time spent in the
        // opposite status. This is so that its time spent being active/inactive can be deduced whenever the sample is
        // taken. If the time spent active is X, and the measurement time is Y, then inactive time is Y-X. Since zero
        // is neither positive nor negative, the values are offset by 1
        final int currentStatus = ByteUtils.extractRightInt(currentState);

        // In order to fit the time into 4 bytes, we need to truncate the time to the lower 31 bits.
        // This causes the timer to become inaccurate after 34 minutes, but that is not a problem,
        // since this utility is intended to be used to measure time over much shorter intervals.
        final int truncatedTime = (int) now;

        if ((isBecomingActive && !isInactive(currentStatus)) || (!isBecomingActive && isInactive(currentStatus))) {
            // this means that the metric has not been updated correctly, we will not change the value
            errorLogger.error(
                    LogMarker.EXCEPTION.getMarker(),
                    "FractionalTimer has been updated incorrectly. "
                            + "Current status: {}, is becoming active: {}, stack trace: \n{}",
                    currentStatus,
                    isBecomingActive,
                    StackTrace.getStackTrace().toString());
            return currentState;
        }
        // the time elapsed since the last reset
        final int elapsedTime = IntegerEpochTime.elapsed(measurementStart, truncatedTime);
        // the time spent in the opposite status, either active or inactive
        final int statusTime = Math.abs(currentStatus) - 1;
        // the time spent inactive is all the elapsed time minus the time spent active
        // the time spent active is all the elapsed time minus the time spent inactive
        final int newTime = elapsedTime - statusTime;
        if (newTime < 0 || measurementStart < 0) {
            // this is an overflow because the metric was not reset, we are in a state where we can no longer track
            // the time spent inactive or active
            return ByteUtils.combineInts(OVERFLOW, isBecomingActive ? 1 : -1);
        }
        if (isBecomingActive) {
            // this means this was previously inactive and now started working
            return ByteUtils.combineInts(measurementStart, newTime + 1);
        }
        // this means the object was previously active and now stopped working
        return ByteUtils.combineInts(measurementStart, -newTime - 1);
    }

    /**
     * This lambda is used to reset all data stored by this object and begin a new measurement period.
     *
     * @param currentState the current state of this object
     * @return the new state of this object
     */
    private long reset(final long currentState) {
        return ByteUtils.combineInts(time.getMicroTime(), resetStatus(ByteUtils.extractRightInt(currentState)));
    }

    /**
     * Used to generate the rightmost four bytes in the state during a restart.
     *
     * @param status the current rightmost four bytes
     * @return the new rightmost four bytes
     */
    private int resetStatus(final int status) {
        if (status > 0) {
            return 1;
        }
        return -1;
    }
}
