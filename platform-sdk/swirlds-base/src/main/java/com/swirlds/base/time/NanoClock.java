// SPDX-License-Identifier: Apache-2.0
package com.swirlds.base.time;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Objects;

/**
 * A clock that is accurate to the nanosecond, as compared to the standard Java "Instant" clock, which is only
 * accurate to the nearest millisecond.
 */
public final class NanoClock extends Clock {

    private final Clock clock;
    private final long initialNanos;
    private final Instant initialInstant;

    /**
     * Creates a NanoClock for UTC time.
     */
    public NanoClock() {
        this(Clock.systemUTC());
    }

    /**
     * Creates a new NanoClock wrapping a {@code Clock} instance.
     *
     * @param clock clock to use as source for a new NanoClock instance
     * @throws NullPointerException if {@code clock} is {@code null}
     */
    public NanoClock(@NonNull final Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        this.initialInstant = clock.instant();
        this.initialNanos = getSystemNanos();
    }

    /**
     * @return the time-zone being used to interpret instants, not null
     */
    @Override
    @NonNull
    public ZoneId getZone() {
        return this.clock.getZone();
    }

    /**
     * @return the current instant from this clock, not null
     */
    @Override
    @NonNull
    public Instant instant() {
        return this.initialInstant.plusNanos(getSystemNanos() - this.initialNanos);
    }

    /**
     * @param zone the time-zone to change to, not null
     * @return Returns a copy of this clock with a different time-zone
     * @throws NullPointerException if {@code zone} is {@code null}
     */
    @Override
    @NonNull
    public Clock withZone(@NonNull final ZoneId zone) {
        return new NanoClock(this.clock.withZone(Objects.requireNonNull(zone, "zone must not be null")));
    }

    private static long getSystemNanos() {
        return System.nanoTime();
    }
}
