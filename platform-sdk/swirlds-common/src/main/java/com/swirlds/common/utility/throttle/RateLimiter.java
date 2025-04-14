// SPDX-License-Identifier: Apache-2.0
package com.swirlds.common.utility.throttle;

import static com.swirlds.base.units.UnitConstants.SECONDS_TO_NANOSECONDS;
import static org.hiero.base.utility.CompareTo.isGreaterThanOrEqualTo;

import com.swirlds.base.time.Time;
import java.time.Duration;
import java.time.Instant;

/**
 * <p>
 * A simple utility designed to limit the frequency of an event, e.g. making sure a particular log message isn't written
 * too often.
 * </p>
 *
 * <p>
 * This object is not thread safe. This object was designed for simplicity and ease of use. This object may not be
 * suitable for code pathways with extremely high performance requirements.
 * </p>
 */
public class RateLimiter {

    /**
     * The minimum amount of time that must pass before this operation may happen another time.
     */
    private final Duration minimumPeriod;

    /**
     * The time when this rate limiter last allowed an operation to happen.
     */
    private Instant lastOperation = Instant.EPOCH;

    /**
     * The number of requests that have been denied since the last successful operation.
     */
    private long deniedRequests;

    /**
     * Provides a view of the current time.
     */
    private final Time time;

    /**
     * Create a new rate limiter.
     *
     * @param time          provides the current time
     * @param minimumPeriod the minimum time that must pass between operations
     */
    public RateLimiter(final Time time, final Duration minimumPeriod) {
        this.time = time;
        this.minimumPeriod = minimumPeriod;
    }

    /**
     * Create a new rate limiter.
     *
     * @param time         provides the current time
     * @param maxFrequency the maximum frequency of the operation, in hz
     */
    public RateLimiter(final Time time, final double maxFrequency) {
        this(time, Duration.ofNanos((long) (1.0 / maxFrequency * SECONDS_TO_NANOSECONDS)));
    }

    /**
     * Request permission to trigger an operation, and immediately trigger if permitted. Returns true if it is ok to
     * perform the operation, returns false if
     * the operation has been performed too recently in the past. Once this
     * method returns true, it will return false for the remainder of the time span specified by the minimum period.
     *
     * @return true if the operation can be triggered without violating rate limits, otherwise false
     */
    public boolean requestAndTrigger() {
        final Instant now = time.now();
        final Duration elapsed = Duration.between(lastOperation, now);
        if (isGreaterThanOrEqualTo(elapsed, minimumPeriod)) {
            lastOperation = now;
            deniedRequests = 0;
            return true;
        }
        deniedRequests++;
        return false;
    }

    /**
     * Check if it is legal to trigger the rate limited action. Unlike {@link #requestAndTrigger()}, this method can
     * return true over and over in a time span smaller than the desired rate limit. In order to cause this method to
     * return false for the remainder of the time span specified by the rate limit, call {@link #trigger()}.
     *
     * @return true if it is currently legal to trigger the rate limited action
     */
    public boolean request() {
        final Instant now = time.now();
        final Duration elapsed = Duration.between(lastOperation, now);
        if (isGreaterThanOrEqualTo(elapsed, minimumPeriod)) {
            deniedRequests = 0;
            return true;
        }
        deniedRequests++;
        return false;
    }

    /**
     * Trigger the action that is being rate limited. Calling this method will cause {@link #request()} and
     * {@link #requestAndTrigger()} to return false for the remainder of the desired rate limit. This method
     * does not actually check if enough time has passed to permit the action being triggered. Calling this method
     * before the end of a rate limit period will reset the rate limit period.
     */
    public void trigger() {
        deniedRequests = 0;
        lastOperation = time.now();
    }

    /**
     * Get the number of times {@link #requestAndTrigger()} and/or {@link #request()} has returned false since the last
     * time one of these methods returned true. Immediately after {@link #requestAndTrigger()} or {@link #request()}
     * returns true, this method will return 0.
     *
     * @return the number of recently denied requests
     */
    public long getDeniedRequests() {
        return deniedRequests;
    }
}
