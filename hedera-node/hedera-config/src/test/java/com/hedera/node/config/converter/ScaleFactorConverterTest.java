// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.config.converter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.hedera.node.app.hapi.utils.sysfiles.domain.throttling.ScaleFactor;
import org.junit.jupiter.api.Test;

class ScaleFactorConverterTest {

    @Test
    void testNullValue() {
        // given
        final ScaleFactorConverter converter = new ScaleFactorConverter();

        // then
        assertThatThrownBy(() -> converter.convert(null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void testValidValue() {
        // given
        final ScaleFactorConverter converter = new ScaleFactorConverter();
        final String input = "1:3";

        // when
        final ScaleFactor scaleFactor = converter.convert(input);

        // then
        assertThat(scaleFactor).isNotNull();
        assertThat(scaleFactor.numerator()).isEqualTo(1);
        assertThat(scaleFactor.denominator()).isEqualTo(3);
    }

    @Test
    void testInvalidValue() {
        // given
        final ScaleFactorConverter converter = new ScaleFactorConverter();

        // then
        assertThatThrownBy(() -> converter.convert("null")).isInstanceOf(IllegalArgumentException.class);
    }
}
