// SPDX-License-Identifier: Apache-2.0
package com.swirlds.config.impl.validators.annotation;

import com.swirlds.config.api.ConfigurationBuilder;
import com.swirlds.config.api.validation.ConfigViolationException;
import com.swirlds.config.extensions.sources.SimpleConfigSource;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class ConstraintMethodTest {

    @Test
    public void testNoViolation() {
        // given
        final ConfigurationBuilder configurationBuilder = ConfigurationBuilder.create()
                .withSources(new SimpleConfigSource("method.valueA", "true"))
                .withSources(new SimpleConfigSource("method.valueB", "true"))
                .withConfigDataTypes(ConstraintMethodConfigData.class);
        // then
        Assertions.assertDoesNotThrow(() -> configurationBuilder.build(), "No violation should happen");
    }

    @Test
    public void testViolation() {
        // given
        final ConfigurationBuilder configurationBuilder = ConfigurationBuilder.create()
                .withSources(new SimpleConfigSource("method.valueA", "false"))
                .withSources(new SimpleConfigSource("method.valueB", "true"))
                .withConfigDataTypes(ConstraintMethodConfigData.class);

        // when
        final ConfigViolationException exception = Assertions.assertThrows(
                ConfigViolationException.class,
                () -> configurationBuilder.build(),
                "Violation for @ConstraintMethod should happen");

        // then
        Assertions.assertEquals(1, exception.getViolations().size());
        Assertions.assertTrue(exception.getViolations().get(0).propertyExists());
        Assertions.assertEquals(
                "method.checkA", exception.getViolations().get(0).getPropertyName());
        Assertions.assertEquals("false", exception.getViolations().get(0).getPropertyValue());
        Assertions.assertEquals("error", exception.getViolations().get(0).getMessage());
    }

    @Test
    public void testError() {
        // given
        final ConfigurationBuilder configurationBuilder = ConfigurationBuilder.create()
                .withSources(new SimpleConfigSource("method.valueA", "true"))
                .withSources(new SimpleConfigSource("method.valueB", "false"))
                .withConfigDataTypes(ConstraintMethodConfigData.class);

        // then
        Assertions.assertThrows(
                IllegalStateException.class,
                () -> configurationBuilder.build(),
                "Error in validation should end in illegal state");
    }

    @Test
    public void testErrorInvalidMethod() {
        // given
        final ConfigurationBuilder configurationBuilder = ConfigurationBuilder.create()
                .withSources(new SimpleConfigSource("method.value", "true"))
                .withConfigDataTypes(BrokenConstraintMethodConfigData.class);

        // then
        Assertions.assertThrows(
                IllegalStateException.class,
                () -> configurationBuilder.build(),
                "Error in validation should end in illegal state");
    }
}
