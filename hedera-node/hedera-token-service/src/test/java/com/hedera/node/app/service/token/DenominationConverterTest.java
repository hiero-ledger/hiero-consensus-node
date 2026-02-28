// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.token;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class DenominationConverterTest {

    @Test
    void testSubunitsPerWholeUnitAtSixDecimals() {
        final var converter = new DenominationConverter(6);
        assertThat(converter.subunitsPerWholeUnit()).isEqualTo(1_000_000L);
    }

    @Test
    void testDecimalsAccessor() {
        final var converter = new DenominationConverter(6);
        assertThat(converter.decimals()).isEqualTo(6);
    }

    @ParameterizedTest
    @CsvSource({"0, 1", "1, 10", "8, 100000000", "17, 100000000000000000", "18, 1000000000000000000"})
    void testSubunitsPerWholeUnitAtBoundaryDecimals(final int decimals, final long expected) {
        final var converter = new DenominationConverter(decimals);
        assertThat(converter.subunitsPerWholeUnit()).isEqualTo(expected);
    }

    @Test
    void testRoundToWholeUnit() {
        final var converter = new DenominationConverter(6);
        assertThat(converter.roundToWholeUnit(1_500_123L)).isEqualTo(1_000_000L);
    }

    @Test
    void testRoundToWholeUnitExactMultiple() {
        final var converter = new DenominationConverter(6);
        assertThat(converter.roundToWholeUnit(2_000_000L)).isEqualTo(2_000_000L);
    }

    @Test
    void testRoundToWholeUnitZero() {
        final var converter = new DenominationConverter(6);
        assertThat(converter.roundToWholeUnit(0L)).isEqualTo(0L);
    }

    @Test
    void testConstructorRejectsTooHigh() {
        assertThatThrownBy(() -> new DenominationConverter(19)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void testConstructorRejectsTooLow() {
        assertThatThrownBy(() -> new DenominationConverter(-1)).isInstanceOf(IllegalArgumentException.class);
    }
}
