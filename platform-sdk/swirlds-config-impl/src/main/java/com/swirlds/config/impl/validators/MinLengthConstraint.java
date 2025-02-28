// SPDX-License-Identifier: Apache-2.0
package com.swirlds.config.impl.validators;

import com.swirlds.config.api.validation.ConfigPropertyConstraint;
import com.swirlds.config.api.validation.ConfigViolation;
import com.swirlds.config.api.validation.PropertyMetadata;
import java.util.Objects;

/**
 * Implementation of {@link ConfigPropertyConstraint} that results in a violation if the property value has less
 * characters than the defined length.
 */
public class MinLengthConstraint implements ConfigPropertyConstraint<String> {

    private final int minLength;

    /**
     * Creates the constraint.
     *
     * @param minLength the minimal allowed length
     */
    public MinLengthConstraint(final int minLength) {
        this.minLength = minLength;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public ConfigViolation check(final PropertyMetadata<String> metadata) {
        Objects.requireNonNull(metadata, "metadata must not be null");
        if (!metadata.exists()) {
            final String message = "Property '" + metadata.getName() + "' must be defined";
            return DefaultConfigViolation.of(metadata, message);
        }
        if (metadata.getRawValue() == null) {
            final String message = "Property '" + metadata.getName() + "' must not be null.";
            return DefaultConfigViolation.of(metadata, message);
        }
        final int valueLength = metadata.getRawValue().length();
        if (valueLength < minLength) {
            final String message = "String value of Property '" + metadata.getName() + "' must have a minimum "
                    + "length of '" + minLength + "'";
            return DefaultConfigViolation.of(metadata, message);
        }
        return null;
    }
}
