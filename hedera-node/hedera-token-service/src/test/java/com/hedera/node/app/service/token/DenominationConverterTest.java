// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.token;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** Tests for {@link DenominationConverter}. */
class DenominationConverterTest {

    @Test
    void accessorsReturnConfiguredValues() {
        final var converter = new DenominationConverter(6);
        assertThat(converter.decimals()).isEqualTo(6);
        assertThat(converter.subunitsPerWholeUnit()).isEqualTo(1_000_000L);
    }

    @ParameterizedTest
    @CsvSource({"0, 1", "1, 10", "8, 100000000", "17, 100000000000000000", "18, 1000000000000000000"})
    void subunitsPerWholeUnitAtBoundaryDecimals(final int decimals, final long expected) {
        final var converter = new DenominationConverter(decimals);
        assertThat(converter.subunitsPerWholeUnit()).isEqualTo(expected);
    }

    @Test
    void roundToWholeUnitVariousInputs() {
        final var converter = new DenominationConverter(6);
        assertThat(converter.roundToWholeUnit(1_500_123L)).isEqualTo(1_000_000L);
        assertThat(converter.roundToWholeUnit(2_000_000L)).isEqualTo(2_000_000L);
        assertThat(converter.roundToWholeUnit(0L)).isEqualTo(0L);

        final var zeroDecimalsConverter = new DenominationConverter(0);
        assertThat(zeroDecimalsConverter.roundToWholeUnit(42L)).isEqualTo(42L);
    }

    @Test
    void roundToWholeUnitRejectsNegative() {
        final var converter = new DenominationConverter(6);
        assertThatThrownBy(() -> converter.roundToWholeUnit(-1L)).isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @CsvSource({"0, 1000000000000000000", "6, 1000000000000", "8, 10000000000", "18, 1"})
    void weibarsPerSubunitAtVariousDecimals(final int decimals, final String expectedStr) {
        final var converter = new DenominationConverter(decimals);
        assertThat(converter.weibarsPerSubunit()).isEqualTo(new BigInteger(expectedStr));
    }

    @Test
    void weibarsPerSubunitAtDefaultDecimalsMatchesLegacyConstant() {
        final var converter = new DenominationConverter(8);
        assertThat(converter.weibarsPerSubunit()).isEqualTo(BigInteger.valueOf(10_000_000_000L));
    }

    @Test
    void constructorRejectsOutOfRangeDecimals() {
        assertThatThrownBy(() -> new DenominationConverter(19)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new DenominationConverter(-1)).isInstanceOf(IllegalArgumentException.class);
    }
}
