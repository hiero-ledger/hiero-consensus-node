// SPDX-License-Identifier: Apache-2.0
package org.hiero.metrics.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.temporal.ChronoUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class UnitTest {

    @Test
    void testNoUnitToString() {
        assertThat(Unit.NO_UNIT.toString()).isEmpty();
    }

    @Test
    void testFrequencyUnitToString() {
        assertThat(Unit.FREQUENCY_UNIT.toString()).isEqualTo("hz");
    }

    @Test
    void testNanosecondUnitToString() {
        assertThat(Unit.NANOSECOND_UNIT.toString()).isEqualTo("ns");
    }

    @Test
    void testMicrosecondUnitToString() {
        assertThat(Unit.MICROSECOND_UNIT.toString()).isEqualTo("µs");
    }

    @Test
    void testMillisecondUnitToString() {
        assertThat(Unit.MILLISECOND_UNIT.toString()).isEqualTo("ms");
    }

    @Test
    void testSecondUnitToString() {
        assertThat(Unit.SECOND_UNIT.toString()).isEqualTo("s");
    }

    @Test
    void testByteUnitToString() {
        assertThat(Unit.BYTE_UNIT.toString()).isEqualTo("byte");
    }

    @Test
    void testMegabyteUnitToString() {
        assertThat(Unit.MEGABYTE_UNIT.toString()).isEqualTo("mb");
    }

    @Test
    void testFromChronoUnitWithNanos() {
        assertThat(Unit.fromChronoUnit(ChronoUnit.NANOS)).isEqualTo(Unit.NANOSECOND_UNIT);
    }

    @Test
    void testFromChronoUnitWithMicros() {
        assertThat(Unit.fromChronoUnit(ChronoUnit.MICROS)).isEqualTo(Unit.MICROSECOND_UNIT);
    }

    @Test
    void testFromChronoUnitWithMillis() {
        assertThat(Unit.fromChronoUnit(ChronoUnit.MILLIS)).isEqualTo(Unit.MILLISECOND_UNIT);
    }

    @Test
    void testFromChronoUnitWithSeconds() {
        assertThat(Unit.fromChronoUnit(ChronoUnit.SECONDS)).isEqualTo(Unit.SECOND_UNIT);
    }

    @ParameterizedTest
    @EnumSource(
            value = ChronoUnit.class,
            names = {"NANOS", "MICROS", "MILLIS", "SECONDS"},
            mode = EnumSource.Mode.EXCLUDE)
    void testFromChronoUnitWithUnsupportedUnits(ChronoUnit unsupportedUnit) {
        assertThat(Unit.fromChronoUnit(unsupportedUnit)).isEqualTo(Unit.NO_UNIT);
    }

    @Test
    void testFromChronoUnitWithNull() {
        assertThatThrownBy(() -> Unit.fromChronoUnit(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("timeUnit must not be null");
    }
}
