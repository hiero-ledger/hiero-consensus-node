// SPDX-License-Identifier: Apache-2.0
package com.swirlds.config.impl.converters;

import com.swirlds.config.api.converter.ConfigConverter;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.Objects;

/**
 * Converter for type {@link ChronoUnit}.
 */
public class ChronoUnitConverter implements ConfigConverter<ChronoUnit> {

    /**
     * {@inheritDoc}
     */
    @Override
    public ChronoUnit convert(@NonNull final String value) throws IllegalArgumentException {
        if (value == null) {
            throw new NullPointerException("null can not be converted");
        }
        return Arrays.stream(ChronoUnit.values())
                .filter(u -> Objects.equals(u.toString().toLowerCase(), value.toLowerCase()))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Can not parse ChronoUnit: " + value));
    }
}
