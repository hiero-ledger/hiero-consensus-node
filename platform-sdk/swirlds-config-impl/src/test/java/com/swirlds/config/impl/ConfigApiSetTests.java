// SPDX-License-Identifier: Apache-2.0
package com.swirlds.config.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.swirlds.config.api.Configuration;
import com.swirlds.config.api.ConfigurationBuilder;
import com.swirlds.config.extensions.sources.SimpleConfigSource;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ConfigApiSetTests {

    @Test
    void readSetProperty() {
        // given
        final Configuration configuration = ConfigurationBuilder.create()
                .withSource(new SimpleConfigSource().withIntegerValues("testNumbers", List.of(1, 2, 3)))
                .build();

        // when
        final Set<Integer> values = configuration.getValueSet("testNumbers", Integer.class);

        // then
        assertEquals(3, values.size(), "A property that is defined as set should be parsed correctly");
        assertTrue(values.contains(1), "A property that is defined as set should contain the defined values");
        assertTrue(values.contains(2), "A property that is defined as set should contain the defined values");
        assertTrue(values.contains(3), "A property that is defined as set should contain the defined values");
    }

    @Test
    void readSetPropertyWithOneEntry() {
        // given
        final Configuration configuration = ConfigurationBuilder.create()
                .withSource(new SimpleConfigSource("testNumbers", 123))
                .build();

        // when
        final Set<Integer> values = configuration.getValueSet("testNumbers", Integer.class);

        // then
        assertEquals(1, values.size(), "A property that is defined as set should be parsed correctly");
        assertTrue(values.contains(123), "A property that is defined as set should contain the defined values");
    }

    @Test
    void readBadSetProperty() {
        // given
        final Configuration configuration = ConfigurationBuilder.create()
                .withSource(new SimpleConfigSource("testNumbers", "1,2,   3,4"))
                .build();

        // then
        assertThrows(
                IllegalArgumentException.class,
                () -> configuration.getValueSet("testNumbers", Integer.class),
                "given set property should not be parsed correctly");
    }

    @Test
    void readDefaultSetProperty() {
        // given
        final Configuration configuration = ConfigurationBuilder.create().build();

        // when
        final Set<Integer> values = configuration.getValueSet("testNumbers", Integer.class, Set.of(6, 7, 8));

        // then
        assertEquals(3, values.size(), "The default value should be used since no value is defined by the config");
        assertTrue(values.contains(6), "Should be part of the set since it is part of the default");
        assertTrue(values.contains(7), "Should be part of the set since it is part of the default");
        assertTrue(values.contains(8), "Should be part of the set since it is part of the default");
    }

    @Test
    void readNullDefaultSetProperty() {
        // given
        final Configuration configuration = ConfigurationBuilder.create().build();

        // when
        final Set<Integer> values = configuration.getValueSet("testNumbers", Integer.class, null);

        // then
        assertNull(values, "Null should be a valid default value");
    }

    @Test
    void checkSetPropertyImmutable() {
        // given
        final Configuration configuration = ConfigurationBuilder.create()
                .withSource(new SimpleConfigSource("testNumbers", "1,2,3"))
                .build();

        // when
        final Set<Integer> values = configuration.getValueSet("testNumbers", Integer.class);

        // then
        assertThrows(
                UnsupportedOperationException.class, () -> values.add(10), "Set properties should always be immutable");
    }

    @Test
    void testNotDefinedEmptySet() {
        // given
        final Configuration configuration = ConfigurationBuilder.create().build();

        // then
        assertThrows(NoSuchElementException.class, () -> configuration.getValueSet("sample.list"));
        assertThrows(NoSuchElementException.class, () -> configuration.getValueSet("sample.list", String.class));
        assertThrows(NoSuchElementException.class, () -> configuration.getValueSet("sample.list", Integer.class));
    }
}
