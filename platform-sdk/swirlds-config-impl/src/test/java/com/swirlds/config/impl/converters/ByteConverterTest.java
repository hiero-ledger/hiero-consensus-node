// SPDX-License-Identifier: Apache-2.0
package com.swirlds.config.impl.converters;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class ByteConverterTest {

    @Test
    public void convertNull() {
        // given
        final ByteConverter converter = new ByteConverter();

        // then
        Assertions.assertThrows(NullPointerException.class, () -> converter.convert(null));
    }

    @Test
    public void convert() {
        // given
        final ByteConverter converter = new ByteConverter();
        final String rawValue = "2";

        // when
        final byte value = converter.convert(rawValue);

        // then
        Assertions.assertEquals((byte) 2, value);
    }
}
