// SPDX-License-Identifier: Apache-2.0
package com.swirlds.base.test.fixtures.time;

import static com.swirlds.base.units.UnitConstants.NANOSECONDS_TO_MILLISECONDS;

import com.swirlds.base.time.Time;
import java.time.Duration;
import java.time.Instant;
import java.util.Calendar;
import java.util.TimeZone;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A fake clock that can be easily manipulated for testing purposes.
 */
public class FakeTime implements Time {

    private static final int DEFAULT_YEAR = 2018;
    private static final int DEFAULT_MONTH = Calendar.AUGUST;
    private static final int DEFAULT_DATE = 25;
    private static final int DEFAULT_HOUR_OF_DAY = 1;
    private static final int DEFAULT_MINUTE = 24;
    private static final int DEFAULT_SECOND = 9;
    private static final int DEFAULT_MILLIS_TO_ADD = 693;
    private static final Instant DEFAULT_START;
    private final Instant start;
    private final long autoIncrement;
    private final AtomicLong elapsedNanos = new AtomicLong(0);

    static {
        final Calendar calendar = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
        calendar.set(DEFAULT_YEAR, DEFAULT_MONTH, DEFAULT_DATE, DEFAULT_HOUR_OF_DAY, DEFAULT_MINUTE, DEFAULT_SECOND);
        DEFAULT_START = calendar.toInstant().plusMillis(DEFAULT_MILLIS_TO_ADD);
    }

    /**
     * Create a fake clock, and start it at whatever time the wall clock says is "now". Does not auto increment.
     */
    public FakeTime() {
        this(DEFAULT_START, Duration.ZERO);
    }

    /**
     * Create a fake clock starting now.
     *
     * @param autoIncrement the {@link Duration} the clock will auto-increment by each time it is observed.
     */
    public FakeTime(final Duration autoIncrement) {
        this(Instant.now(), autoIncrement);
    }

    /**
     * Create a fake clock that starts at a particular time.
     *
     * @param start         the starting timestamp relative to the epoch
     * @param autoIncrement the {@link Duration} the clock will auto-increment by each time it is observed.
     */
    public FakeTime(final Instant start, final Duration autoIncrement) {
        this.start = start;
        this.autoIncrement = autoIncrement.toNanos();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public long nanoTime() {
        return elapsedNanos.getAndAdd(autoIncrement);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public long currentTimeMillis() {
        return (long) (start.toEpochMilli() + nanoTime() * NANOSECONDS_TO_MILLISECONDS);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Instant now() {
        return start.plusNanos(nanoTime());
    }

    /**
     * Tick forward by a single nanosecond.
     */
    public void tick() {
        tick(1);
    }

    /**
     * Move the clock forward a number of nanoseconds.
     */
    public void tick(final long nanos) {
        if (nanos < 0) {
            throw new IllegalStateException("clock can only move forward");
        }
        elapsedNanos.getAndAdd(nanos);
    }

    /**
     * Move the clock forward for an amount of time.
     */
    public void tick(final Duration time) {
        tick(time.toNanos());
    }

    /**
     * <p>
     * Directly set the value of the chronometer relative to when the chronometer was started
     * </p>
     *
     * <p>
     * WARNING: this method can cause the clock to go backwards in time. This is impossible for a "real" implementation
     * that will be used in production environments.
     * </p>
     *
     * @param elapsedSinceStart the time that has elapsed since the chronometer has started
     * @deprecated it's really easy to do things with this method that can never happen in reality, don't use this
     * method for new tests
     */
    @Deprecated
    public void set(final Duration elapsedSinceStart) {
        elapsedNanos.set(elapsedSinceStart.toNanos());
    }

    /**
     * <p>
     * Reset this chronometer to the state it was in immediately after construction.
     * </p>
     *
     * <p>
     * WARNING: this method can cause the clock to go backwards in time. This is impossible for a "real" implementation
     * that will be used in production environments.
     * </p>
     */
    public void reset() {
        elapsedNanos.set(0);
    }

    /**
     * Get the fake duration that has elapsed since fake clock was started.
     */
    public Duration elapsed() {
        return Duration.between(start, now());
    }
}
