// SPDX-License-Identifier: Apache-2.0
package com.swirlds.config.impl.converters;

import java.math.BigInteger;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class BigIntegerConverterTest {

    @Test
    public void convertNull() {
        // given
        final BigIntegerConverter converter = new BigIntegerConverter();

        // then
        Assertions.assertThrows(NullPointerException.class, () -> converter.convert(null));
    }

    @Test
    public void convert() {
        // given
        final BigIntegerConverter converter = new BigIntegerConverter();
        final String rawValue = "12";

        // when
        final BigInteger value = converter.convert(rawValue);

        // then
        Assertions.assertNotNull(value);
        Assertions.assertEquals("12", value.toString());
    }
}
