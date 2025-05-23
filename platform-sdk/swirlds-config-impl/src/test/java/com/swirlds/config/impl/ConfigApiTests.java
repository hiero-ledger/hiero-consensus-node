// SPDX-License-Identifier: Apache-2.0
package com.swirlds.config.impl;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.swirlds.config.api.Configuration;
import com.swirlds.config.api.ConfigurationBuilder;
import com.swirlds.config.api.validation.ConfigValidator;
import com.swirlds.config.api.validation.ConfigViolationException;
import com.swirlds.config.extensions.sources.LegacyFileConfigSource;
import com.swirlds.config.extensions.sources.PropertyFileConfigSource;
import com.swirlds.config.extensions.sources.SimpleConfigSource;
import com.swirlds.config.extensions.sources.SystemPropertiesConfigSource;
import com.swirlds.config.impl.converters.DurationConverter;
import com.swirlds.config.impl.validators.DefaultConfigViolation;
import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.Date;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

class ConfigApiTests {

    @Test
    void checkNotExistingProperty() {
        // given
        final Configuration configuration = ConfigurationBuilder.create().build();

        // then
        assertFalse(configuration.exists("someName"), "A not defined config property should not exist");
    }

    @Test
    void checkExistingProperty() {
        // given
        final Configuration configuration = ConfigurationBuilder.create()
                .withSource(new SimpleConfigSource("someName", "123"))
                .build();

        // then
        assertTrue(configuration.exists("someName"), "A defined config property should exist");
    }

    @Test
    void getAllPropertyNamesEmpty() {
        // given
        final Configuration configuration = ConfigurationBuilder.create().build();

        // when
        final List<String> names = configuration.getPropertyNames().collect(Collectors.toList());

        // then
        assertNotNull(names, "Even for an empty config the returned collection should not be null");
        assertEquals(0, names.size(), "The config should not contain any properties");
    }

    @Test
    void getAllPropertyNames() {
        // given
        final Configuration configuration = ConfigurationBuilder.create()
                .withSource(new SimpleConfigSource("someName", "123"))
                .withSource(new SimpleConfigSource("someOtherName", "test"))
                .withSource(new SimpleConfigSource("company.url", "dummy"))
                .build();

        // when
        final List<String> names = configuration.getPropertyNames().collect(Collectors.toList());

        // then
        assertNotNull(names, "The collection of config properties should never be null");
        assertEquals(3, names.size(), "The config should contain 3 properties");
        assertTrue(names.contains("someName"), "The config should contain the defined property");
        assertTrue(names.contains("someOtherName"), "The config should contain the defined property");
        assertTrue(names.contains("company.url"), "The config should contain the defined property");
    }

    @Test
    void readNotExistingStringProperty() {
        // given
        final Configuration configuration = ConfigurationBuilder.create().build();

        // then
        assertThrows(
                NoSuchElementException.class,
                () -> configuration.getValue("someName"),
                "The config should throw an exception when trying to access a property that is not defined");
    }

    @Test
    void readDefaultStringProperty() {
        // given
        final Configuration configuration = ConfigurationBuilder.create().build();

        // when
        final String value = configuration.getValue("someName", "default");

        // then
        assertEquals("default", value, "The default value should be returned for a property that is not defined");
    }

    @Test
    void checkOverwriteConverter() {
        // given
        final ConfigurationBuilder configurationBuilder = ConfigurationBuilder.create();
        configurationBuilder.withSource(new SimpleConfigSource("TEST", "123"));

        // when
        configurationBuilder.withConverter(Duration.class, value -> Duration.ofSeconds(1));
        final Configuration configuration = configurationBuilder.build();

        // then
        assertEquals(
                Duration.ofSeconds(1),
                configuration.getValue("TEST", Duration.class),
                "The defined converter should be used");
    }

    @Test
    void readStringProperty() {
        // given
        final Configuration configuration = ConfigurationBuilder.create()
                .withSource(new SimpleConfigSource("someName", "123"))
                .build();

        // when
        final String value = configuration.getValue("someName");

        // then
        assertEquals("123", value, "The defined value of the property should be returned");
    }

    @Test
    void readStringPropertyWithDefaultProperty() {
        // given
        final Configuration configuration = ConfigurationBuilder.create()
                .withSource(new SimpleConfigSource("someName", "123"))
                .build();

        // when
        final String value = configuration.getValue("someName", "default");

        // then
        assertEquals("123", value, "If a property is defined the default value should be ignored");
    }

    @Test
    void readMissingStringPropertyWithNullProperty() {
        // given
        final Configuration configuration = ConfigurationBuilder.create().build();

        // when
        final String value = configuration.getValue("someName", String.class, null);

        // then
        assertNull(value, "Null should be an allowed default value");
    }

    @Test
    void readIntProperty() {
        // given
        final Configuration configuration = ConfigurationBuilder.create()
                .withSource(new SimpleConfigSource("timeout", 12))
                .build();

        // when
        final int value = configuration.getValue("timeout", Integer.class);

        // then
        assertEquals(12, value, "Integer value should be supported");
    }

