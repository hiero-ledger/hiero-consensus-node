// SPDX-License-Identifier: Apache-2.0
package com.swirlds.logging.api.internal.format;

import static java.time.ZoneOffset.UTC;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.temporal.TemporalAccessor;
import java.util.Random;
import java.util.concurrent.ThreadLocalRandom;
import org.junit.jupiter.api.Test;

class EpochCachedFormatterTest {

    private static final DateTimeFormatter DATE_TIME_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS").withZone(UTC);
    private static final int A_SECOND = 1000;
    private static final int A_MINUTE = 60 * A_SECOND;
    private static final int AN_HOUR = 60 * A_MINUTE;
    private static final int A_DAY = 24 * AN_HOUR;

    @Test
    void testExactCache() {
        EpochCachedFormatter formatter = new EpochCachedFormatter();
        final String expectedDate = "2020-08-26 12:34:56.789";
        long epochMillis = epochFromString(expectedDate);
        String cached = formatter.format(epochMillis);
        assertEquals(expectedDate, cached);
    }

    @Test
    void testCaches() {
        EpochCachedFormatter formatter = new EpochCachedFormatter();
        final String date = "2020-08-26 12:00:00.000";
        final long dateEpoch = epochFromString(date);
        formatter.format(dateEpoch); // Just so it caches the date

        assertEquals(date, formatter.format(dateEpoch)); // Exact match comes from exact cache
        assertEquals(
                "2020-08-26 12:00:01.000",
                formatter.format(dateEpoch + A_SECOND)); // one second after, uses the minutes cache
        assertEquals(
                "2020-08-26 12:00:01.000",
                formatter.format(dateEpoch + A_SECOND)); // this one should come from exact cache
        assertEquals(
                "2020-08-26 12:01:00.000",
                formatter.format(dateEpoch + A_MINUTE)); // one minute after, uses the hours cache
        assertEquals(
                "2020-08-26 12:01:00.000",
                formatter.format(dateEpoch + A_MINUTE)); // this one should come from exact cache
        assertEquals(
                "2020-08-26 13:00:00.000", formatter.format(dateEpoch + AN_HOUR)); // one hour after, uses the day cache
        assertEquals(
                "2020-08-26 13:00:00.000",
                formatter.format(dateEpoch + AN_HOUR)); // this one should come from exact cache
        assertEquals(
                "2020-08-26 20:12:34.312",
                formatter.format(dateEpoch + 8 * AN_HOUR + 12 * A_MINUTE + 34 * A_SECOND + 312));
        assertEquals(
                "2020-08-26 20:12:34.312",
                formatter.format(dateEpoch
                        + 8 * AN_HOUR
                        + 12 * A_MINUTE
                        + 34 * A_SECOND
                        + 312)); // this one should come from exact cache
    }

    @Test
    void testRandomlyParsesData() {
        EpochCachedFormatter formatter = new EpochCachedFormatter();
        for (int i = 0; i < 2000000; i++) {
            final long epochMillis = generateRandomEpoch();
            final String expected = stringFromEpoch(epochMillis);
            final String formatted = formatter.format(epochMillis);
            assertEquals(
                    expected,
                    formatted,
                    "parsing random epoch %d did not match expected value %s: %s"
                            .formatted(epochMillis, expected, formatted));
        }
    }

    private static String stringFromEpoch(final long epochMillis) {
        return DATE_TIME_FORMATTER.format(Instant.ofEpochMilli(epochMillis));
    }

    private static long epochFromString(final String expectedDate) {
        final TemporalAccessor parse = DATE_TIME_FORMATTER.parse(expectedDate);
        return Instant.from(parse).toEpochMilli();
    }

    private static long generateRandomEpoch() {
        final ThreadLocalRandom random = ThreadLocalRandom.current();
        int year = getRandomNumberBetween(1900, 2300, random);
        int month = getRandomNumberBetween(1, 12, random);
        int day = getRandomNumberBetween(1, LocalDate.of(year, month, 1).lengthOfMonth(), random);
        long elapsedSinceMidnightMillis = System.currentTimeMillis() % A_DAY;
        return LocalDate.of(year, month, day).toEpochDay() + elapsedSinceMidnightMillis;
    }

    public static int getRandomNumberBetween(int min, int max, Random random) {
        return random.nextInt(max - min + 1) + min;
    }
}
