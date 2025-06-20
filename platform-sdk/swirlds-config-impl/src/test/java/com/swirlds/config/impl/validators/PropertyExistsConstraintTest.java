// SPDX-License-Identifier: Apache-2.0
package com.swirlds.config.impl.validators;

import com.swirlds.config.api.converter.ConfigConverter;
import com.swirlds.config.api.validation.ConfigViolation;
import com.swirlds.config.api.validation.PropertyMetadata;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class PropertyExistsConstraintTest {

    @Test
    public void testPassNull() {
        // given
        final PropertyExistsConstraint<String> constraint = new PropertyExistsConstraint<>();
        final PropertyMetadata<String> metadata = null;

        // then
        Assertions.assertThrows(NullPointerException.class, () -> constraint.check(metadata));
    }

    @Test
    public void testValid() {
        // given
        final PropertyExistsConstraint<String> constraint = new PropertyExistsConstraint<>();
        final String propName = "app.property";
        final String value = "12345678";
        final Class<String> propertyClass = String.class;
        final boolean present = true;
        final ConfigConverter<String> converter = v -> v;
        final PropertyMetadata<String> metadata =
                new DummyMetadata<>(propName, value, propertyClass, present, converter);

        // when
        final ConfigViolation configViolation = constraint.check(metadata);

        // then
        Assertions.assertNull(configViolation);
    }

    @Test
    public void testContrainsNoProperty() {
        // given
        final PropertyExistsConstraint<String> constraint = new PropertyExistsConstraint<>();
        final String propName = "app.property";
        final String value = null;
        final Class<String> propertyClass = String.class;
        final boolean present = false;
        final ConfigConverter<String> converter = v -> v;
        final PropertyMetadata<String> metadata =
                new DummyMetadata<>(propName, value, propertyClass, present, converter);

        // when
        final ConfigViolation configViolation = constraint.check(metadata);

        // then
        Assertions.assertNotNull(configViolation);
        Assertions.assertEquals("app.property", configViolation.getPropertyName());
        Assertions.assertNull(configViolation.getPropertyValue());
        Assertions.assertFalse(configViolation.propertyExists());
    }

    @Test
    public void testInvalid() {
        // given
        final PropertyExistsConstraint<String> constraint = new PropertyExistsConstraint<>();
        final String propName = "app.property";
        final String value = null;
        final Class<String> propertyClass = String.class;
        final boolean present = false;
        final ConfigConverter<String> converter = v -> v;
        final PropertyMetadata<String> metadata =
                new DummyMetadata<>(propName, value, propertyClass, present, converter);

        // when
        final ConfigViolation configViolation = constraint.check(metadata);

        // then
        Assertions.assertNotNull(configViolation);
        Assertions.assertEquals("app.property", configViolation.getPropertyName());
        Assertions.assertFalse(configViolation.propertyExists());
    }
}
