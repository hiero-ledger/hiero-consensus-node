// SPDX-License-Identifier: Apache-2.0
package com.swirlds.config.impl.internal;

import com.swirlds.config.api.Configuration;
import com.swirlds.config.api.ConfigurationBuilder;
import com.swirlds.config.api.converter.ConfigConverter;
import com.swirlds.config.extensions.sources.SimpleConfigSource;
import com.swirlds.config.impl.converters.EnumConverter;
import java.io.File;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.temporal.ChronoUnit;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ConverterServiceTest {

    private ConverterService converterService;

    @BeforeEach
    public void setUp() {
        converterService = new ConverterService();
        converterService.init();
    }

    @Test
    void itSuccessfullyCreatesAndReturnsAConverter() {
        // given
        final ConverterService cs = converterService;

        ConfigConverter<NumberEnum> converterForType = cs.getConverterForType(NumberEnum.class);
        // then:
        Assertions.assertNotNull(converterForType);
        Assertions.assertInstanceOf(EnumConverter.class, converterForType);
    }

    @Test
    void itDoesNotCreateANewInstanceForAlreadyCachedConverters() {
        // given
        final ConverterService cs = converterService;
        ConfigConverter<NumberEnum> converter = cs.getConverterForType(NumberEnum.class);

        // then:
        Assertions.assertSame(converter, cs.getConverterForType(NumberEnum.class));
    }

    @Test
    void itSuccessfullyUsesConfiguredEnumConverterAboveDefaultConverter() {
        // given
        ConverterService cs = new ConverterService();
        ConfigConverter<NumberAndValueEnum> converter = value -> NumberAndValueEnum.ONE;
        cs.addConverter(NumberAndValueEnum.class, converter); // creates a new enumConverter for NumberAndValueEnum
        cs.init();

        // then:
        // NumberValueEnum  gets converted with FakeEnumConverter
        Assertions.assertSame(converter, cs.getConverterForType(NumberAndValueEnum.class));
        Assertions.assertEquals(NumberAndValueEnum.ONE, cs.convert("ONE", NumberAndValueEnum.class));
        Assertions.assertEquals(NumberAndValueEnum.ONE, cs.convert("", NumberAndValueEnum.class));
        Assertions.assertEquals(NumberAndValueEnum.ONE, cs.convert("DOS", NumberAndValueEnum.class));
        Assertions.assertFalse(cs.getConverterForType(NumberAndValueEnum.class) instanceof EnumConverter);
        // and:
        // NumberEnum stills gets converted with defaultEnumConverter
        Assertions.assertEquals(NumberEnum.ONE, cs.convert("ONE", NumberEnum.class));
        Assertions.assertThrows(IllegalArgumentException.class, () -> cs.convert("", NumberEnum.class));
        Assertions.assertThrows(IllegalArgumentException.class, () -> cs.convert("DOS", NumberEnum.class));
        Assertions.assertInstanceOf(EnumConverter.class, cs.getConverterForType(NumberEnum.class));
    }

    @Test
    void itSuccessfullyConvertsPrimitives() {
        // given
        final ConverterService cs = converterService;

        // then
        Assertions.assertEquals(42, cs.convert("42", Integer.TYPE));
        Assertions.assertEquals(123L, cs.convert("123", Long.TYPE));
        Assertions.assertEquals(3.14, cs.convert("3.14", Double.TYPE), 0.0001);
        Assertions.assertEquals(2.718f, cs.convert("2.718", Float.TYPE), 0.0001f);
        Assertions.assertEquals((short) 567, cs.convert("567", Short.TYPE));
        Assertions.assertEquals((byte) 8, cs.convert("8", Byte.TYPE));
        Assertions.assertTrue(cs.convert("true", Boolean.TYPE));
    }

    @Test
    void itSuccessfullyConvertsBoxedPrimitives() {
        // given
        final ConverterService cs = converterService;

        // then
        Assertions.assertEquals(Integer.valueOf(42), cs.convert("42", Integer.class));
        Assertions.assertEquals(Long.valueOf(123), cs.convert("123", Long.class));
        Assertions.assertEquals(3.14, cs.convert("3.14", Double.class), 0.0001);
        Assertions.assertEquals(2.718f, cs.convert("2.718", Float.class), 0.0001f);
        Assertions.assertEquals((short) 567, cs.convert("567", Short.class));
        Assertions.assertEquals((byte) 8, cs.convert("8", Byte.class));
        Assertions.assertTrue(cs.convert("true", Boolean.class));
    }

    @Test
    void itSuccessfullyOverwriteDefaultConverter() {
        // given
        ConverterService cs = new ConverterService();

        // when
        cs.addConverter(Duration.class, value -> Duration.of(1, ChronoUnit.SECONDS));
        cs.init();

        // then
        Assertions.assertEquals(Duration.of(1, ChronoUnit.SECONDS), cs.convert("TEST", Duration.class));
    }

    @Test
    void itSuccessfullyConvertsOtherTypes() {
        // given
        final ConverterService cs = converterService;

        // then
        Assertions.assertEquals(new BigDecimal("123.45"), cs.convert("123.45", BigDecimal.class));
        Assertions.assertEquals(new BigInteger("789"), cs.convert("789", BigInteger.class));

        try {
            URL url = new URL("https://www.example.com");
            Assertions.assertEquals(url, cs.convert("https://www.example.com", URL.class));
        } catch (MalformedURLException e) {
            // Handle exception if URL creation fails
            Assertions.fail("Failed to create URL");
        }

        try {
            URI uri = new URI("https://www.example.com");
            Assertions.assertEquals(uri, cs.convert("https://www.example.com", URI.class));
        } catch (URISyntaxException e) {
            // Handle exception if URI creation fails
            Assertions.fail("Failed to create URI");
        }

        Assertions.assertEquals(Paths.get("/path/to/file"), cs.convert("/path/to/file", Path.class));

        File file = new File("/path/to/file");
        Assertions.assertEquals(file, cs.convert("/path/to/file", File.class));
    }

    @Test
    void itHandlesNullValueForBoxedPrimitiveConversion() {
        // given
        final ConverterService cs = converterService;

        // then
        Assertions.assertNull(cs.convert(null, Integer.class));
        Assertions.assertNull(cs.convert(null, Long.class));
        Assertions.assertNull(cs.convert(null, Double.class));
        Assertions.assertNull(cs.convert(null, Float.class));
        Assertions.assertNull(cs.convert(null, Short.class));
        Assertions.assertNull(cs.convert(null, Byte.class));
        Assertions.assertNull(cs.convert(null, Boolean.class));
    }

    @Test
    void itHandlesUnknownEnumType() {
        // given
        final ConverterService cs = converterService;

        // then
        Assertions.assertThrows(IllegalArgumentException.class, () -> cs.convert("ONE", UnknownClass.class));
    }

    @Test
    void itHandlesNullEnumType() {
        // given
        final ConverterService cs = converterService;

        // then
        Assertions.assertThrows(NullPointerException.class, () -> cs.convert("ONE", null));
    }

    @Test
    void itHandlesNullValueForOtherTypes() {
        // given
        final ConverterService cs = converterService;

        // then
        Assertions.assertNull(cs.convert(null, BigDecimal.class));
        Assertions.assertNull(cs.convert(null, BigInteger.class));
        Assertions.assertNull(cs.convert(null, URL.class));
        Assertions.assertNull(cs.convert(null, URI.class));
        Assertions.assertNull(cs.convert(null, Path.class));
        Assertions.assertNull(cs.convert(null, File.class));
    }

    @Test
    void itUsesNewConverterInsteadOfDefaultForType() {
        // given
        final ConverterService cs = new ConverterService();
        // when
        cs.addConverter(Object.class, value -> "CustomStringConverter");
        cs.init();

        // then
        Assertions.assertEquals("CustomStringConverter", cs.convert("originalValue", Object.class));
        Assertions.assertEquals("originalValue", cs.convert("originalValue", String.class));
    }

    @Test
    void testIntegration() {

        // given
        final Configuration configuration = ConfigurationBuilder.create()
                .withSource(new SimpleConfigSource("plain-value", "ONE"))
                .withSource(new SimpleConfigSource("complex-value-one", "ONE"))
                .withSource(new SimpleConfigSource("complex-value-two", "DOS"))
                .withSource(new SimpleConfigSource("complex-value-three", "THREE"))
                .build();

        // when
        final NumberEnum uno = configuration.getValue("plain-value", NumberEnum.class);
        final NumberAndValueEnum one = configuration.getValue("complex-value-one", NumberAndValueEnum.class);

        // then
        Assertions.assertEquals(uno, NumberEnum.ONE);
        Assertions.assertEquals(one, NumberAndValueEnum.ONE);
        Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> configuration.getValue("complex-value-two", NumberAndValueEnum.class));
        Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> configuration.getValue("complex-value-three", NumberAndValueEnum.class));
    }

    private enum NumberEnum {
        ONE
    }

    private enum NumberAndValueEnum {
        ONE(1);
        final int value;

        NumberAndValueEnum(int value) {
            this.value = value;
        }
    }

    private static class UnknownClass {}
}
