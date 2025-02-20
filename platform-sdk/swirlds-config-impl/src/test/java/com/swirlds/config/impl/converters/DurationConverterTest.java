// SPDX-License-Identifier: Apache-2.0
package com.swirlds.config.impl.converters;

import static com.swirlds.base.units.UnitConstants.DAYS_TO_HOURS;
import static com.swirlds.base.units.UnitConstants.HOURS_TO_MINUTES;
import static com.swirlds.base.units.UnitConstants.MICROSECONDS_TO_NANOSECONDS;
import static com.swirlds.base.units.UnitConstants.MILLISECONDS_TO_NANOSECONDS;
import static com.swirlds.base.units.UnitConstants.MINUTES_TO_SECONDS;
import static com.swirlds.base.units.UnitConstants.SECONDS_TO_NANOSECONDS;
import static com.swirlds.base.units.UnitConstants.WEEKS_TO_DAYS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class DurationConverterTest {

    @Test
    public void convertNull() {
        // given
        final DurationConverter converter = new DurationConverter();

        // then
        Assertions.assertThrows(NullPointerException.class, () -> converter.convert(null));
    }

    private static void testDeserialization(final String str, final Duration expected) {
        final Duration parsed = new DurationConverter().convert(str);
        assertEquals(expected, parsed, "parsed duration from \"" + str + "\" does not match");
    }

    @Test
    @DisplayName("Nanosecond Parsing Tests")
    void nanosecondParsingTests() {

        // Test whitespace
        testDeserialization("0ns", Duration.ofNanos(0));
        testDeserialization("0 ns", Duration.ofNanos(0));
        testDeserialization(" 0 ns", Duration.ofNanos(0));
        testDeserialization(" 0 ns ", Duration.ofNanos(0));
        testDeserialization(" 0    ns ", Duration.ofNanos(0));

        // Test decimals
        testDeserialization("1ns", Duration.ofNanos(1));
        testDeserialization("1.1ns", Duration.ofNanos(1));
        testDeserialization("1.1 ns", Duration.ofNanos(1));
        testDeserialization("1.4ns", Duration.ofNanos(1));
        testDeserialization("1.5ns", Duration.ofNanos(1));
        testDeserialization("1.6ns", Duration.ofNanos(1));
        testDeserialization("1.ns", Duration.ofNanos(1));
        testDeserialization(".1ns", Duration.ofNanos(0));

        // Test large values
        testDeserialization("10ns", Duration.ofNanos(10));
        testDeserialization("100ns", Duration.ofNanos(100));
        testDeserialization("1000ns", Duration.ofNanos(1000));
        testDeserialization("1000ns", Duration.ofNanos(1000));
        testDeserialization("1000000000ns", Duration.ofNanos(1000000000));

        // Test abbreviations
        for (final String unit : List.of("ns", "nano", "nanos", "nanosecond", "nanoseconds", "nanosec", "nanosecs")) {
            testDeserialization("123456 " + unit, Duration.ofNanos(123456));
        }
    }

    @Test
    @DisplayName("Microsecond Parsing Tests")
    void microsecondParsingTests() {

        // Test whitespace
        testDeserialization("0us", Duration.ofNanos(0));
        testDeserialization("0 us", Duration.ofNanos(0));
        testDeserialization(" 0 us", Duration.ofNanos(0));
        testDeserialization(" 0 us ", Duration.ofNanos(0));
        testDeserialization(" 0    us ", Duration.ofNanos(0));

        // Test decimals
        final long factor = MICROSECONDS_TO_NANOSECONDS;
        testDeserialization("1us", Duration.ofNanos(factor));
        testDeserialization("1.1us", Duration.ofNanos((long) (1.1 * factor)));
        testDeserialization("1.1 us", Duration.ofNanos((long) (1.1 * factor)));
        testDeserialization("1.4us", Duration.ofNanos((long) (1.4 * factor)));
        testDeserialization("1.5us", Duration.ofNanos((long) (1.5 * factor)));
        testDeserialization("1.6us", Duration.ofNanos((long) (1.6 * factor)));
        testDeserialization("1.us", Duration.ofNanos(factor));
        testDeserialization(".1us", Duration.ofNanos((long) (0.1 * factor)));

        // Test large values
        testDeserialization("10us", Duration.ofNanos(10 * factor));
        testDeserialization("100us", Duration.ofNanos(100 * factor));
        testDeserialization("1000us", Duration.ofNanos(1000 * factor));
        testDeserialization("1000us", Duration.ofNanos(1000 * factor));
        testDeserialization("100000us", Duration.ofNanos(100000 * factor));

        // Test abbreviations
        for (final String unit :
                List.of("us", "micro", "micros", "microsecond", "microseconds", "microsec", "microsecs")) {
            testDeserialization("123456 " + unit, Duration.ofNanos(123456 * factor));
        }
    }

    @Test
    @DisplayName("Millisecond Parsing Tests")
    void millisecondParsingTests() {

        // Test whitespace
        testDeserialization("0ms", Duration.ofMillis(0));
        testDeserialization("0 ms", Duration.ofMillis(0));
        testDeserialization(" 0 ms", Duration.ofMillis(0));
        testDeserialization(" 0 ms ", Duration.ofMillis(0));
        testDeserialization(" 0    ms ", Duration.ofMillis(0));

        // Test decimals
        final long factor = MILLISECONDS_TO_NANOSECONDS;
        testDeserialization("1ms", Duration.ofNanos(factor));
        testDeserialization("1.1ms", Duration.ofNanos((long) (1.1 * factor)));
        testDeserialization("1.1 ms", Duration.ofNanos((long) (1.1 * factor)));
        testDeserialization("1.4ms", Duration.ofNanos((long) (1.4 * factor)));
        testDeserialization("1.5ms", Duration.ofNanos((long) (1.5 * factor)));
        testDeserialization("1.6ms", Duration.ofNanos((long) (1.6 * factor)));
        testDeserialization("1.ms", Duration.ofNanos(factor));
        testDeserialization(".1ms", Duration.ofNanos((long) (0.1 * factor)));

        // Test large values
        testDeserialization("10ms", Duration.ofMillis(10));
        testDeserialization("100ms", Duration.ofMillis(100));
        testDeserialization("1000ms", Duration.ofMillis(1000));
        testDeserialization("1000ms", Duration.ofMillis(1000));
        testDeserialization("1000000000ms", Duration.ofMillis(1000000000));

        // Test abbreviations
        for (final String unit :
                List.of("ms", "milli", "millis", "millisecond", "milliseconds", "millisec", "millisecs")) {
            testDeserialization("123456 " + unit, Duration.ofMillis(123456));
        }
    }

    @Test
    @DisplayName("Second Parsing Tests")
    void secondParsingTests() {

        // Test whitespace
        testDeserialization("0s", Duration.ofSeconds(0));
        testDeserialization("0 s", Duration.ofSeconds(0));
        testDeserialization(" 0 s", Duration.ofSeconds(0));
        testDeserialization(" 0 s ", Duration.ofSeconds(0));
        testDeserialization(" 0    s ", Duration.ofSeconds(0));

        // Test decimals
        final long factor = SECONDS_TO_NANOSECONDS;
        testDeserialization("1s", Duration.ofNanos(factor));
        testDeserialization("1.1s", Duration.ofNanos((long) (1.1 * factor)));
        testDeserialization("1.1 s", Duration.ofNanos((long) (1.1 * factor)));
        testDeserialization("1.4s", Duration.ofNanos((long) (1.4 * factor)));
        testDeserialization("1.5s", Duration.ofNanos((long) (1.5 * factor)));
        testDeserialization("1.6s", Duration.ofNanos((long) (1.6 * factor)));
        testDeserialization("1.s", Duration.ofNanos(factor));
        testDeserialization(".1s", Duration.ofNanos((long) (0.1 * factor)));

        // Test large values
        testDeserialization("10s", Duration.ofSeconds(10));
        testDeserialization("100s", Duration.ofSeconds(100));
        testDeserialization("1000s", Duration.ofSeconds(1000));
        testDeserialization("1000s", Duration.ofSeconds(1000));
        testDeserialization("1000000000s", Duration.ofSeconds(1000000000));

        // Test abbreviations
        for (final String unit : List.of("s", "sec", "secs", "second", "seconds")) {
            testDeserialization("123456 " + unit, Duration.ofSeconds(123456));
        }
    }

    @Test
    @DisplayName("Minute Parsing Tests")
    void minuteParsingTests() {

        // Test whitespace
        testDeserialization("0m", Duration.ofMinutes(0));
        testDeserialization("0 m", Duration.ofMinutes(0));
        testDeserialization(" 0 m", Duration.ofMinutes(0));
        testDeserialization(" 0 m ", Duration.ofMinutes(0));
        testDeserialization(" 0    m ", Duration.ofMinutes(0));

        // Test decimals
        final long factor = (long) MINUTES_TO_SECONDS * SECONDS_TO_NANOSECONDS;
        testDeserialization("1m", Duration.ofNanos(factor));
        testDeserialization("1.1m", Duration.ofNanos((long) (1.1 * factor)));
        testDeserialization("1.1 m", Duration.ofNanos((long) (1.1 * factor)));
        testDeserialization("1.4m", Duration.ofNanos((long) (1.4 * factor)));
        testDeserialization("1.5m", Duration.ofNanos((long) (1.5 * factor)));
        testDeserialization("1.6m", Duration.ofNanos((long) (1.6 * factor)));
        testDeserialization("1.m", Duration.ofNanos(factor));
        testDeserialization(".1m", Duration.ofNanos((long) (0.1 * factor)));

        // Test large values
        testDeserialization("10m", Duration.ofMinutes(10));
        testDeserialization("100m", Duration.ofMinutes(100));
        testDeserialization("1000m", Duration.ofMinutes(1000));
        testDeserialization("1000m", Duration.ofMinutes(1000));
        testDeserialization("100000m", Duration.ofMinutes(100000));

        // Test abbreviations
        for (final String unit : List.of("m", "min", "mins", "minute", "minutes")) {
            testDeserialization("123456 " + unit, Duration.ofMinutes(123456));
        }
    }

    @Test
    @DisplayName("Hour Parsing Tests")
    void hourParsingTests() {

        // Test whitespace
        testDeserialization("0h", Duration.ofHours(0));
        testDeserialization("0 h", Duration.ofHours(0));
        testDeserialization(" 0 h", Duration.ofHours(0));
        testDeserialization(" 0 h ", Duration.ofHours(0));
        testDeserialization(" 0    h ", Duration.ofHours(0));

        // Test decimals
        final long factor = (long) HOURS_TO_MINUTES * MINUTES_TO_SECONDS * SECONDS_TO_NANOSECONDS;
        testDeserialization("1h", Duration.ofNanos(factor));
        testDeserialization("1.1h", Duration.ofNanos((long) (1.1 * factor)));
        testDeserialization("1.1 h", Duration.ofNanos((long) (1.1 * factor)));
        testDeserialization("1.4h", Duration.ofNanos((long) (1.4 * factor)));
        testDeserialization("1.5h", Duration.ofNanos((long) (1.5 * factor)));
        testDeserialization("1.6h", Duration.ofNanos((long) (1.6 * factor)));
        testDeserialization("1.h", Duration.ofNanos(factor));
        testDeserialization(".1h", Duration.ofNanos((long) (0.1 * factor)));

        // Test large values
        testDeserialization("10h", Duration.ofHours(10));
        testDeserialization("100h", Duration.ofHours(100));
        testDeserialization("1000h", Duration.ofHours(1000));
        testDeserialization("1000h", Duration.ofHours(1000));
        testDeserialization("100000h", Duration.ofHours(100000));

        // Test abbreviations
        for (final String unit : List.of("h", "hour", "hours")) {
            testDeserialization("123456 " + unit, Duration.ofHours(123456));
        }
    }

    @Test
    @DisplayName("Day Parsing Tests")
    void dayParsingTests() {

        // Test whitespace
        testDeserialization("0d", Duration.ofDays(0));
        testDeserialization("0 d", Duration.ofDays(0));
        testDeserialization(" 0 d", Duration.ofDays(0));
        testDeserialization(" 0 d ", Duration.ofDays(0));
        testDeserialization(" 0    d ", Duration.ofDays(0));

        // Test decimals
        final long factor = (long) DAYS_TO_HOURS * HOURS_TO_MINUTES * MINUTES_TO_SECONDS * SECONDS_TO_NANOSECONDS;
        testDeserialization("1d", Duration.ofNanos(factor));
        testDeserialization("1.1d", Duration.ofNanos((long) (1.1 * factor)));
        testDeserialization("1.1 d", Duration.ofNanos((long) (1.1 * factor)));
        testDeserialization("1.4d", Duration.ofNanos((long) (1.4 * factor)));
        testDeserialization("1.5d", Duration.ofNanos((long) (1.5 * factor)));
        testDeserialization("1.6d", Duration.ofNanos((long) (1.6 * factor)));
        testDeserialization("1.d", Duration.ofNanos(factor));
        testDeserialization(".1d", Duration.ofNanos((long) (0.1 * factor)));

        // Test large values
        testDeserialization("10d", Duration.ofDays(10));
        testDeserialization("100d", Duration.ofDays(100));
        testDeserialization("1000d", Duration.ofDays(1000));
        testDeserialization("1000d", Duration.ofDays(1000));
        testDeserialization("100000d", Duration.ofDays(100000));

        // Test abbreviations
        for (final String unit : List.of("d", "day", "days")) {
            testDeserialization("12345 " + unit, Duration.ofDays(12345));
        }
    }

    @Test
    @DisplayName("Week Parsing Tests")
    void weekParsingTests() {

        // Test whitespace
        testDeserialization("0w", Duration.ofDays(0));
        testDeserialization("0 w", Duration.ofDays(0));
        testDeserialization(" 0 w", Duration.ofDays(0));
        testDeserialization(" 0 w ", Duration.ofDays(0));
        testDeserialization(" 0    W ", Duration.ofDays(0));

        // Test decimals
        final long factor =
                (long) WEEKS_TO_DAYS * DAYS_TO_HOURS * HOURS_TO_MINUTES * MINUTES_TO_SECONDS * SECONDS_TO_NANOSECONDS;
        testDeserialization("1w", Duration.ofNanos(factor));
        testDeserialization("1.1w", Duration.ofNanos((long) (1.1 * factor)));
        testDeserialization("1.1 w", Duration.ofNanos((long) (1.1 * factor)));
        testDeserialization("1.4w", Duration.ofNanos((long) (1.4 * factor)));
        testDeserialization("1.5w", Duration.ofNanos((long) (1.5 * factor)));
        testDeserialization("1.6w", Duration.ofNanos((long) (1.6 * factor)));
        testDeserialization("1.w", Duration.ofNanos(factor));
        testDeserialization(".1w", Duration.ofNanos((long) (0.1 * factor)));

        // Test large values
        testDeserialization("10w", Duration.ofDays(10 * 7));
        testDeserialization("100w", Duration.ofDays(100 * 7));
        testDeserialization("1000w", Duration.ofDays(1000 * 7));
        testDeserialization("1000w", Duration.ofDays(1000 * 7));
        testDeserialization("10000w", Duration.ofDays(10000 * 7));

        // Test abbreviations
        for (final String unit : List.of("w", "week", "weeks")) {
            testDeserialization("12345 " + unit, Duration.ofDays(12345 * 7));
        }
    }

    @Test
    @DisplayName("Default Parsing Tests")
    void defaultParsingTests() {
        testDeserialization(Duration.ofNanos(1234).toString(), Duration.ofNanos(1234));
        testDeserialization(Duration.ofMillis(1234).toString(), Duration.ofMillis(1234));
        testDeserialization(Duration.ofSeconds(1234).toString(), Duration.ofSeconds(1234));
        testDeserialization(Duration.ofMinutes(1234).toString(), Duration.ofMinutes(1234));
        testDeserialization(Duration.ofHours(1234).toString(), Duration.ofHours(1234));
        testDeserialization(Duration.ofDays(1234).toString(), Duration.ofDays(1234));
    }

    @Test
    @DisplayName("Large Number Tests")
    void largeNumberTests() {

        final long seconds = Long.MAX_VALUE - 1;

        final Duration parsed = new DurationConverter().convert(seconds + ".5 seconds");
        final Duration expected = Duration.ofSeconds(seconds, (long) (0.5 * SECONDS_TO_NANOSECONDS));

        final long delta = parsed.getSeconds() - expected.getSeconds();

        assertTrue(delta <= 1 && delta >= -1, "values should be closer together");
    }
}
