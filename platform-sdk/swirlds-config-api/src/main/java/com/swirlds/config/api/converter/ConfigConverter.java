// SPDX-License-Identifier: Apache-2.0
package com.swirlds.config.api.converter;

import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;

/**
 * Interface that provides the functionality to convert a raw {@link String} value of a property to a specific data
 * type.
 * <p>
 * Implementations of the interface can be added to the configuration setup by calling {@link
 * com.swirlds.config.api.ConfigurationBuilder#withConverter(Class, ConfigConverter)}}
 *
 * @param <T> The data type of the converter
 */
@FunctionalInterface
public interface ConfigConverter<T> {

    /**
     * The method that is called to convert the given raw string value to a specific data type.
     *
     * @param value the value that should be converted to the specific data type
     * @return the converted value
     * @throws IllegalArgumentException if the given String value can not be converted
     * @throws NullPointerException     if the given String value is null
     */
    @Nullable
    T convert(@NonNull String value) throws IllegalArgumentException, NullPointerException;
}
