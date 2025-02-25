// SPDX-License-Identifier: Apache-2.0
package com.swirlds.config.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.swirlds.config.api.Configuration;
import com.swirlds.config.api.ConfigurationBuilder;
import com.swirlds.config.extensions.sources.SimpleConfigSource;
import java.util.List;
import java.util.NoSuchElementException;
import org.junit.jupiter.api.Test;

class ConfigApiListTests {

    @Test
    void readListProperty() {
        // given
        final Configuration configuration = ConfigurationBuilder.create()
                .withSource(new SimpleConfigSource().withIntegerValues("testNumbers", List.of(1, 2, 3)))
                .build();

        // when
        final List<Integer> values = configuration.getValues("testNumbers", Integer.class);

        // then
        assertEquals(3, values.size(), "A property that is defined as list should be parsed correctly");
        assertEquals(1, values.get(0), "A property that is defined as list should contain the defined values");
        assertEquals(2, values.get(1), "A property that is defined as list should contain the defined values");
        assertEquals(3, values.get(2), "A property that is defined as list should contain the defined values");
    }

    @Test
    void readListPropertyWithOneEntry() {
        // given
        final Configuration configuration = ConfigurationBuilder.create()
                .withSource(new SimpleConfigSource("testNumbers", 123))
                .build();

        // when
        final List<Integer> values = configuration.getValues("testNumbers", Integer.class);

        // then
        assertEquals(1, values.size(), "A property that is defined as list should be parsed correctly");
        assertEquals(123, values.get(0), "A property that is defined as list should contain the defined values");
    }

    @Test
    void readBadListProperty() {
        // given
        final Configuration configuration = ConfigurationBuilder.create()
                .withSource(new SimpleConfigSource("testNumbers", "1,2,   3,4"))
                .build();

        // then
        assertThrows(
                IllegalArgumentException.class,
                () -> configuration.getValues("testNumbers", Integer.class),
                "given list property should not be parsed correctly");
    }

    @Test
    void readDefaultListProperty() {
        // given
        final Configuration configuration = ConfigurationBuilder.create().build();

        // when
        final List<Integer> values = configuration.getValues("testNumbers", Integer.class, List.of(6, 7, 8));

        // then
        assertEquals(3, values.size(), "The default value should be used since no value is defined by the config");
        assertEquals(6, values.get(0), "Should be part of the list since it is part of the default");
        assertEquals(7, values.get(1), "Should be part of the list since it is part of the default");
        assertEquals(8, values.get(2), "Should be part of the list since it is part of the default");
    }

    @Test
    void readNullDefaultListProperty() {
        // given
        final Configuration configuration = ConfigurationBuilder.create().build();

        // when
        final List<Integer> values = configuration.getValues("testNumbers", Integer.class, null);

        // then
        assertNull(values, "Null should be a valid default value");
    }

    @Test
    void checkListPropertyImmutable() {
        // given
        final Configuration configuration = ConfigurationBuilder.create()
                .withSource(new SimpleConfigSource("testNumbers", "1,2,3"))
                .build();

        // when
        final List<Integer> values = configuration.getValues("testNumbers", Integer.class);

        // then
        assertThrows(
                UnsupportedOperationException.class,
                () -> values.add(10),
                "List properties should always be immutable");
    }

    @Test
    void testNotDefinedEmptyList() {
        // given
        final Configuration configuration = ConfigurationBuilder.create().build();

        // then
        assertThrows(NoSuchElementException.class, () -> configuration.getValues("sample.list"));
        assertThrows(NoSuchElementException.class, () -> configuration.getValues("sample.list", String.class));
        assertThrows(NoSuchElementException.class, () -> configuration.getValues("sample.list", Integer.class));
    }
}
