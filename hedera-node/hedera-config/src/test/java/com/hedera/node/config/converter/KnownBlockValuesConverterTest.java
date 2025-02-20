// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.config.converter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.hedera.node.app.hapi.utils.sysfiles.domain.KnownBlockValues;
import org.junit.jupiter.api.Test;

class KnownBlockValuesConverterTest {

    @Test
    void testNullValue() {
        // given
        final KnownBlockValuesConverter converter = new KnownBlockValuesConverter();

        // then
        assertThatThrownBy(() -> converter.convert(null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void testValidValue() {
        // given
        final KnownBlockValuesConverter converter = new KnownBlockValuesConverter();
        final String input = "c9e37a7a454638ca62662bd1a06de49ef40b3444203fe329bbc81363604ea7f8@666";

        // when
        final KnownBlockValues knownBlockValues = converter.convert(input);

        // then
        assertThat(knownBlockValues).isNotNull();
        assertThat(knownBlockValues.number()).isEqualTo(666L);
        assertThat(knownBlockValues.hash())
                .asHexString()
                .isEqualTo("C9E37A7A454638CA62662BD1A06DE49EF40B3444203FE329BBC81363604EA7F8");
    }

    @Test
    void testInvalidValue() {
        // given
        final KnownBlockValuesConverter converter = new KnownBlockValuesConverter();

        // then
        assertThatThrownBy(() -> converter.convert("null")).isInstanceOf(IllegalArgumentException.class);
    }
}
