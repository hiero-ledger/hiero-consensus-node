// SPDX-License-Identifier: Apache-2.0
package org.hiero.metrics.api.utils;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.temporal.ChronoUnit;
import java.util.Objects;

/**
 * Common units used in metrics.
 */
public final class Unit {

    private Unit() {}

    /**
     * Unit of frequency in Hertz.
     */
    public static final String FREQUENCY_UNIT = "hz";

    /**
     * Unit of nanoseconds.
     */
    public static final String NANOSECOND_UNIT = "ns";

    /**
     * Unit of microseconds.
     */
    public static final String MICROSECOND_UNIT = "µs";

    /**
     * Unit of milliseconds.
     */
    public static final String MILLISECOND_UNIT = "ms";

    /**
     * Unit of seconds.
     */
    public static final String SECOND_UNIT = "s";

    /**
     * Unit of bytes.
     */
    public static final String BYTE_UNIT = "byte";

    /**
     * Unit of megabytes.
     */
    public static final String MEGABYTE_UNIT = "mb";

    /**
     * Unit for time, based on the provided ChronoUnit. If the ChronoUnit is not one of
     * NANOS, MICROS, MILLIS, or SECONDS, an empty string is returned.
     */
    @NonNull
    public static String getUnit(final ChronoUnit timeUnit) {
        Objects.requireNonNull(timeUnit, "timeUnit must not be null");
        return switch (timeUnit) {
            case NANOS -> NANOSECOND_UNIT;
            case MICROS -> MICROSECOND_UNIT;
            case MILLIS -> MILLISECOND_UNIT;
            case SECONDS -> SECOND_UNIT;
            default -> "";
        };
    }
}
