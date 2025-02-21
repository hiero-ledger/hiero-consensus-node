// SPDX-License-Identifier: Apache-2.0
package com.swirlds.config.impl.converters;

import java.util.stream.Stream;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class FloatConverterTest {

    @Test
    public void convertNull() {
        // given
        final FloatConverter converter = new FloatConverter();

        // then
        Assertions.assertThrows(
                NullPointerException.class, () -> converter.convert(null), "Null values must always throw a NPE");
    }

    @ParameterizedTest
    @MethodSource("provideConversionChecks")
    public void conversionCheck(final String rawValue, final float expectedValue) {
        // given
        final FloatConverter converter = new FloatConverter();

        // when
        final float value = converter.convert(rawValue);

        // then
        Assertions.assertEquals(expectedValue, value, "All valid double values must be supported");
    }

    @Test
    public void convertInvalid() {
        // given
        final FloatConverter converter = new FloatConverter();
        final String rawValue = "Hello";

        // then
        Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> converter.convert(rawValue),
                "Only valid int values must be supported");
    }

    private static Stream<Arguments> provideConversionChecks() {
        return Stream.of(
                Arguments.of("-7.5", -7.5f),
                Arguments.of("2.1", 2.1f),
                Arguments.of("0", 0),
                Arguments.of(Float.MAX_VALUE + "", Float.MAX_VALUE),
                Arguments.of(Float.MIN_VALUE + "", Float.MIN_VALUE),
                Arguments.of(Float.NaN + "", Float.NaN),
                Arguments.of(Float.NEGATIVE_INFINITY + "", Float.NEGATIVE_INFINITY),
                Arguments.of(Float.POSITIVE_INFINITY + "", Float.POSITIVE_INFINITY));
    }
}
