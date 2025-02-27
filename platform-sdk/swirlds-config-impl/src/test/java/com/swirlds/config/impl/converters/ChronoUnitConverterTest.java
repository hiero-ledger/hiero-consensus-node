// SPDX-License-Identifier: Apache-2.0
package com.swirlds.config.impl.converters;

import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class ChronoUnitConverterTest {

    @Test
    public void convertNull() {
        // given
        final ChronoUnitConverter converter = new ChronoUnitConverter();

        // then
        Assertions.assertThrows(
                NullPointerException.class,
                () -> converter.convert(null),
                "Passing 'null' as a value must always end in a NPE");
    }

    @ParameterizedTest
    @MethodSource("provideAllChronoUnitParameters")
    public void convert(final ChronoUnit unit, String rawValue) {
        // given
        final ChronoUnitConverter converter = new ChronoUnitConverter();

        // when
        final ChronoUnit value = converter.convert(rawValue);

        // then
        Assertions.assertEquals(unit, value, "The converted value must be the correct enum value");
    }

    @Test
    public void convertBadValue() {
        // given
        final ChronoUnitConverter converter = new ChronoUnitConverter();
        final String rawValue = "invalid";

        // then
        Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> converter.convert(rawValue),
                "It should not be possible to convert an invalid value");
    }

    private static Stream<Arguments> provideAllChronoUnitParameters() {
        return Arrays.stream(ChronoUnit.values()).flatMap(chronoUnit -> {
            final List<Arguments> result = new ArrayList<>(3);
            result.add(Arguments.of(chronoUnit, chronoUnit.toString()));
            result.add(Arguments.of(chronoUnit, chronoUnit.toString().toLowerCase()));
            result.add(Arguments.of(chronoUnit, chronoUnit.toString().toUpperCase()));
            return result.stream();
        });
    }
}