    @Test
    void readNotExistingIntProperty() {
        // given
        final Configuration configuration = ConfigurationBuilder.create().build();

        // then
        assertThrows(
                NoSuchElementException.class,
                () -> configuration.getValue("timeout", Integer.class),
                "The config should throw an exception when trying to access a property that is not defined");
    }

    @Test
    void readBadIntProperty() {
        // given
        final Configuration configuration = ConfigurationBuilder.create()
                .withSource(new SimpleConfigSource("timeout", "NO-INT"))
                .build();

        // then
        assertThrows(
                IllegalArgumentException.class,
                () -> configuration.getValue("timeout", Integer.class),
                "The config should throw an exception when trying to access a property that can not be converted to "
                        + "it's defined type");
    }

    @Test
    void readIntPropertyWithDefaultValue() {
        // given
        final Configuration configuration = ConfigurationBuilder.create()
                .withSource(new SimpleConfigSource("timeout", 12))
                .build();

        // when
        final int value = configuration.getValue("timeout", Integer.class, 1_000);

        // then
        assertEquals(12, value, "Default value should be ignored for a defined property");
    }

    @Test
    void readMissingIntPropertyWithDefaultValue() {
        // given
        final Configuration configuration = ConfigurationBuilder.create().build();

        // when
        final int value = configuration.getValue("timeout", Integer.class, 1_000);

        // then
        assertEquals(1_000, value, "The default value should be used for a property that is not defined");
    }

    @Test
    void readMissingIntPropertyWithNullDefaultValue() {
        // given
        final Configuration configuration = ConfigurationBuilder.create().build();

        // when
        final Integer value = configuration.getValue("timeout", Integer.class, null);

        // then
        assertNull(value, "Null should be allowed as default value");
    }

    @Test
    void readMissingIntPropertyWithNullDefaultValueAutoboxing() {
        // given
        final Configuration configuration = ConfigurationBuilder.create().build();

        // then
        assertThrows(
                NullPointerException.class,
                () -> {
                    final int timeout = configuration.getValue("timeout", Integer.class, null);
                },
                "Autoboxing of null as default value should throw an exception for int");
    }

    @Test
    void getConfigProxyUndefinedValue() {
        // given
        final Configuration configuration = ConfigurationBuilder.create().build();

        // then
        assertThrows(
                IllegalArgumentException.class,
                () -> configuration.getConfigData(NetworkConfig.class),
                "It should not be possible to create an object of a not registered config data type");
    }

    @Test
    void getSystemProperty() {
        // given
        System.setProperty("test.config.sample", "qwerty");
        final Configuration configuration = ConfigurationBuilder.create()
                .withSource(SystemPropertiesConfigSource.getInstance())
                .build();

        // when
        final String value = configuration.getValue("test.config.sample");

        // then
        assertEquals("qwerty", value, "It must be possible to use system variables for the config");
    }

    @Test
    void getPropertyFromFile() throws IOException, URISyntaxException {
        // given
        final Path configFile =
                Paths.get(ConfigApiTests.class.getResource("test.properties").toURI());
        final Configuration configuration = ConfigurationBuilder.create()
                .withSource(new PropertyFileConfigSource(configFile))
                .build();

        // when
        final String value = configuration.getValue("app.name");

        // then
        assertEquals("ConfigTest", value, "It must be possible to read variables for the config from a property file");
    }

    @Test
    void getPropertyFromFileOverwritesDefault() throws IOException, URISyntaxException {
        // given
        final Path configFile =
                Paths.get(ConfigApiTests.class.getResource("test.properties").toURI());

        final Configuration configuration = ConfigurationBuilder.create()
                .withSource(new PropertyFileConfigSource(configFile))
                .withSources(new SimpleConfigSource("app.name", "unknown"))
                .build();

        // when
        final String value = configuration.getValue("app.name");

        // then
        assertEquals(
                "ConfigTest", value, "the used value must be based on the ordinal of the underlying config sources");
    }

    @Test
    void checkValidationForNotExistingProperty() {
        // given
        final ConfigValidator validator = configuration -> {
            if (!configuration.exists("app.name")) {
                return Stream.of(new DefaultConfigViolation(
                        "app.name", null, false, "Property 'app.name' must not be " + "null"));
            }
            return Stream.empty();
        };
        final ConfigurationBuilder configurationBuilder =
                ConfigurationBuilder.create().withValidator(validator);

        // when
        final IllegalStateException exception = assertThrows(
                IllegalStateException.class,
                () -> configurationBuilder.build(),
                "Config must not be initialized if a validation fails");

        // then
        assertEquals(
                ConfigViolationException.class,
                exception.getClass(),
                "The specific exception type is used for a failed validation");
        final ConfigViolationException configViolationException = (ConfigViolationException) exception;
        assertEquals(
                1, configViolationException.getViolations().size(), "The given init should only end in 1 violation");
        assertEquals(
                "app.name",
                configViolationException.getViolations().get(0).getPropertyName(),
                "The violation should contain the correct property name");
        assertEquals(
                "Property 'app.name' must not be null",
                configViolationException.getViolations().get(0).getMessage(),
                "The violation must contain the correct error message");
    }

