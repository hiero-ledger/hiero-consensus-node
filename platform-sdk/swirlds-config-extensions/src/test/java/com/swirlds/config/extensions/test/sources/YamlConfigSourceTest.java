// SPDX-License-Identifier: Apache-2.0
package com.swirlds.config.extensions.test.sources;

import com.swirlds.config.extensions.sources.YamlConfigSource;
import java.io.UncheckedIOException;
import java.util.List;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class YamlConfigSourceTest {

    @Test
    void testPropertyConfigSourceWhenPropertyFileNotFound() {
        // given
        final String nonExistentFile = "non-existent-file.yaml";

        // then
        Assertions.assertThrows(UncheckedIOException.class, () -> new YamlConfigSource(nonExistentFile, 1));
    }

    @Test
    void testLoadProperties() {
        // given
        final String existingFile = "test.yaml";

        // when
        final YamlConfigSource yamlConfigSource = new YamlConfigSource(existingFile, 1);

        // then
        Assertions.assertNotNull(yamlConfigSource);

        Assertions.assertTrue(yamlConfigSource.isListProperty("gossip.interfaceBindings"));
        final List<String> interfaceBindings = yamlConfigSource.getListValue("gossip.interfaceBindings");
        Assertions.assertEquals(4, interfaceBindings.size());
        Assertions.assertEquals("{\"nodeId\":0,\"hostname\":\"10.10.10.1\",\"port\":1234}", interfaceBindings.get(0));
        Assertions.assertEquals("{\"nodeId\":0,\"hostname\":\"10.10.10.2\",\"port\":1234}", interfaceBindings.get(1));
        Assertions.assertEquals("{\"nodeId\":1,\"hostname\":\"10.10.10.3\",\"port\":1234}", interfaceBindings.get(2));
        Assertions.assertEquals(
                "{\"nodeId\":4,\"hostname\":\"2001:db8:3333:4444:5555:6666:7777:8888\",\"port\":1234}",
                interfaceBindings.get(3));

        Assertions.assertEquals("random", yamlConfigSource.getValue("gossip.randomStringValue"));
        Assertions.assertEquals("42", yamlConfigSource.getValue("gossip.randomIntValue"));
        Assertions.assertEquals(
                "{\"nodeId\":5,\"hostname\":\"10.10.10.45\",\"port\":23424}",
                yamlConfigSource.getValue("gossip.randomObj"));

        Assertions.assertTrue(yamlConfigSource.isListProperty("gossip.randomList"));
        final List<String> randomList = yamlConfigSource.getListValue("gossip.randomList");
        Assertions.assertEquals(2, randomList.size());
        Assertions.assertEquals("foo", randomList.get(0));
        Assertions.assertEquals("bar", randomList.get(1));

        Assertions.assertTrue(yamlConfigSource.isListProperty("gossip.randomListOfList"));
    }

    @Test
    void testYamlFileWithNoList() {
        // given
        final String ymlFile = "withNoList.yaml";

        // when
        final YamlConfigSource yamlConfigSource = new YamlConfigSource(ymlFile, 1);

        // then
        Assertions.assertEquals("random", yamlConfigSource.getValue("gossip.randomStringValue"));
        Assertions.assertEquals("42", yamlConfigSource.getValue("gossip.randomIntValue"));
        Assertions.assertEquals("random2", yamlConfigSource.getValue("other.randomStringValue"));
        Assertions.assertEquals("43", yamlConfigSource.getValue("other.randomIntValue"));
        Assertions.assertEquals("random3", yamlConfigSource.getValue("a.b.randomStringValue"));
        Assertions.assertEquals("44", yamlConfigSource.getValue("a.b.randomIntValue"));
    }

    @Test
    void testYamlFileWithMoreThanTwoLevels() {
        // given
        final String ymlFile = "moreThanTwoLevels.yaml";

        // when
        final YamlConfigSource yamlConfigSource = new YamlConfigSource(ymlFile, 1);

        // then
        Assertions.assertEquals("{\"c\":1337}", yamlConfigSource.getValue("a.b"));
    }
}
