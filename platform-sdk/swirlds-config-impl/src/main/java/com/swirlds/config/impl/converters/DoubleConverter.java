// SPDX-License-Identifier: Apache-2.0
package com.swirlds.config.impl.converters;

import com.swirlds.config.api.converter.ConfigConverter;
import edu.umd.cs.findbugs.annotations.NonNull;

/**
 * Concrete {@link ConfigConverter} implementation that provides the support for {@link Double} values in the
 * configuration.
 */
public final class DoubleConverter implements ConfigConverter<Double> {

    /**
     * {@inheritDoc}
     */
    @Override
    public Double convert(@NonNull final String value) throws IllegalArgumentException {
        if (value == null) {
            throw new NullPointerException("null can not be converted");
        }
        return Double.valueOf(value);
    }
}
