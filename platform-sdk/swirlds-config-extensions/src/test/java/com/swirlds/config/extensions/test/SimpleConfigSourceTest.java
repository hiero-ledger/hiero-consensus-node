// SPDX-License-Identifier: Apache-2.0
package com.swirlds.config.extensions.test;

import com.swirlds.config.api.Configuration;
import com.swirlds.config.api.ConfigurationBuilder;
import com.swirlds.config.extensions.sources.SimpleConfigSource;
import java.util.List;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class SimpleConfigSourceTest {

    @Test
    void testEmpty() {
        // given
        final SimpleConfigSource configSource = new SimpleConfigSource();

        // when
        final Configuration configuration =
                ConfigurationBuilder.create().withSource(configSource).build();

        // then
        Assertions.assertTrue(
                configuration.getPropertyNames().collect(Collectors.toSet()).isEmpty());
    }

    @Test
    void testValueForAllTypes() {
        // given
        final SimpleConfigSource configSource1 = new SimpleConfigSource("string", "a");
        final SimpleConfigSource configSource2 = new SimpleConfigSource("int", 7);
        final SimpleConfigSource configSource3 = new SimpleConfigSource("long", 7L);
        final SimpleConfigSource configSource4 = new SimpleConfigSource("double", 1.0D);
        final SimpleConfigSource configSource5 = new SimpleConfigSource("boolean", true);

        // when
        final Configuration configuration = ConfigurationBuilder.create()
                .withSource(configSource1)
                .withSource(configSource2)
                .withSource(configSource3)
                .withSource(configSource4)
                .withSource(configSource5)
                .build();

        // then
        Assertions.assertEquals("a", configuration.getValue("string"), "the defined value must be returned");
        Assertions.assertEquals(7, configuration.getValue("int", Integer.TYPE), "the defined value must be returned");
        Assertions.assertEquals(7, configuration.getValue("int", Integer.class), "the defined value must be returned");
        Assertions.assertEquals(7L, configuration.getValue("long", Long.TYPE), "the defined value must be returned");
        Assertions.assertEquals(7L, configuration.getValue("long", Long.class), "the defined value must be returned");
        Assertions.assertEquals(
                1.0D, configuration.getValue("double", Double.TYPE), "the defined value must be returned");
        Assertions.assertEquals(
                1.0D, configuration.getValue("double", Double.class), "the defined value must be returned");
        Assertions.assertTrue(configuration.getValue("boolean", Boolean.TYPE), "the defined value must be returned");
        Assertions.assertTrue(configuration.getValue("boolean", Boolean.class), "the defined value must be returned");
    }

    @Test
    void testNullValueForAlltypes() {
        // given
        final SimpleConfigSource configSource1 = new SimpleConfigSource("string", (String) null);
        final SimpleConfigSource configSource2 = new SimpleConfigSource("int", (Integer) null);
        final SimpleConfigSource configSource3 = new SimpleConfigSource("long", (Long) null);
        final SimpleConfigSource configSource4 = new SimpleConfigSource("double", (Double) null);
        final SimpleConfigSource configSource5 = new SimpleConfigSource("boolean", (Boolean) null);

        // when
        final Configuration configuration = ConfigurationBuilder.create()
                .withSource(configSource1)
                .withSource(configSource2)
                .withSource(configSource3)
                .withSource(configSource4)
                .withSource(configSource5)
                .build();

        // then
        Assertions.assertNull(configuration.getValue("string"), "the defined value (null) must be returned");
        Assertions.assertNull(configuration.getValue("int", Integer.TYPE), "the defined value (null) must be returned");
        Assertions.assertNull(
                configuration.getValue("int", Integer.class), "the defined value (null) must be " + "returned");
        Assertions.assertNull(configuration.getValue("long", Long.TYPE), "the defined value (null) must be returned");
        Assertions.assertNull(configuration.getValue("long", Long.class), "the defined value (null) must be returned");
        Assertions.assertNull(
                configuration.getValue("double", Double.TYPE), "the defined value (null) must be returned");
        Assertions.assertNull(
                configuration.getValue("double", Double.class), "the defined value (null) must be returned");
        Assertions.assertNull(
                configuration.getValue("boolean", Boolean.TYPE), "the defined value (null) must be returned");
        Assertions.assertNull(
                configuration.getValue("boolean", Boolean.class), "the defined value (null) must be returned");
    }

    @Test
    void testValueEmptyListsForAllTypes() {
        // given
        final SimpleConfigSource configSource1 = new SimpleConfigSource().withStringValues("string", List.of());
        final SimpleConfigSource configSource2 = new SimpleConfigSource().withIntegerValues("int", List.of());
        final SimpleConfigSource configSource3 = new SimpleConfigSource().withLongValues("long", List.of());
        final SimpleConfigSource configSource4 = new SimpleConfigSource().withDoubleValues("double", List.of());
        final SimpleConfigSource configSource5 = new SimpleConfigSource().withBooleanValues("boolean", List.of());

        // when
        final Configuration configuration = ConfigurationBuilder.create()
                .withSource(configSource1)
                .withSource(configSource2)
                .withSource(configSource3)
                .withSource(configSource4)
                .withSource(configSource5)
                .build();

        // then
        Assertions.assertIterableEquals(
                List.of(), configuration.getValues("string"), "the defined value (an empty list) must be returned");
        Assertions.assertIterableEquals(
                List.of(),
                configuration.getValues("int", Integer.TYPE),
                "the defined value (an empty list) must be returned");
        Assertions.assertIterableEquals(
                List.of(),
                configuration.getValues("int", Integer.class),
                "the defined value (an empty list) must be returned");
        Assertions.assertIterableEquals(
                List.of(),
                configuration.getValues("long", Long.TYPE),
                "the defined value (an empty list) must be returned");
        Assertions.assertIterableEquals(
                List.of(),
                configuration.getValues("long", Long.class),
                "the defined value (an empty list) must be returned");
        Assertions.assertIterableEquals(
                List.of(),
                configuration.getValues("double", Double.TYPE),
                "the defined value (an empty list) must be returned");
        Assertions.assertIterableEquals(
                List.of(),
                configuration.getValues("double", Double.class),
                "the defined value (an empty list) must be returned");
        Assertions.assertIterableEquals(
                List.of(),
                configuration.getValues("boolean", Boolean.TYPE),
                "the defined value (an empty list) must be returned");
        Assertions.assertIterableEquals(
                List.of(),
                configuration.getValues("boolean", Boolean.class),
                "the defined value (an empty list) must be returned");
    }

    @Test
    void testValueListsForAllTypes() {
        // given
        final SimpleConfigSource configSource1 =
                new SimpleConfigSource().withStringValues("string", List.of("a", "b", "c"));
        final SimpleConfigSource configSource2 =
                new SimpleConfigSource().withIntegerValues("int", List.of(Integer.MIN_VALUE, 0, Integer.MAX_VALUE));
        final SimpleConfigSource configSource3 =
                new SimpleConfigSource().withLongValues("long", List.of(Long.MIN_VALUE, 0L, Long.MAX_VALUE));
        final SimpleConfigSource configSource4 =
                new SimpleConfigSource().withDoubleValues("double", List.of(Double.MIN_VALUE, 0D, Double.MAX_VALUE));
        final SimpleConfigSource configSource5 =
                new SimpleConfigSource().withBooleanValues("boolean", List.of(true, false));

        // when
        final Configuration configuration = ConfigurationBuilder.create()
                .withSource(configSource1)
                .withSource(configSource2)
                .withSource(configSource3)
                .withSource(configSource4)
                .withSource(configSource5)
                .build();

        // then
        Assertions.assertIterableEquals(
                List.of("a", "b", "c"),
                configuration.getValues("string"),
                "the defined value (an empty list) must be returned");
        Assertions.assertIterableEquals(
                List.of(Integer.MIN_VALUE, 0, Integer.MAX_VALUE),
                configuration.getValues("int", Integer.TYPE),
                "the defined value (an empty list) must be returned");
        Assertions.assertIterableEquals(
                List.of(Integer.MIN_VALUE, 0, Integer.MAX_VALUE),
                configuration.getValues("int", Integer.class),
                "the defined value (an empty list) must be returned");
        Assertions.assertIterableEquals(
                List.of(Long.MIN_VALUE, 0L, Long.MAX_VALUE),
                configuration.getValues("long", Long.TYPE),
                "the defined value (an empty list) must be returned");
        Assertions.assertIterableEquals(
                List.of(Long.MIN_VALUE, 0L, Long.MAX_VALUE),
                configuration.getValues("long", Long.class),
                "the defined value (an empty list) must be returned");
        Assertions.assertIterableEquals(
                List.of(Double.MIN_VALUE, 0D, Double.MAX_VALUE),
                configuration.getValues("double", Double.TYPE),
                "the defined value (an empty list) must be returned");
        Assertions.assertIterableEquals(
                List.of(Double.MIN_VALUE, 0D, Double.MAX_VALUE),
                configuration.getValues("double", Double.class),
                "the defined value (an empty list) must be returned");
        Assertions.assertIterableEquals(
                List.of(true, false),
                configuration.getValues("boolean", Boolean.TYPE),
                "the defined value (an empty list) must be returned");
        Assertions.assertIterableEquals(
                List.of(true, false),
                configuration.getValues("boolean", Boolean.class),
                "the defined value (an empty list) must be returned");
    }

    @Test
    void testNullValueListsForAllTypes() {
        // given
        final SimpleConfigSource configSource1 = new SimpleConfigSource().withStringValues("string", null);
        final SimpleConfigSource configSource2 = new SimpleConfigSource().withIntegerValues("int", null);
        final SimpleConfigSource configSource3 = new SimpleConfigSource().withLongValues("long", null);
        final SimpleConfigSource configSource4 = new SimpleConfigSource().withDoubleValues("double", null);
        final SimpleConfigSource configSource5 = new SimpleConfigSource().withBooleanValues("boolean", null);

        // when
        final Configuration configuration = ConfigurationBuilder.create()
                .withSource(configSource1)
                .withSource(configSource2)
                .withSource(configSource3)
                .withSource(configSource4)
                .withSource(configSource5)
                .build();

        // then
        Assertions.assertNull(configuration.getValues("string"), "the defined value (null) must be returned");
        Assertions.assertNull(
                configuration.getValues("int", Integer.TYPE), "the defined value (null) must be " + "returned");
        Assertions.assertNull(
                configuration.getValues("int", Integer.class), "the defined value (null) must be returned");
        Assertions.assertNull(configuration.getValues("long", Long.TYPE), "the defined value (null) must be returned");
        Assertions.assertNull(configuration.getValues("long", Long.class), "the defined value (null) must be returned");
        Assertions.assertNull(
                configuration.getValues("double", Double.TYPE), "the defined value (null) must be returned");
        Assertions.assertNull(
                configuration.getValues("double", Double.class), "the defined value (null) must be returned");
        Assertions.assertNull(
                configuration.getValues("boolean", Boolean.TYPE), "the defined value (null) must be returned");
        Assertions.assertNull(
                configuration.getValues("boolean", Boolean.class), "the defined value (null) must be returned");
    }
}
