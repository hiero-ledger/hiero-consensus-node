// SPDX-License-Identifier: Apache-2.0
package com.swirlds.base.units;

/**
 * Contains a variety constants useful when converting between units.
 * <p>
 * All constants are in the form "UNIT1_TO_UNIT2" where you multiply by the constant to go from UNIT1 to UNIT2, or
 * divide by the constant to go from UNIT2 to UNIT1.
 */
public final class UnitConstants {

    private UnitConstants() {}

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
     * Multiply by this value for converting seconds to nanoseconds.
     */
    public static final int SECONDS_TO_NANOSECONDS = 1_000_000_000;

    /**
     * Multiply by this value for converting nanoseconds to seconds.
     */
    public static final double NANOSECONDS_TO_SECONDS = 1.0 / SECONDS_TO_NANOSECONDS;

    /**
     * Multiply by this value for converting seconds to microseconds.
     */
    public static final int SECONDS_TO_MICROSECONDS = 1_000_000;

    /**
     * Multiply by this value for converting microseconds to seconds.
     */
    public static final double MICROSECONDS_TO_SECONDS = 1.0 / SECONDS_TO_MICROSECONDS;

    /**
     * Multiply by this value for converting seconds to milliseconds.
     */
    public static final int SECONDS_TO_MILLISECONDS = 1_000;

    /**
     * Multiply by this value for converting microseconds to seconds.
     */
    public static final double MILLISECONDS_TO_SECONDS = 1.0 / SECONDS_TO_MILLISECONDS;

    /**
     * Multiply by this value for converting milliseconds to nanoseconds.
     */
    public static final int MILLISECONDS_TO_NANOSECONDS = 1_000_000;

    /**
     * Multiply by this value for converting nanoseconds to milliseconds.
     */
    public static final double NANOSECONDS_TO_MILLISECONDS = 1.0 / MILLISECONDS_TO_NANOSECONDS;

    /**
     * Multiply by this value for converting milliseconds to microseconds.
     */
    public static final int MILLISECONDS_TO_MICROSECONDS = 1_000;

    /**
     * Multiply by this value for converting microseconds to milliseconds.
     */
    public static final double MICROSECONDS_TO_MILLISECONDS = 1.0 / MILLISECONDS_TO_MICROSECONDS;

    /**
     * Multiply by this value for converting microseconds to nanoseconds.
     */
    public static final int MICROSECONDS_TO_NANOSECONDS = 1_000;

    /**
     * Multiply by this value for converting nanoseconds to microseconds.
     */
    public static final double NANOSECONDS_TO_MICROSECONDS = 1.0 / MICROSECONDS_TO_NANOSECONDS;

    /**
     * Multiply by this value for converting minutes to seconds.
     */
    public static final int MINUTES_TO_SECONDS = 60;

    /**
     * Multiply by this value for converting hours to minutes.
     */
    public static final int HOURS_TO_MINUTES = 60;

    /**
     * Multiply by this value for converting days to hours.
     */
    public static final int DAYS_TO_HOURS = 24;

    /**
     * Multiply by this value for converting weeks to days.
     */
    public static final int WEEKS_TO_DAYS = 7;

    /**
     * Multiply by this value for converting bytes to bits.
     */
    public static final int BYTES_TO_BITS = 8;

    /**
     * Multiply by this value for converting bits to bytes.
     */
    public static final double BITS_TO_BYTES = 1.0 / BYTES_TO_BITS;

    /**
     * The number of bytes in a char.
     */
    public static final int BYTES_PER_CHAR = 1;

    /**
     * The number of bytes in a short.
     */
    public static final int BYTES_PER_SHORT = 2;

    /**
     * The number of bytes in an integer.
     */
    public static final int BYTES_PER_INT = 4;

    /**
     * The number of bytes in a long.
     */
    public static final int BYTES_PER_LONG = 8;

    /**
     * Multiply by these values for converting KB to bytes ("kilo" meaning 10^3, "kibi" meaning 2^10).
     */
    public static final int KIBIBYTES_TO_BYTES = 1024;

    public static final int KILOBYTES_TO_BYTES = 1000;
    public static final int KB_TO_BYTES = KIBIBYTES_TO_BYTES;
    public static final double BYTES_TO_KIBIBYTES = 1.0 / KIBIBYTES_TO_BYTES;

    /**
     * Multiply by these values for converting MB to bytes ("mega" meaning 10^6, "mebi" meaning 2^20).
     */
    public static final int MEBIBYTES_TO_BYTES = 1024 * KIBIBYTES_TO_BYTES;

    public static final int MEGABYTES_TO_BYTES = 1000 * KILOBYTES_TO_BYTES;
    public static final int MB_TO_BYTES = MEBIBYTES_TO_BYTES;
    public static final double BYTES_TO_MEBIBYTES = 1.0 / MEBIBYTES_TO_BYTES;
    public static final int MEBIBYTES_TO_KIBIBYTES = 1024;
    public static final double KIBIBYTES_TO_MEBIBYTES = 1.0 / MEBIBYTES_TO_KIBIBYTES;

    /**
     * Multiply by these values for converting GB to bytes ("giga" meaning 10^9, "gibi" meaning 2^30). Unlike KIBI/KILO
     * and MEBI/MEGA, these are defined as longs, because Integer.MAX_VALUE is one less than 2*GIBIBYTES_TO_BYTES, so
     * multiplying by anything larger than 1 is very likely to require a long to hold the result.  (2 GIGABYTES can fit
     * in an int, just not 2 GIBIBYES, but that's entirely a different matter.)
     */
    public static final long GIBIBYTES_TO_BYTES = 1024L * MEBIBYTES_TO_BYTES;

    public static final long GIGABYTES_TO_BYTES = 1000L * MEGABYTES_TO_BYTES;
    public static final long GB_TO_BYTES = GIBIBYTES_TO_BYTES;
    public static final double BYTES_TO_GIBIBYTES = 1.0 / GIBIBYTES_TO_BYTES;
    public static final int GIBIBYTES_TO_KIBIBYTES = 1024 * MEBIBYTES_TO_KIBIBYTES;
    public static final double KIBIBYTES_TO_GIBIBYTES = 1.0 / GIBIBYTES_TO_KIBIBYTES;
    public static final int GIBIBYTES_TO_MEBIBYTES = 1024;
    public static final double MEBIBYTES_TO_GIBIBYTES = 1.0 / GIBIBYTES_TO_MEBIBYTES;
}
