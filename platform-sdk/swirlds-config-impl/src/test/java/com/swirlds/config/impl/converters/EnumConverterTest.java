// SPDX-License-Identifier: Apache-2.0
package com.swirlds.config.impl.converters;

import java.util.function.Supplier;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

public class EnumConverterTest {

    @Test
    public void itFailsWhenConstructingWithNull() {
        // given
        Supplier<EnumConverter<?>> supplier = () -> new EnumConverter<>(null);
        // then
        Assertions.assertThrows(NullPointerException.class, supplier::get);
    }

    @Test
    public void itFailsWhenConvertingNull() {
        // given
        final EnumConverter<NumberEnum> converter = new EnumConverter<>(NumberEnum.class);
        // then
        Assertions.assertThrows(NullPointerException.class, () -> converter.convert(null));
    }

    @Test
    public void itSucceedsWhenConvertingAValidEnumValue() {
        // given
        final EnumConverter<NumberEnum> converter = new EnumConverter<>(NumberEnum.class);

        // when
        final NumberEnum value = converter.convert("ONE");

        // then
        Assertions.assertEquals(NumberEnum.ONE, value);
    }

    @Test
    public void itFailsWhenConvertingInvalidEnumValue() {
        // given
        final EnumConverter<NumberEnum> converter = new EnumConverter<>(NumberEnum.class);

        // then
        Assertions.assertThrows(IllegalArgumentException.class, () -> converter.convert(""));
    }

    @Test
    void itSuccessfullyConvertsValidEnumValueWithSpecialChar() {
        // given
        final EnumConverter<SpecialCharacterEnum> converter = new EnumConverter<>(SpecialCharacterEnum.class);

        // then:
        Assertions.assertEquals(SpecialCharacterEnum.Ñ, converter.convert("Ñ"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"One", "one", "onE", "oNe", " ONE", "ONE ", "DOS", "OnE", "null"})
    void itFailsConvertingInvalidEnumValues(final String param) {
        // given
        final EnumConverter<NumberEnum> converter = new EnumConverter<>(NumberEnum.class);
        // then
        Assertions.assertThrows(IllegalArgumentException.class, () -> converter.convert(param));
    }

    private enum NumberEnum {
        ONE,
        TWO
    }

    private enum SpecialCharacterEnum {
        Ñ,
    }
}
