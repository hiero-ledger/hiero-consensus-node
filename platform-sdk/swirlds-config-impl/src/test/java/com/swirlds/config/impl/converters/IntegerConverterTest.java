// SPDX-License-Identifier: Apache-2.0
package com.swirlds.config.impl.converters;

import java.util.stream.Stream;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class IntegerConverterTest {

    @Test
    public void convertNull() {
        // given
        final IntegerConverter converter = new IntegerConverter();

        // then
        Assertions.assertThrows(
                NullPointerException.class, () -> converter.convert(null), "Null values must always throw a NPE");
    }

    @ParameterizedTest
    @MethodSource("provideConversionChecks")
    public void conversionCheck(final String rawValue, final int expectedValue) {
        // given
        final IntegerConverter converter = new IntegerConverter();

        // when
        final int value = converter.convert(rawValue);

        // then
        Assertions.assertEquals(expectedValue, value, "All valid integers must be supported");
    }

    @Test
    public void convertOutOfRangeNumber() {
        // given
        final IntegerConverter converter = new IntegerConverter();
        final String rawValue = Integer.MAX_VALUE + "0";

        // then
        Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> converter.convert(rawValue),
                "Only the integer range must be supported");
    }

    @Test
    public void convertInvalid() {
        // given
        final IntegerConverter converter = new IntegerConverter();
        final String rawValue = "1.23";

        // then
        Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> converter.convert(rawValue),
                "Only valid int values must be supported");
    }

    private static Stream<Arguments> provideConversionChecks() {
        return Stream.of(
                Arguments.of("-7", -7),
                Arguments.of("7", 7),
                Arguments.of("0", 0),
                Arguments.of("10_000", 10000),
                Arguments.of("-10_000", -10000),
                Arguments.of(Integer.MAX_VALUE + "", Integer.MAX_VALUE),
                Arguments.of(Integer.MIN_VALUE + "", Integer.MIN_VALUE));
    }
}
