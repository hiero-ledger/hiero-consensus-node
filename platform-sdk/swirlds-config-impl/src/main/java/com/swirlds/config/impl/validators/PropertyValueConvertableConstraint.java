// SPDX-License-Identifier: Apache-2.0
package com.swirlds.config.impl.validators;

import com.swirlds.config.api.converter.ConfigConverter;
import com.swirlds.config.api.validation.ConfigPropertyConstraint;
import com.swirlds.config.api.validation.ConfigViolation;
import com.swirlds.config.api.validation.PropertyMetadata;
import java.util.Objects;

/**
 * Implementation of {@link ConfigPropertyConstraint} that results in a violation if the value of the property can not be
 * converted.
 *
 * @param <T> type of the value
 */
public class PropertyValueConvertableConstraint<T> implements ConfigPropertyConstraint<T> {

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
        final ConfigConverter<T> converter = metadata.getConverter();
        if (converter == null) {
            final String message = "No converter for type '" + metadata.getValueType() + "' + of property '"
                    + metadata.getName() + "'" + " defined";
            return DefaultConfigViolation.of(metadata, message);
        }
        try {
            converter.convert(metadata.getRawValue());
        } catch (final Exception e) {
            final String message = "Value '" + metadata.getRawValue() + "' of property '" + metadata.getName() + "' "
                    + "can not be converted to '" + metadata.getValueType() + "'";
            return DefaultConfigViolation.of(metadata, message);
        }
        return null;
    }
}
