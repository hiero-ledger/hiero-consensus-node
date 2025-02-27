// SPDX-License-Identifier: Apache-2.0
package com.swirlds.config.impl.converters;

import com.swirlds.config.api.converter.ConfigConverter;
import edu.umd.cs.findbugs.annotations.NonNull;

/**
 * Concrete {@link ConfigConverter} implementation that provides the support for {@link Boolean} values in the
 * configuration.
 */
public final class BooleanConverter implements ConfigConverter<Boolean> {

    /**
     * {@inheritDoc}
     */
    @Override
    public Boolean convert(@NonNull final String value) throws IllegalArgumentException {
        if (value == null) {
            throw new NullPointerException("null can not be converted");
        }

        if (value.equals("1")) {
            return Boolean.TRUE;
        }

        return Boolean.valueOf(value);
    }
}
