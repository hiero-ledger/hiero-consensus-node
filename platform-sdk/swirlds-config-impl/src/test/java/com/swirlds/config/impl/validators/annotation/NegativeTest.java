// SPDX-License-Identifier: Apache-2.0
package com.swirlds.config.impl.validators.annotation;

import com.swirlds.config.api.ConfigurationBuilder;
import com.swirlds.config.api.validation.ConfigViolationException;
import com.swirlds.config.extensions.sources.SimpleConfigSource;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class NegativeTest {

    @Test
    public void testNoViolation() {
        // given
        final ConfigurationBuilder configurationBuilder = ConfigurationBuilder.create()
                .withSources(new SimpleConfigSource("negative.intValue", -1))
                .withSources(new SimpleConfigSource("negative.longValue", -1L))
                .withSources(new SimpleConfigSource("negative.doubleValue", -1D))
                .withSources(new SimpleConfigSource("negative.floatValue", -1F))
                .withSources(new SimpleConfigSource("negative.shortValue", -1))
                .withSources(new SimpleConfigSource("negative.byteValue", -1))
                .withConfigDataTypes(NegativeConfigData.class);

        // then
        Assertions.assertDoesNotThrow(() -> configurationBuilder.build(), "No violation should happen");
    }

    @Test
    public void testIntViolation() {
        // given
        final ConfigurationBuilder configurationBuilder = ConfigurationBuilder.create()
                .withSources(new SimpleConfigSource("negative.intValue", 1))
                .withSources(new SimpleConfigSource("negative.longValue", -1L))
                .withSources(new SimpleConfigSource("negative.doubleValue", -1D))
                .withSources(new SimpleConfigSource("negative.floatValue", -1F))
                .withSources(new SimpleConfigSource("negative.shortValue", -1))
                .withSources(new SimpleConfigSource("negative.byteValue", -1))
                .withConfigDataTypes(NegativeConfigData.class);

        // when
        final ConfigViolationException exception = Assertions.assertThrows(
                ConfigViolationException.class, () -> configurationBuilder.build(), "Violation for @Min should happen");

        // then
        Assertions.assertEquals(1, exception.getViolations().size());
        Assertions.assertTrue(exception.getViolations().get(0).propertyExists());
        Assertions.assertEquals(
                "negative.intValue", exception.getViolations().get(0).getPropertyName());
        Assertions.assertEquals("1", exception.getViolations().get(0).getPropertyValue());
        Assertions.assertEquals(
                "Value must be < 0", exception.getViolations().get(0).getMessage());
    }

    @Test
    public void testViolations() {
        // given
        final ConfigurationBuilder configurationBuilder = ConfigurationBuilder.create()
                .withSources(new SimpleConfigSource("negative.intValue", 1))
                .withSources(new SimpleConfigSource("negative.longValue", 1L))
                .withSources(new SimpleConfigSource("negative.doubleValue", 1D))
                .withSources(new SimpleConfigSource("negative.floatValue", 1F))
                .withSources(new SimpleConfigSource("negative.shortValue", 1))
                .withSources(new SimpleConfigSource("negative.byteValue", 1))
                .withConfigDataTypes(NegativeConfigData.class);

        // when
        final ConfigViolationException exception = Assertions.assertThrows(
                ConfigViolationException.class,
                () -> configurationBuilder.build(),
                "Violation for @Negative should happen");

        // then
        Assertions.assertEquals(6, exception.getViolations().size());
    }

    @Test
    public void testEdgeCaseViolations() {
        // given
        final ConfigurationBuilder configurationBuilder = ConfigurationBuilder.create()
                .withSources(new SimpleConfigSource("negative.intValue", 0))
                .withSources(new SimpleConfigSource("negative.longValue", 0L))
                .withSources(new SimpleConfigSource("negative.doubleValue", 0D))
                .withSources(new SimpleConfigSource("negative.floatValue", 0F))
                .withSources(new SimpleConfigSource("negative.shortValue", 0))
                .withSources(new SimpleConfigSource("negative.byteValue", 0))
                .withConfigDataTypes(NegativeConfigData.class);

        // when
        final ConfigViolationException exception = Assertions.assertThrows(
                ConfigViolationException.class,
                () -> configurationBuilder.build(),
                "Violation for @Negative should happen");

        // then
        Assertions.assertEquals(6, exception.getViolations().size());
    }
}
