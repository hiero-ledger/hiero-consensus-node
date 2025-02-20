// SPDX-License-Identifier: Apache-2.0
package com.swirlds.base.time;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import org.junit.jupiter.api.Test;

class NanoClockTest {

    @Test
    void instantReturnsExpectedValue() {
        // given
        NanoClock nanoClock = new NanoClock();

        // when
        Instant initialInstant = nanoClock.instant();
        Instant laterInstant = nanoClock.instant();

        // then
        assertNotEquals(initialInstant, laterInstant);
        assertTrue(initialInstant.isBefore(laterInstant));
    }

    @Test
    void getZoneReturnsSameZoneAsWrappedClock() {
        // given
        Clock systemClock = Clock.systemUTC();
        NanoClock nanoClock = new NanoClock(systemClock);

        // when
        ZoneId nanoClockZone = nanoClock.getZone();
        ZoneId systemClockZone = systemClock.getZone();

        // then
        assertEquals(systemClockZone, nanoClockZone);
    }

    @Test
    void withZoneCreatesNewNanoClockWithCorrectZone() {
        // given
        NanoClock nanoClock = new NanoClock();
        ZoneId newZone = ZoneId.of("America/New_York");

        // when
        Clock newClock = nanoClock.withZone(newZone);

        // then
        assertEquals(newZone, newClock.getZone());
    }

    @Test
    void instantReturnsExpectedValueWhenClockProvided() {
        // given
        Instant initialInstant = Instant.parse("2022-01-01T00:00:00Z");
        Clock fixedClock = Clock.fixed(initialInstant, ZoneId.systemDefault());
        NanoClock nanoClock = new NanoClock(fixedClock);

        // when
        Instant laterInstant = nanoClock.instant();

        // then
        assertEquals(initialInstant, laterInstant.minus(laterInstant.getNano(), ChronoUnit.NANOS));
    }

    @Test
    void shouldThrowNullOnNullClockParameter() {
        // given
        Clock aClock = null;

        // then
        assertThrows(NullPointerException.class, () -> new NanoClock(aClock));
    }

    @Test
    void shouldThrowNullOnNullZoneParameter() {
        // given
        Clock aClock = new NanoClock();

        // then
        assertThrows(NullPointerException.class, () -> aClock.withZone(null));
    }

    @Test
    void shouldReturnNotNullInstant() {
        // given
        Clock aClock = new NanoClock();

        // then
        assertNotNull(aClock.instant());
    }

    @Test
    void shouldReturnNotNullZone() {
        // given
        Clock aClock = new NanoClock();

        // then
        assertNotNull(aClock.getZone());
    }

    @Test
    void shouldReturnNotNullClock() {
        // given
        Clock aClock = new NanoClock();

        // then
        assertNotNull(aClock.withZone(ZoneOffset.UTC));
    }
}
