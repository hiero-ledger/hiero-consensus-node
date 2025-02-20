// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.config.converter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.hedera.node.config.types.CongestionMultipliers;
import org.junit.jupiter.api.Test;

class CongestionMultipliersConverterTest {

    @Test
    void testNullValue() {
        // given
        final CongestionMultipliersConverter converter = new CongestionMultipliersConverter();

        // then
        assertThatThrownBy(() -> converter.convert(null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void testValidValue() {
        // given
        final CongestionMultipliersConverter converter = new CongestionMultipliersConverter();
        final String input = "90,10x,95,25x,99,100x";

        // when
        final CongestionMultipliers multipliers = converter.convert(input);

        // then
        assertThat(multipliers).isNotNull();
        assertThat(multipliers.usagePercentTriggers()).containsExactly(90, 95, 99);
        assertThat(multipliers.multipliers()).containsExactly(10L, 25L, 100L);
    }

    @Test
    void testInvalidValue() {
        // given
        final CongestionMultipliersConverter converter = new CongestionMultipliersConverter();

        // then
        assertThatThrownBy(() -> converter.convert("null")).isInstanceOf(IllegalArgumentException.class);
    }
}
