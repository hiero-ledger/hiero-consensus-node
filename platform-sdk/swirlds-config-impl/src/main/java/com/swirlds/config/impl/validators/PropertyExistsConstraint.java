// SPDX-License-Identifier: Apache-2.0
package com.swirlds.config.impl.validators;

import com.swirlds.config.api.validation.ConfigPropertyConstraint;
import com.swirlds.config.api.validation.ConfigViolation;
import com.swirlds.config.api.validation.PropertyMetadata;
import java.util.Objects;

/**
 * Implementation of {@link ConfigPropertyConstraint} that results in a violation of the property is not defined.
 *
 * @param <T> type of the property value
 */
public class PropertyExistsConstraint<T> implements ConfigPropertyConstraint<T> {

    /**
     * {@inheritDoc}
     */
    @Override
    public ConfigViolation check(final PropertyMetadata<T> metadata) {
        Objects.requireNonNull(metadata, "metadata must not be null");
        if (metadata.exists()) {
            return null;
        }
        final String message = "Property '" + metadata.getName() + "' must be defined";
        return DefaultConfigViolation.of(metadata, message);
    }
}
