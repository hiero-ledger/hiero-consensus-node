// SPDX-License-Identifier: Apache-2.0
package com.swirlds.config.impl.converters;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class ShortConverterTest {

    @Test
    public void convertNull() {
        // given
        final ShortConverter converter = new ShortConverter();

        // then
        Assertions.assertThrows(NullPointerException.class, () -> converter.convert(null));
    }

    @Test
    public void convert() {
        // given
        final ShortConverter converter = new ShortConverter();
        final String rawValue = "2";

        // when
        final short value = converter.convert(rawValue);

        // then
        Assertions.assertEquals((short) 2, value);
    }
}
