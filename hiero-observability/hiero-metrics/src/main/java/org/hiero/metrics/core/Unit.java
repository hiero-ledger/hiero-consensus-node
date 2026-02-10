// SPDX-License-Identifier: Apache-2.0
package org.hiero.metrics.core;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.temporal.ChronoUnit;
import java.util.Objects;

/**
 * Common units used in metrics.
 */
public enum Unit {
    /**
     * No unit (empty unit).
     */
    NO_UNIT(""),
    /**
     * Unit of frequency in Hertz.
     */
    FREQUENCY_UNIT("hz"),
    /**
     * Unit of nanoseconds.
     */
    NANOSECOND_UNIT("ns"),
    /**
     * Unit of microseconds.
     */
    MICROSECOND_UNIT("µs"),
    /**
     * Unit of milliseconds.
     */
    MILLISECOND_UNIT("ms"),
    /**
     * Unit of seconds.
     */
    SECOND_UNIT("s"),
    /**
     * Unit of bytes.
     */
    BYTE_UNIT("byte"),
    /**
     * Unit of megabytes.
     */
    MEGABYTE_UNIT("mb");

    private final String stringValue;

    Unit(final String stringValue) {
        this.stringValue = stringValue;
    }

    /**
     * Unit for time, based on the provided ChronoUnit. If the ChronoUnit is not
     * one of NANOS, MICROS, MILLIS, or SECONDS, an {@link #NO_UNIT} is returned.
     */
    @NonNull
    public static Unit fromChronoUnit(final ChronoUnit timeUnit) {
        Objects.requireNonNull(timeUnit, "timeUnit must not be null");
        return switch (timeUnit) {
            case NANOS -> NANOSECOND_UNIT;
            case MICROS -> MICROSECOND_UNIT;
            case MILLIS -> MILLISECOND_UNIT;
            case SECONDS -> SECOND_UNIT;
            default -> NO_UNIT;
        };
    }

    @Override
    public String toString() {
        return stringValue;
    }
}
