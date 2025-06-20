// SPDX-License-Identifier: Apache-2.0
package com.swirlds.logging.api.internal.configuration;

import com.swirlds.config.api.converter.ConfigConverter;
import com.swirlds.logging.api.internal.level.ConfigLevel;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;

/**
 * A class that implements the {@link ConfigConverter} interface for converting configuration values to {@link ConfigLevel}.
 * It is used to convert strings to {@link ConfigLevel} enum values based on the string representation.
 *
 * @see ConfigConverter
 * @see ConfigLevel
 */
public class ConfigLevelConverter implements ConfigConverter<ConfigLevel> {

    /**
     * Converts a string representation of a logging level to a {@link ConfigLevel} enum value.
     *
     * @param value The string value to convert.
     * @return The {@link ConfigLevel} enum value corresponding to the provided string.
     * @throws IllegalArgumentException If the provided value cannot be converted to a valid {@link ConfigLevel}.
     * @throws NullPointerException     If the provided value is {@code null}.
     */
    @Nullable
    @Override
    public ConfigLevel convert(@NonNull final String value) throws IllegalArgumentException, NullPointerException {
        if (value == null) {
            throw new NullPointerException("null can not be converted");
        }

        try {
            return ConfigLevel.valueOf(value.toUpperCase());
        } catch (final IllegalArgumentException e) {
            throw new IllegalArgumentException(
                    "The given value '%s' can not be converted to a logging level.".formatted(value));
        }
    }
}
