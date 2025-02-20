// SPDX-License-Identifier: Apache-2.0
package com.swirlds.config.impl.converters;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class LongConverterTest {

    @Test
    public void convertNull() {
        // given
        final LongConverter converter = new LongConverter();

        // then
        Assertions.assertThrows(
                NullPointerException.class, () -> converter.convert(null), "Null values must always throw a NPE");
    }

    @Test
    public void convertMin() {
        // given
        final LongConverter converter = new LongConverter();
        final String rawValue = Long.MIN_VALUE + "";

        // when
        final long value = converter.convert(rawValue);

        // then
        Assertions.assertEquals(Long.MIN_VALUE, value, "All valid long values must be supported");
    }

    @Test
    public void convertMax() {
        // given
        final LongConverter converter = new LongConverter();
        final String rawValue = Long.MAX_VALUE + "";

        // when
        final long value = converter.convert(rawValue);

        // then
        Assertions.assertEquals(Long.MAX_VALUE, value, "All valid long values must be supported");
    }

    @Test
    public void convertZero() {
        // given
        final LongConverter converter = new LongConverter();
        final String rawValue = "0";

        // when
        final long value = converter.convert(rawValue);

        // then
        Assertions.assertEquals(0, value, "All valid long values must be supported");
    }

    @Test
    public void convertPositiveNumber() {
        // given
        final LongConverter converter = new LongConverter();
        final String rawValue = "21";

        // when
        final long value = converter.convert(rawValue);

        // then
        Assertions.assertEquals(21L, value, "All valid long values must be supported");
    }

    @Test
    public void convertNegativeNumber() {
        // given
        final LongConverter converter = new LongConverter();
        final String rawValue = "-7";

        // when
        final long value = converter.convert(rawValue);

        // then
        Assertions.assertEquals(-7L, value, "All valid long values must be supported");
    }

    @Test
    public void convertOutOfRangeNumber() {
        // given
        final LongConverter converter = new LongConverter();
        final String rawValue = Long.MAX_VALUE + "0";

        // then
        Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> converter.convert(rawValue),
                "Only the long value range must be supported");
    }

    @Test
    public void convertInvalid() {
        // given
        final LongConverter converter = new LongConverter();
        final String rawValue = "1.23";

        // then
        Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> converter.convert(rawValue),
                "Only valid long values must be supported");
    }

    @Test
    public void convertNumericalLiteral() {
        // given
        final LongConverter converter = new LongConverter();
        final String rawValue = "21_000";

        // when
        final long value = converter.convert(rawValue);

        // then
        Assertions.assertEquals(21000L, value, "Numerical literals must be supported");
    }

    @Test
    public void convertNumericalLiteralNegative() {
        // given
        final LongConverter converter = new LongConverter();
        final String rawValue = "-781_000";

        // when
        final long value = converter.convert(rawValue);

        // then
        Assertions.assertEquals(-781000L, value, "Negative numerical literals must be supported");
    }
}
