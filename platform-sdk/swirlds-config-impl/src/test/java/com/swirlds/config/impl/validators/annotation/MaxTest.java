// SPDX-License-Identifier: Apache-2.0
package com.swirlds.config.impl.validators.annotation;

import com.swirlds.config.api.ConfigurationBuilder;
import com.swirlds.config.api.validation.ConfigViolationException;
import com.swirlds.config.extensions.sources.SimpleConfigSource;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class MaxTest {

    @Test
    public void testNoViolation() {
        // given
        final ConfigurationBuilder configurationBuilder = ConfigurationBuilder.create()
                .withSources(new SimpleConfigSource("max.intValue", 1))
                .withSources(new SimpleConfigSource("max.longValue", 1))
                .withSources(new SimpleConfigSource("max.doubleValue", 1))
                .withSources(new SimpleConfigSource("max.floatValue", 1))
                .withSources(new SimpleConfigSource("max.shortValue", 1))
                .withSources(new SimpleConfigSource("max.byteValue", 1))
                .withConfigDataTypes(MaxTestConfigData.class);

        // then
        Assertions.assertDoesNotThrow(() -> configurationBuilder.build(), "No violation should happen");
    }

    @Test
    public void testEdgeCase() {
        // given
        final ConfigurationBuilder configurationBuilder = ConfigurationBuilder.create()
                .withSources(new SimpleConfigSource("max.intValue", 2))
                .withSources(new SimpleConfigSource("max.longValue", 2))
                .withSources(new SimpleConfigSource("max.doubleValue", 2))
                .withSources(new SimpleConfigSource("max.floatValue", 2))
                .withSources(new SimpleConfigSource("max.shortValue", 2))
                .withSources(new SimpleConfigSource("max.byteValue", 2))
                .withConfigDataTypes(MaxTestConfigData.class);

        // then
        Assertions.assertDoesNotThrow(() -> configurationBuilder.build(), "No violation should happen");
    }

    @Test
    public void testIntViolation() {
        // given
        final ConfigurationBuilder configurationBuilder = ConfigurationBuilder.create()
                .withSources(new SimpleConfigSource("max.intValue", 3))
                .withSources(new SimpleConfigSource("max.longValue", 1))
                .withSources(new SimpleConfigSource("max.doubleValue", 1))
                .withSources(new SimpleConfigSource("max.floatValue", 1))
                .withSources(new SimpleConfigSource("max.shortValue", 1))
                .withSources(new SimpleConfigSource("max.byteValue", 1))
                .withConfigDataTypes(MaxTestConfigData.class);

        // when
        final ConfigViolationException exception = Assertions.assertThrows(
                ConfigViolationException.class, () -> configurationBuilder.build(), "Violation for @Min should happen");

        // then
        Assertions.assertEquals(1, exception.getViolations().size());
        Assertions.assertTrue(exception.getViolations().get(0).propertyExists());
        Assertions.assertEquals("max.intValue", exception.getViolations().get(0).getPropertyName());
        Assertions.assertEquals("3", exception.getViolations().get(0).getPropertyValue());
        Assertions.assertEquals(
                "Value must be <= 2", exception.getViolations().get(0).getMessage());
    }

    @Test
    public void testLongViolation() {
        // given
        final ConfigurationBuilder configurationBuilder = ConfigurationBuilder.create()
                .withSources(new SimpleConfigSource("max.intValue", 1))
                .withSources(new SimpleConfigSource("max.longValue", 3))
                .withSources(new SimpleConfigSource("max.doubleValue", 1))
                .withSources(new SimpleConfigSource("max.floatValue", 1))
                .withSources(new SimpleConfigSource("max.shortValue", 1))
                .withSources(new SimpleConfigSource("max.byteValue", 1))
                .withConfigDataTypes(MaxTestConfigData.class);

        // when
        final ConfigViolationException exception = Assertions.assertThrows(
                ConfigViolationException.class, () -> configurationBuilder.build(), "Violation for @Min should happen");

        // then
        Assertions.assertEquals(1, exception.getViolations().size());
        Assertions.assertTrue(exception.getViolations().get(0).propertyExists());
        Assertions.assertEquals(
                "max.longValue", exception.getViolations().get(0).getPropertyName());
        Assertions.assertEquals("3", exception.getViolations().get(0).getPropertyValue());
        Assertions.assertEquals(
                "Value must be <= 2", exception.getViolations().get(0).getMessage());
    }

    @Test
    public void testShortViolation() {
        // given
        final ConfigurationBuilder configurationBuilder = ConfigurationBuilder.create()
                .withSources(new SimpleConfigSource("max.intValue", 1))
                .withSources(new SimpleConfigSource("max.longValue", 1))
                .withSources(new SimpleConfigSource("max.doubleValue", 1))
                .withSources(new SimpleConfigSource("max.floatValue", 1))
                .withSources(new SimpleConfigSource("max.shortValue", 3))
                .withSources(new SimpleConfigSource("max.byteValue", 1))
                .withConfigDataTypes(MaxTestConfigData.class);

        // when
        final ConfigViolationException exception = Assertions.assertThrows(
                ConfigViolationException.class, () -> configurationBuilder.build(), "Violation for @Min should happen");

        // then
        Assertions.assertEquals(1, exception.getViolations().size());
        Assertions.assertTrue(exception.getViolations().get(0).propertyExists());
        Assertions.assertEquals(
                "max.shortValue", exception.getViolations().get(0).getPropertyName());
        Assertions.assertEquals("3", exception.getViolations().get(0).getPropertyValue());
        Assertions.assertEquals(
                "Value must be <= 2", exception.getViolations().get(0).getMessage());
    }

    @Test
    public void testByteViolation() {
        // given
        final ConfigurationBuilder configurationBuilder = ConfigurationBuilder.create()
                .withSources(new SimpleConfigSource("max.intValue", 1))
                .withSources(new SimpleConfigSource("max.longValue", 1))
                .withSources(new SimpleConfigSource("max.doubleValue", 1))
                .withSources(new SimpleConfigSource("max.floatValue", 1))
                .withSources(new SimpleConfigSource("max.shortValue", 1))
                .withSources(new SimpleConfigSource("max.byteValue", 3))
                .withConfigDataTypes(MaxTestConfigData.class);

        // when
        final ConfigViolationException exception = Assertions.assertThrows(
                ConfigViolationException.class, () -> configurationBuilder.build(), "Violation for @Min should happen");

        // then
        Assertions.assertEquals(1, exception.getViolations().size());
        Assertions.assertTrue(exception.getViolations().get(0).propertyExists());
        Assertions.assertEquals(
                "max.byteValue", exception.getViolations().get(0).getPropertyName());
        Assertions.assertEquals("3", exception.getViolations().get(0).getPropertyValue());
        Assertions.assertEquals(
                "Value must be <= 2", exception.getViolations().get(0).getMessage());
    }
}
