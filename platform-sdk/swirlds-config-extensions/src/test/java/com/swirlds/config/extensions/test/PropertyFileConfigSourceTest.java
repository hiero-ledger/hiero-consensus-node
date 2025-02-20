// SPDX-License-Identifier: Apache-2.0
package com.swirlds.config.extensions.test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.swirlds.config.api.Configuration;
import com.swirlds.config.api.ConfigurationBuilder;
import com.swirlds.config.extensions.sources.PropertyFileConfigSource;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class PropertyFileConfigSourceTest {

    @Test
    void testConfig2HigherOrdinal() {
        // given
        final int ordinal1 = 100;
        final Path configFile1 = Path.of(PropertyFileConfigSourceTest.class
                .getClassLoader()
                .getResource("config1.properties")
                .getFile());
        final PropertyFileConfigSource source1 =
                assertDoesNotThrow(() -> new PropertyFileConfigSource(configFile1, ordinal1));

        final int ordinal2 = 200;
        final Path configFile2 = Path.of(PropertyFileConfigSourceTest.class
                .getClassLoader()
                .getResource("config2.properties")
                .getFile());
        final PropertyFileConfigSource source2 =
                assertDoesNotThrow(() -> new PropertyFileConfigSource(configFile2, ordinal2));

        final Configuration configuration = ConfigurationBuilder.create()
                .withSource(source1)
                .withSource(source2)
                .build();

        // when
        final int fooValue = configuration.getValue("foo", Integer.class);
        final int barValue = configuration.getValue("bar", Integer.class);

        // then
        assertEquals(2, fooValue);
        assertEquals(1, barValue);
    }

    @Test
    void testConfig1HigherOrdinal() {
        // given
        final int ordinal1 = 200;
        final Path configFile1 = Path.of(PropertyFileConfigSourceTest.class
                .getClassLoader()
                .getResource("config1.properties")
                .getFile());
        final PropertyFileConfigSource source1 =
                assertDoesNotThrow(() -> new PropertyFileConfigSource(configFile1, ordinal1));

        final int ordinal2 = 100;
        final Path configFile2 = Path.of(PropertyFileConfigSourceTest.class
                .getClassLoader()
                .getResource("config2.properties")
                .getFile());
        final PropertyFileConfigSource source2 =
                assertDoesNotThrow(() -> new PropertyFileConfigSource(configFile2, ordinal2));

        final Configuration configuration = ConfigurationBuilder.create()
                .withSource(source1)
                .withSource(source2)
                .build();

        // when
        final int fooValue = configuration.getValue("foo", Integer.class);
        final int barValue = configuration.getValue("bar", Integer.class);

        // then
        assertEquals(1, fooValue);
        assertEquals(2, barValue);
    }

    @Test
    void testConfigDefaultOrdinal() {
        // given
        final int ordinal1 = 199;
        final Path configFile1 = Path.of(PropertyFileConfigSourceTest.class
                .getClassLoader()
                .getResource("config1.properties")
                .getFile());
        final PropertyFileConfigSource source1 =
                assertDoesNotThrow(() -> new PropertyFileConfigSource(configFile1, ordinal1));

        final Path configFile2 = Path.of(PropertyFileConfigSourceTest.class
                .getClassLoader()
                .getResource("config2.properties")
                .getFile());
        final PropertyFileConfigSource source2 = assertDoesNotThrow(() -> new PropertyFileConfigSource(configFile2));

        final int ordinal3 = 201;
        final Path configFile3 = Path.of(PropertyFileConfigSourceTest.class
                .getClassLoader()
                .getResource("config3.properties")
                .getFile());
        final PropertyFileConfigSource source3 =
                assertDoesNotThrow(() -> new PropertyFileConfigSource(configFile3, ordinal3));

        final Configuration configuration = ConfigurationBuilder.create()
                .withSource(source1)
                .withSource(source2)
                .withSource(source3)
                .build();

        // when
        final int fooValue = configuration.getValue("foo", Integer.class);
        final int barValue = configuration.getValue("bar", Integer.class);

        // then
        assertEquals(2, fooValue);
        assertEquals(100, barValue);
    }
}
