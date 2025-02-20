// SPDX-License-Identifier: Apache-2.0
package com.swirlds.config.impl.validators;

import com.swirlds.config.api.validation.ConfigPropertyConstraint;
import com.swirlds.config.api.validation.ConfigViolation;
import com.swirlds.config.api.validation.PropertyMetadata;
import com.swirlds.config.impl.internal.ConfigNumberUtils;
import java.util.Objects;

/**
 * An implementation of {@link ConfigPropertyConstraint} that will result in a violation if the property value is lower
 * than a defined minimum value.
 *
 * @param <T> type of the property value
 */
public class MinConstraint<T extends Number> implements ConfigPropertyConstraint<T> {

    private final T min;

    /**
     * Creates the constraint.
     *
     * @param min the minimum value that is allowed
     */
    public MinConstraint(final T min) {
        this.min = min;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public ConfigViolation check(final PropertyMetadata<T> metadata) {
        Objects.requireNonNull(metadata, "metadata must not be null");
        if (!metadata.exists()) {
            final String message = "Property '" + metadata.getName() + "' must be defined";
            return DefaultConfigViolation.of(metadata, message);
        }
        if (metadata.getRawValue() == null) {
            final String message = "Property '" + metadata.getName() + "' must not be null.";
            return DefaultConfigViolation.of(metadata, message);
        }
        final T convertedValue = metadata.getConverter().convert(metadata.getRawValue());
        if (ConfigNumberUtils.compare(convertedValue, metadata.getValueType(), min) < 0) {
            final String message = "Value of Property '" + metadata.getName() + "' must be >= '" + min + "'";
            return DefaultConfigViolation.of(metadata, message);
        } else {
            return null;
        }
    }
}
