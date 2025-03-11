// SPDX-License-Identifier: Apache-2.0
package com.swirlds.config.impl.converters;

import com.swirlds.config.api.converter.ConfigConverter;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.Objects;

/**
 * Implementation of {@code ConfigConverter} specifically for converting enums.
 */
public class EnumConverter<T extends Enum<T>> implements ConfigConverter<T> {

    private final Class<T> valueType;

    /**
     * @param valueType enum type this converter is associated to
     * @throws NullPointerException if {@code valueType} is {@code null}
     */
    public EnumConverter(@NonNull Class<T> valueType) {
        this.valueType = Objects.requireNonNull(valueType, "valueType must not be null");
    }

    /**
     * @param value String value to be converted to a valid enum instance
     * @return the enum instance associated with {@code value}
     * @throws IllegalArgumentException if value is not a valid enum value for {@code valueType}
     * @throws NullPointerException     if {@code value} is {@code null}
     */
    @Nullable
    @Override
    public T convert(@NonNull String value) throws IllegalArgumentException, NullPointerException {
        try {
            return Enum.valueOf(this.valueType, Objects.requireNonNull(value, "value must not be null"));
        } catch (final IllegalArgumentException e) {
            throw new IllegalArgumentException(
                    "Can not convert value '%s' of Enum '%s' by default. Please add a custom config converter."
                            .formatted(value, this.valueType),
                    e);
        }
    }
}
