// SPDX-License-Identifier: Apache-2.0
package com.swirlds.config.impl.converters;

import com.swirlds.config.api.converter.ConfigConverter;
import edu.umd.cs.findbugs.annotations.NonNull;

/**
 * Concrete {@link ConfigConverter} implementation that provides the support for {@link String} values in the
 * configuration.
 */
public class StringConverter implements ConfigConverter<String> {

    /**
     * {@inheritDoc}
     */
    @Override
    public String convert(@NonNull final String value) throws IllegalArgumentException {
        if (value == null) {
            throw new NullPointerException("value must not be null");
        }
        return value;
    }
}
