// SPDX-License-Identifier: Apache-2.0
package com.swirlds.common.utility;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class InstantUtilsTest {

    @Test
    void instantToMicrosBasic() {
        final Instant instant = Instant.ofEpochSecond(1, 500_000_000); // 1 second + 500ms
        final long micros = InstantUtils.instantToMicros(instant);
        assertEquals(1_500_000L, micros);
    }

    @Test
    void instantToMicrosEpoch() {
        final Instant epoch = Instant.EPOCH;
        assertEquals(0L, InstantUtils.instantToMicros(epoch));
    }

    @Test
    void instantToMicrosWithNanos() {
        // 2 seconds + 123456 microseconds (123456000 nanos)
        final Instant instant = Instant.ofEpochSecond(2, 123_456_000);
        final long micros = InstantUtils.instantToMicros(instant);
        assertEquals(2_123_456L, micros);
    }

    @Test
    void microsToInstantBasic() {
        final long micros = 1_500_000L; // 1.5 seconds
        final Instant instant = InstantUtils.microsToInstant(micros);
        assertEquals(1L, instant.getEpochSecond());
        assertEquals(500_000_000, instant.getNano());
    }

    @Test
    void microsToInstantZero() {
        final Instant instant = InstantUtils.microsToInstant(0L);
        assertEquals(Instant.EPOCH, instant);
    }

    @Test
    void microsToInstantWithMicros() {
        final long micros = 2_123_456L; // 2 seconds + 123456 microseconds
        final Instant instant = InstantUtils.microsToInstant(micros);
        assertEquals(2L, instant.getEpochSecond());
        assertEquals(123_456_000, instant.getNano());
    }

    @Test
    void roundTripConversion() {
        final Instant original = Instant.ofEpochSecond(12345, 678_000_000); // microsecond precision
        final long micros = InstantUtils.instantToMicros(original);
        final Instant recovered = InstantUtils.microsToInstant(micros);
        assertEquals(original, recovered);
    }

    @Test
    void roundTripConversionFromMicros() {
        final long originalMicros = 9_876_543_210L;
        final Instant instant = InstantUtils.microsToInstant(originalMicros);
        final long recoveredMicros = InstantUtils.instantToMicros(instant);
        assertEquals(originalMicros, recoveredMicros);
    }
}
