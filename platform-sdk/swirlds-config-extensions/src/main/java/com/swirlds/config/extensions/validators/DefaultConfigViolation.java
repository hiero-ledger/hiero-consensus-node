// SPDX-License-Identifier: Apache-2.0
package com.swirlds.config.extensions.validators;

import com.swirlds.config.api.validation.ConfigViolation;
import com.swirlds.config.api.validation.PropertyMetadata;
import java.util.Objects;

/**
 * An immutable default implementation of {@link ConfigViolation}.
 *
 * @param propertyName the name of the property that causes the violation
 * @param value        the value of the property that causes the violation
 * @param exists       defines whether the property that caused the violation exists
 * @param message      message of the violation
 */
public record DefaultConfigViolation(String propertyName, String value, boolean exists, String message)
        implements ConfigViolation {

    /**
     * Factory method to create a {@link ConfigViolation}.
     *
     * @param metadata the metadata of the property that causes the violation
     * @param message  the violation message
     * @return a new {@link ConfigViolation} instance
     */
    public static ConfigViolation of(final PropertyMetadata<?> metadata, final String message) {
        Objects.requireNonNull(metadata, "metadata can not be null");
        return new DefaultConfigViolation(metadata.getName(), metadata.getRawValue(), metadata.exists(), message);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String getPropertyName() {
        return propertyName();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String getMessage() {
        return message();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String getPropertyValue() {
        return value();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean propertyExists() {
        return exists();
    }
}
