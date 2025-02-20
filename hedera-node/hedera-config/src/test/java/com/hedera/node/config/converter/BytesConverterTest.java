// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.config.converter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;

import com.hedera.pbj.runtime.io.buffer.Bytes;
import java.io.IOException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class BytesConverterTest {

    @Test
    void testNull() {
        // given
        final BytesConverter bytesConverter = new BytesConverter();

        // then
        assertThatThrownBy(() -> bytesConverter.convert(null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void testConversionResult() throws IOException {
        // given
        final BytesConverter bytesConverter = new BytesConverter();
        final String value = "0x1234567890abcdef";
        final byte[] expected = new byte[] {0x12, 0x34, 0x56, 0x78, (byte) 0x90, (byte) 0xab, (byte) 0xcd, (byte) 0xef};

        // when
        final byte[] bytes = bytesConverter.convert(value).toInputStream().readAllBytes();

        // then
        assertArrayEquals(expected, bytes);
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "",
                "0x",
                "0xaa",
                "0x1234",
                "0x111111111111111111111111",
                "0x1234567890abcdef1234567890abcdef1234567890abcdef1234567890abcdef"
            })
    void testValueValues(final String value) {
        // given
        final BytesConverter bytesConverter = new BytesConverter();

        // when
        final Bytes bytes = bytesConverter.convert(value);

        // then
        assertThat(bytes).isNotNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {" ", "  ", "1", "123", "abc", "0xu", "0x1", "0x123", "0x12345", " 0xaa", "0xaa "})
    void testInvalidValue(final String value) {
        // given
        final BytesConverter bytesConverter = new BytesConverter();

        // then
        assertThatThrownBy(() -> bytesConverter.convert(value)).isInstanceOf(IllegalArgumentException.class);
    }
}
