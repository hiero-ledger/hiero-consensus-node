// SPDX-License-Identifier: Apache-2.0
package com.swirlds.config.impl.converters;

import com.swirlds.config.api.converter.ConfigConverter;
import edu.umd.cs.findbugs.annotations.NonNull;

/**
 * Concrete {@link ConfigConverter} implementation that provides the support for {@link Short} values in the
 * configuration.
 */
public final class ShortConverter implements ConfigConverter<Short> {

    /**
     * {@inheritDoc}
     */
    @Override
    public Short convert(@NonNull final String value) throws IllegalArgumentException {
        if (value == null) {
            throw new NullPointerException("null can not be converted");
        }
        return Short.valueOf(value);
    }
}
