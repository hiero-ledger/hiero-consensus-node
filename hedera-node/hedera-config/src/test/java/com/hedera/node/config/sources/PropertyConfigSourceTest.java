// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.config.sources;

import static org.junit.jupiter.api.Assertions.*;

import java.io.UncheckedIOException;
import java.lang.reflect.Method;
import java.util.Properties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class PropertyConfigSourceTest {

    private Properties properties;

    @BeforeEach
    void setUp() {
        properties = new Properties();
        properties.setProperty("key1", "value1");
        properties.setProperty("key2", "value2");
    }

    @Test
    void testExistentPropertyConfigSource() {
        // given
        int ordinal = 1; // used to determine priority of config source

        // when
        PropertyConfigSource propertyConfigSource = new PropertyConfigSource(properties, ordinal);

        // then
        assertEquals(2, propertyConfigSource.getPropertyNames().size());
        assertEquals("value1", propertyConfigSource.getValue("key1"));
        assertEquals("value2", propertyConfigSource.getValue("key2"));
        assertEquals(ordinal, propertyConfigSource.getOrdinal());
    }

    @Test
    void testPropertyConfigSourceWhenPropertyFileNotFound() {
        // given
        String nonExistentFile = "non-existent-file.properties";

        // then
        assertThrows(UncheckedIOException.class, () -> new PropertyConfigSource(nonExistentFile, 1));
    }

    @Test
    void testLoadProperties() throws Exception {
        // given
        String existingFile = "alphabet.properties";

        // when
        Method method = PropertyConfigSource.class.getDeclaredMethod("loadProperties", String.class);
        method.setAccessible(true);

        // then
        // Test successful path
        Properties properties = (Properties) method.invoke(null, existingFile);
        assertNotNull(properties);
    }

    @AfterEach
    void tearDown() {
        properties = null;
    }
}
