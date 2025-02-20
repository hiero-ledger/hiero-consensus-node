// SPDX-License-Identifier: Apache-2.0
package com.swirlds.config.impl.validators;

import com.swirlds.config.api.converter.ConfigConverter;
import com.swirlds.config.api.validation.PropertyMetadata;

public record DummyMetadata<T>(
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