    @Test
    void loadLegacySettings() throws IOException, URISyntaxException {
        // given
        final Path configFile = Paths.get(
                ConfigApiTests.class.getResource("legacy-settings.txt").toURI());
        final Configuration configuration = ConfigurationBuilder.create()
                .withSource(new LegacyFileConfigSource(configFile))
                .build();
        // then
        assertEquals(
                6,
                configuration.getPropertyNames().count(),
                "It must be possible to read config properties from the old file format");
        assertTrue(
                configuration.exists("state.saveStatePeriod"),
                "It must be possible to read config properties from the old file format");
        assertTrue(
                configuration.exists("showInternalStats"),
                "It must be possible to read config properties from the old file format");
        assertTrue(
                configuration.exists("useLoopbackIp"),
                "It must be possible to read config properties from the old file format");
        assertTrue(
                configuration.exists("csvFileName"),
                "It must be possible to read config properties from the old file format");
        assertTrue(
                configuration.exists("checkSignedStateFromDisk"),
                "It must be possible to read config properties from the old file format");
        assertTrue(
                configuration.exists("loadKeysFromPfxFiles"),
                "It must be possible to read config properties from the old file format");

        assertEquals(
                0,
                configuration.getValue("state.saveStatePeriod", Integer.class),
                "It must be possible to read config properties from the old file format");
        assertEquals(
                1,
                configuration.getValue("showInternalStats", Integer.class),
                "It must be possible to read config properties from the old file format");
        assertNotEquals(
                Boolean.TRUE,
                configuration.getValue("useLoopbackIp", Boolean.class),
                "It must be possible to read config properties from the old file format");
        assertEquals(
                "PlatformTesting",
                configuration.getValue("csvFileName"),
                "It must be possible to read config properties from the old file format");
        assertEquals(
                1,
                configuration.getValue("checkSignedStateFromDisk", Integer.class),
                "It must be possible to read config properties from the old file format");
        assertEquals(
                0,
                configuration.getValue("loadKeysFromPfxFiles", Integer.class),
                "It must be possible to read config properties from the old file format");
    }

    @Test
    void registerConverterForTypeMultipleTimes() {
        // given
        final ConfigurationBuilder configurationBuilder = ConfigurationBuilder.create()
                .withConverter(Duration.class, new DurationConverter())
                .withConverter(Duration.class, value -> Duration.ofSeconds(2));

        // then
        assertDoesNotThrow(configurationBuilder::build);
    }

    @Test
    void registerCustomConverter() {
        // given
        final Configuration configuration = ConfigurationBuilder.create()
                .withConverter(Date.class, new TestDateConverter())
                .withSource(new SimpleConfigSource("app.start", "1662367513551"))
                .build();

        // when
        final Date date = configuration.getValue("app.start", Date.class);

        // then
        assertEquals(new Date(1662367513551L), date, "The date should be converted correctly based on the given value");
    }

    @Test
    void testNullList() {
        // given
        final Configuration configuration = ConfigurationBuilder.create()
                .withSources(new SimpleConfigSource("sample.list", (List<String>) null))
                .build();

        // when
        final List<String> list1 = configuration.getValues("sample.list");
        final List<String> list2 = configuration.getValues("sample.list", List.of());
        final List<String> list3 = configuration.getValues("sample.list", String.class);
        final List<String> list4 = configuration.getValues("sample.list", String.class, List.of());
        final List<Integer> list5 = configuration.getValues("sample.list", Integer.class);
        final List<Integer> list6 = configuration.getValues("sample.list", Integer.class, List.of());

        // then
        assertNull(list1, "The list must be null");
        assertNull(list2, "The list must be null since a default is only used if a property is not defined");
        assertNull(list3, "The list must be null");
        assertNull(list4, "The list must be null since a default is only used if a property is not defined");
        assertNull(list5, "The list must be null");
        assertNull(list6, "The list must be null since a default is only used if a property is not defined");
    }

    @Test
    void testNullProperties() {
        // given
        final Configuration configuration = ConfigurationBuilder.create()
                .withSources(new SimpleConfigSource("sample", (String) null))
                .build();

        // when
        final String value1 = configuration.getValue("sample");
        final String value2 = configuration.getValue("sample", "default");
        final String value3 = configuration.getValue("sample", String.class);
        final String value4 = configuration.getValue("sample", String.class, "default");

        final Integer value5 = configuration.getValue("sample", Integer.class);
        final Integer value6 = configuration.getValue("sample", Integer.class, 123);

        // then
        assertNull(value1, "The value must be null");
        assertNull(value2, "The value must be null since a default is only used if a property is not defined");
        assertNull(value3, "The value must be null");
        assertNull(value4, "The value must be null since a default is only used if a property is not defined");
        assertNull(value5, "The value must be null");
        assertNull(value6, "The value must be null since a default is only used if a property is not defined");
    }
}
