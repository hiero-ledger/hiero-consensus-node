// SPDX-License-Identifier: Apache-2.0
package com.swirlds.config.impl.converters;

import java.math.BigDecimal;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class BigDecimalConverterTest {

    @Test
    public void convertNull() {
        // given
        final BigDecimalConverter converter = new BigDecimalConverter();

        // then
        Assertions.assertThrows(NullPointerException.class, () -> converter.convert(null));
    }

    @Test
    public void convert() {
        // given
        final BigDecimalConverter converter = new BigDecimalConverter();
        final String rawValue = "1.2";

        // when
        final BigDecimal value = converter.convert(rawValue);

        // then
        Assertions.assertNotNull(value);
        Assertions.assertEquals("1.2", value.toString());
    }
}
