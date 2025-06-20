// SPDX-License-Identifier: Apache-2.0
package com.swirlds.config.impl.validators;

import com.swirlds.config.api.converter.ConfigConverter;
import com.swirlds.config.api.validation.ConfigViolation;
import com.swirlds.config.api.validation.PropertyMetadata;
import com.swirlds.config.impl.converters.IntegerConverter;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class PropertyValueConvertableConstraintTest {

    @Test
    public void testPassNull() {
        // given
        final PropertyValueConvertableConstraint<Integer> constraint = new PropertyValueConvertableConstraint<>();
        final PropertyMetadata<Integer> metadata = null;

        // then
        Assertions.assertThrows(NullPointerException.class, () -> constraint.check(metadata));
    }

    @Test
    public void testValid() {
        // given
        final PropertyValueConvertableConstraint<Integer> constraint = new PropertyValueConvertableConstraint<>();
        final String propName = "app.property";
        final String value = "10";
        final Class<Integer> propertyClass = Integer.class;
        final boolean present = true;
        final ConfigConverter<Integer> converter = new IntegerConverter();
        final PropertyMetadata<Integer> metadata =
                new DummyMetadata<>(propName, value, propertyClass, present, converter);

        // when
        final ConfigViolation configViolation = constraint.check(metadata);

        // then
        Assertions.assertNull(configViolation);
    }

    @Test
    public void testContrainsNoProperty() {
        // given
        final PropertyValueConvertableConstraint<Integer> constraint = new PropertyValueConvertableConstraint<>();
        final String propName = "app.property";
        final String value = null;
        final Class<Integer> propertyClass = Integer.class;
        final boolean present = false;
        final ConfigConverter<Integer> converter = new IntegerConverter();
        final PropertyMetadata<Integer> metadata =
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
    public void testContrainsPropertyValueNull() {
        // given
        final PropertyValueConvertableConstraint<Integer> constraint = new PropertyValueConvertableConstraint<>();
        final String propName = "app.property";
        final String value = null;
        final Class<Integer> propertyClass = Integer.class;
        final boolean present = true;
        final ConfigConverter<Integer> converter = new IntegerConverter();
        final PropertyMetadata<Integer> metadata =
                new DummyMetadata<>(propName, value, propertyClass, present, converter);

        // when
        final ConfigViolation configViolation = constraint.check(metadata);

        // then
        Assertions.assertNotNull(configViolation);
        Assertions.assertEquals("app.property", configViolation.getPropertyName());
        Assertions.assertNull(configViolation.getPropertyValue());
        Assertions.assertTrue(configViolation.propertyExists());
    }

    @Test
    public void testInvalid() {
        // given
        final PropertyValueConvertableConstraint<Integer> constraint = new PropertyValueConvertableConstraint<>();
        final String propName = "app.property";
        final String value = "no-number";
        final Class<Integer> propertyClass = Integer.class;
        final boolean present = true;
        final ConfigConverter<Integer> converter = new IntegerConverter();
        final PropertyMetadata<Integer> metadata =
                new DummyMetadata<>(propName, value, propertyClass, present, converter);

        // when
        final ConfigViolation configViolation = constraint.check(metadata);

        // then
        Assertions.assertNotNull(configViolation);
        Assertions.assertEquals("app.property", configViolation.getPropertyName());
        Assertions.assertEquals("no-number", configViolation.getPropertyValue());
        Assertions.assertTrue(configViolation.propertyExists());
    }
}
