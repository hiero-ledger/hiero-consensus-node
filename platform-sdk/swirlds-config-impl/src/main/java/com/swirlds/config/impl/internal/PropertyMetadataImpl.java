// SPDX-License-Identifier: Apache-2.0
package com.swirlds.config.impl.internal;

import com.swirlds.config.api.converter.ConfigConverter;
import com.swirlds.config.api.validation.PropertyMetadata;

/**
 * Implementation of the {@link PropertyMetadata} interface.
 *
 * @param name      the property name
 * @param value     the property value
 * @param valueType the property type
 * @param present   true if the property is present
 * @param converter the converter for the property
 * @param <T>       the type
 */
public record PropertyMetadataImpl<T>(
        String name, String value, Class<T> valueType, boolean present, ConfigConverter<T> converter)
        implements PropertyMetadata<T> {
    @Override
    public String getRawValue() {
        return value();
    }

    @Override
    public Class<T> getValueType() {
        return valueType();
    }

    @Override
    public ConfigConverter<T> getConverter() {
        return converter();
    }

    @Override
    public boolean exists() {
        return present();
    }

    @Override
    public String getName() {
        return name();
    }
}
