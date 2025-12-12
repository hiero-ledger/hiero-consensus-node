// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.config.legacy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.net.UnknownHostException;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.hiero.consensus.model.roster.SimpleAddress;
import org.hiero.consensus.model.roster.SimpleAddresses;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class LegacyConfigPropertiesLoaderTest {

    @Test
    void testNullValue() {
        Assertions.assertThrows(NullPointerException.class, () -> LegacyConfigPropertiesLoader.loadConfigFile(null));
    }

    @Test
    void testInvalidPath() {
        final Path path = Paths.get("does", "not", "exits");
        Assertions.assertThrows(ConfigurationException.class, () -> LegacyConfigPropertiesLoader.loadConfigFile(path));
    }

    @Test
    void testEmptyConfig() {
        // given
        final Path path = Paths.get(
                LegacyConfigPropertiesLoaderTest.class.getResource("empty.txt").getPath());

        // when & then
        // NK(2024-10-10): An empty file should be considered an invalid configuration file. The fact that this was
        // previously considered a valid configuration file is a bug.
        // The correct expected behavior is to throw a ConfigurationException.
        assertThrows(ConfigurationException.class, () -> LegacyConfigPropertiesLoader.loadConfigFile(path));
    }

    @Test
    void testRealisticConfig() throws UnknownHostException {
        // given
        final Path path = Paths.get(
                LegacyConfigPropertiesLoaderTest.class.getResource("config.txt").getPath());

        // when
        final LegacyConfigProperties properties = LegacyConfigPropertiesLoader.loadConfigFile(path);

        // then
        assertNotNull(properties, "The properties should never be null");

        final SimpleAddresses addressBook = properties.getSimpleAddresses();
        assertNotNull(addressBook);

        assertEquals(4, addressBook.addresses().size());

        final SimpleAddress firstNode = addressBook.addresses().getFirst();
        assertEquals(1L, firstNode.nodeId());

        final SimpleAddress secondNode = addressBook.addresses().get(1);
        assertEquals(3L, secondNode.nodeId());

        final SimpleAddress thirdNode = addressBook.addresses().get(2);
        assertEquals(20L, thirdNode.nodeId());

        final SimpleAddress fourthNode = addressBook.addresses().get(3);
        assertEquals(95L, fourthNode.nodeId());
    }
}
