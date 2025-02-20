// SPDX-License-Identifier: Apache-2.0
package com.swirlds.config.extensions.test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.swirlds.config.api.Configuration;
import com.swirlds.config.api.ConfigurationBuilder;
import com.swirlds.config.extensions.sources.ClasspathFileConfigSource;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class ClasspathFileConfigSourceTest {

    @Test
    void testConfig2HigherOrdinal() {
        // given
        final int ordinal1 = 100;
        final ClasspathFileConfigSource source1 =
                assertDoesNotThrow(() -> new ClasspathFileConfigSource(Path.of("config1.properties"), ordinal1));

        final int ordinal2 = 200;
        final ClasspathFileConfigSource source2 =
                assertDoesNotThrow(() -> new ClasspathFileConfigSource(Path.of("config2.properties"), ordinal2));

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
        final ClasspathFileConfigSource source1 =
                assertDoesNotThrow(() -> new ClasspathFileConfigSource(Path.of("config1.properties"), ordinal1));

        final int ordinal2 = 100;
        final ClasspathFileConfigSource source2 =
                assertDoesNotThrow(() -> new ClasspathFileConfigSource(Path.of("config2.properties"), ordinal2));

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
        final ClasspathFileConfigSource source1 =
                assertDoesNotThrow(() -> new ClasspathFileConfigSource(Path.of("config1.properties"), ordinal1));

        final ClasspathFileConfigSource source2 =
                assertDoesNotThrow(() -> new ClasspathFileConfigSource(Path.of("config2.properties")));

        final int ordinal3 = 201;
        final ClasspathFileConfigSource source3 =
                assertDoesNotThrow(() -> new ClasspathFileConfigSource(Path.of("config3.properties"), ordinal3));

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
