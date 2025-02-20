// SPDX-License-Identifier: Apache-2.0
package com.swirlds.config.impl.converters;

import java.util.stream.Stream;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class BooleanConverterTest {

    @Test
    public void convertNull() {
        // given
        final BooleanConverter converter = new BooleanConverter();

        // then
        Assertions.assertThrows(
                NullPointerException.class, () -> converter.convert(null), "Null values must always throw a NPE");
    }

    @ParameterizedTest
    @MethodSource("provideConversionChecks")
    public void conversionCheck(final String rawValue, final boolean expectedValue) {
        // given
        final BooleanConverter converter = new BooleanConverter();

        // when
        final boolean value = converter.convert(rawValue);

        // then
        Assertions.assertEquals(expectedValue, value, "All valid boolean values must be supported");
    }

    private static Stream<Arguments> provideConversionChecks() {
        return Stream.of(
                Arguments.of("true", true),
                Arguments.of("false", false),
                Arguments.of("  ", false),
                Arguments.of("", false),
                Arguments.of("TRUE", true),
                Arguments.of("1", true),
                Arguments.of("0", false));
    }
}
