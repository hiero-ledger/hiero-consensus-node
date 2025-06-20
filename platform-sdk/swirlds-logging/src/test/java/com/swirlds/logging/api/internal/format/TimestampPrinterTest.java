// SPDX-License-Identifier: Apache-2.0
package com.swirlds.logging.api.internal.format;

import static java.time.ZoneOffset.UTC;
import static org.assertj.core.api.Assertions.assertThat;

import com.swirlds.base.test.fixtures.io.WithSystemError;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import org.junit.jupiter.api.Test;

@WithSystemError
public class TimestampPrinterTest {

    @Test
    void testPrint() {
        // given
        long timestamp = Instant.parse("2024-03-06T12:00:00Z").toEpochMilli();

        // when
        final StringBuilder sb = new StringBuilder();
        TimestampPrinter.print(sb, timestamp);

        String expectedTimestamp = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS   ")
                .withZone(UTC)
                .format(Instant.ofEpochMilli(timestamp));

        // then
        assertThat(sb.toString().length()).isEqualTo(26);
        assertThat(sb.toString()).isEqualTo(expectedTimestamp);
    }

    @Test
    void testTimestampAsStringWithNegativeTimestamp() {
        // given
        long timestamp = -1; // Negative timestamp

        // when
        final StringBuilder sb = new StringBuilder();
        TimestampPrinter.print(sb, timestamp);

        // then
        assertThat(sb.toString()).isEqualTo("1969-12-31 23:59:59.999   ");
    }

    @Test
    void testNegativeTimestampOverflowed26() {
        // given
        final StringBuilder sb = new StringBuilder();
        TimestampPrinter.print(sb, Long.MIN_VALUE);
        // then
        assertThat(sb.toString()).isEqualTo("BROKEN-TIMESTAMP          ");
    }

    @Test
    void testPositiveTimestampOverflowed26() {
        // given
        final StringBuilder sb = new StringBuilder();
        TimestampPrinter.print(sb, Long.MAX_VALUE);
        // then
        assertThat(sb.toString()).isEqualTo("BROKEN-TIMESTAMP          ");
    }
}
