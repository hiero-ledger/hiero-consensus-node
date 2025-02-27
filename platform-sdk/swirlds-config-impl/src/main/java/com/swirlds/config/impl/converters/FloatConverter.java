// SPDX-License-Identifier: Apache-2.0
package com.swirlds.config.impl.converters;

import com.swirlds.config.api.converter.ConfigConverter;
import edu.umd.cs.findbugs.annotations.NonNull;

/**
 * Concrete {@link ConfigConverter} implementation that provides the support for {@link Float} values in the
 * configuration.
 */
public final class FloatConverter implements ConfigConverter<Float> {

    /**
     * {@inheritDoc}
     */
    @Override
    public Float convert(@NonNull final String value) throws IllegalArgumentException {
        return Float.valueOf(value);
    }
}
