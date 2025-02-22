// SPDX-License-Identifier: Apache-2.0
package com.swirlds.config.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertIterableEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.swirlds.config.api.Configuration;
import com.swirlds.config.api.ConfigurationBuilder;
import com.swirlds.config.api.validation.ConfigViolationException;
import com.swirlds.config.extensions.sources.SimpleConfigSource;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ConfigApiRecordsTests {

    @Test
    void getConfigProxy() {
        // given
        final Configuration configuration = ConfigurationBuilder.create()
                .withSource(new SimpleConfigSource("network.port", 8080))
                .withConfigDataType(NetworkConfig.class)
                .build();

        // when
        final NetworkConfig networkConfig = configuration.getConfigData(NetworkConfig.class);

        // then
        assertEquals(8080, networkConfig.port(), "Config data objects should be configured correctly");
    }

    @Test
    void getNotRegisteredDataObject() {
        // given
        final ConfigurationBuilder configurationBuilder =
                ConfigurationBuilder.create().withConfigDataType(NetworkConfig.class);

        // then
        assertThrows(
                IllegalStateException.class,
                () -> configurationBuilder.build(),
                "It should not be possible to create a config data object with undefined values");
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
    void getConfigProxyDefaultValue() {
        // given
        final Configuration configuration = ConfigurationBuilder.create()
                .withSource(new SimpleConfigSource("network.port", "8080"))
                .withConfigDataType(NetworkConfig.class)
                .build();

        // when
        final NetworkConfig networkConfig = configuration.getConfigData(NetworkConfig.class);

        // then
        assertEquals("localhost", networkConfig.server(), "Default values of config data objects should be used");
    }

    @Test
    void getConfigProxyDefaultValuesList() {
        // given
        final Configuration configuration = ConfigurationBuilder.create()
                .withSource(new SimpleConfigSource("network.port", "8080"))
                .withConfigDataType(NetworkConfig.class)
                .build();

        // when
        final NetworkConfig networkConfig = configuration.getConfigData(NetworkConfig.class);
        final List<Integer> errorCodes = networkConfig.errorCodes();
        final Set<Long> errorCodeSet = networkConfig.errorCodeSet();

        // then
        assertNotNull(errorCodes, "Default values of config data objects should be used");
        assertEquals(2, errorCodes.size(), "List values should be supported for default values in config data objects");
        assertTrue(
                errorCodes.contains(404), "List values should be supported for default values in config data objects");
        assertTrue(
                errorCodes.contains(500), "List values should be supported for default values in config data objects");
        assertEquals(
                2, errorCodeSet.size(), "Set values should be supported for default values in config data objects");
        assertTrue(
                errorCodeSet.contains(404L),
                "Set values should be supported for default values in config data objects");
        assertTrue(
                errorCodeSet.contains(500L),
                "Set values should be supported for default values in config data objects");
    }

    @Test
    void getConfigProxyValuesList() {
        // given
        final Configuration configuration = ConfigurationBuilder.create()
                .withSource(new SimpleConfigSource("network.port", "8080"))
                .withSource(new SimpleConfigSource("network.errorCodes", "1,2,3"))
                .withConfigDataType(NetworkConfig.class)
                .build();

        // when
        final NetworkConfig networkConfig = configuration.getConfigData(NetworkConfig.class);
        final List<Integer> errorCodes = networkConfig.errorCodes();
        final Set<Long> errorCodeSet = networkConfig.errorCodeSet();

        // then
        assertNotNull(errorCodes, "List values should be supported in config data objects");
        assertEquals(3, errorCodes.size(), "List values should be supported in config data objects");
        assertTrue(errorCodes.contains(1), "List values should be supported in config data objects");
        assertTrue(errorCodes.contains(2), "List values should be supported in config data objects");
        assertTrue(errorCodes.contains(3), "List values should be supported in config data objects");
        assertEquals(3, errorCodeSet.size(), "Set values should be supported in config data objects");
        assertTrue(errorCodeSet.contains(1L), "Set values should be supported in config data objects");
        assertTrue(errorCodeSet.contains(2L), "Set values should be supported in config data objects");
        assertTrue(errorCodeSet.contains(3L), "Set values should be supported in config data objects");
    }

    @Test
    void invalidDataRecordWillFailInit() {
        // given
        final ConfigurationBuilder configurationBuilder =
                ConfigurationBuilder.create().withConfigDataType(NetworkConfig.class);

        // then
        assertThrows(
                IllegalStateException.class,
                () -> configurationBuilder.build(),
                "values must be defined for all properties that are defined by registered config data types");
    }

    @Test
    void getConfigProxyOverwrittenDefaultValue() {
        // given
        final Configuration configuration = ConfigurationBuilder.create()
                .withSource(new SimpleConfigSource("network.port", "8080"))
                .withSource(new SimpleConfigSource("network.server", "example.net"))
                .withConfigDataType(NetworkConfig.class)
                .build();

        // when
        final NetworkConfig networkConfig = configuration.getConfigData(NetworkConfig.class);

        // then
        assertEquals(
                "example.net",
                networkConfig.server(),
                "It must be possible to overwrite default values in object data types");
    }

    @Test
    void testMinConstrainAnnotation() {
        // given
        final ConfigurationBuilder configurationBuilder = ConfigurationBuilder.create()
                .withSources(new SimpleConfigSource("network.port", "-1"))
                .withConfigDataType(NetworkConfig.class);

        // when
        final ConfigViolationException exception = assertThrows(
                ConfigViolationException.class,
                () -> configurationBuilder.build(),
                "Check for @Min annotation in NetworkConfig should end in violation");

        // then
        assertEquals(1, exception.getViolations().size());
        assertTrue(exception.getViolations().get(0).propertyExists());
        assertEquals("network.port", exception.getViolations().get(0).getPropertyName());
        assertEquals("-1", exception.getViolations().get(0).getPropertyValue());
        assertEquals("Value must be >= 1", exception.getViolations().get(0).getMessage());
    }

    @Test
    void testConstrainAnnotation() {
        // given
        final ConfigurationBuilder configurationBuilder = ConfigurationBuilder.create()
                .withSources(new SimpleConfigSource("network.port", "8080"))
                .withSources(new SimpleConfigSource("network.server", "invalid"))
                .withConfigDataType(NetworkConfig.class);

        // when
        final ConfigViolationException exception = assertThrows(
                ConfigViolationException.class,
                () -> configurationBuilder.build(),
                "Check for @Constraint annotation in NetworkConfig should end in violation");

        // then
        assertEquals(1, exception.getViolations().size());
        assertTrue(exception.getViolations().get(0).propertyExists());
        assertEquals("network.server", exception.getViolations().get(0).getPropertyName());
        assertEquals("invalid", exception.getViolations().get(0).getPropertyValue());
        assertEquals(
                "server must not be invalid", exception.getViolations().get(0).getMessage());
    }

    @Test
    void testMultipleConstrainAnnotationsFail() {
        // given
        final ConfigurationBuilder configurationBuilder = ConfigurationBuilder.create()
                .withSources(new SimpleConfigSource("network.port", "-1"))
                .withSources(new SimpleConfigSource("network.server", "invalid"))
                .withConfigDataType(NetworkConfig.class);

        // when
        final ConfigViolationException exception = assertThrows(
                ConfigViolationException.class,
                () -> configurationBuilder.build(),
                "Check for @Constraint annotation in NetworkConfig should end in violation");

        // then
        assertEquals(2, exception.getViolations().size());
    }

    @Test
    void testNullDefaultsInConfigDataRecord() {
        // given
        final Configuration configuration = ConfigurationBuilder.create()
                .withConfigDataType(NullConfig.class)
                .build();

        // when
        final String value = configuration.getConfigData(NullConfig.class).value();
        final List<Integer> list = configuration.getConfigData(NullConfig.class).list();
        final Set<Integer> set = configuration.getConfigData(NullConfig.class).set();

        // then
        assertNull(value);
        assertNull(list);
        assertNull(set);
    }

    @Test
    void testEmptyCollectionsInConfigDataRecord() {
        // given
        final Configuration configuration = ConfigurationBuilder.create()
                .withConfigDataType(EmptyCollectionConfig.class)
                .build();

        // when
        final List<Integer> list =
                configuration.getConfigData(EmptyCollectionConfig.class).list();
        final Set<Integer> set =
                configuration.getConfigData(EmptyCollectionConfig.class).set();

        // then
        assertIterableEquals(List.of(), list);
        assertIterableEquals(Set.of(), set);
    }
}
