// SPDX-License-Identifier: Apache-2.0
package com.swirlds.config.api.validation;

import com.swirlds.config.api.converter.ConfigConverter;

/**
 * Interface that provides metadata for a property.
 *
 * @param <T> type of the property value
 */
public interface PropertyMetadata<T> {

    /**
     * Returns the raw value of the property.
     *
     * @return the raw value of the property
     */
    String getRawValue();

    /**
     * Returns the value of the property.
     *
     * @return the value of the property
     */
    default T getValue() {
        return getConverter().convert(getRawValue());
    }

    /**
     * Returns the type of the property value.
     *
     * @return the type of the property value
     */
    Class<T> getValueType();

    /**
     * Returns the converter that will be used to convert the raw string value of the property to the value of the
     * property type.
     *
     * @return the converter
     */
    ConfigConverter<T> getConverter();

    /**
     * Returns true if the property exists (is defined by a {@link com.swirlds.config.api.source.ConfigSource}).
     *
     * @return true if the property exists, false otherwise
     */
    boolean exists();

    /**
     * Returns the name of the property.
     *
     * @return the names
     */
    String getName();
}
