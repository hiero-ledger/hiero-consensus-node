// SPDX-License-Identifier: Apache-2.0
package com.swirlds.config.impl.converters;

import com.swirlds.config.api.converter.ConfigConverter;
import edu.umd.cs.findbugs.annotations.NonNull;

/**
 * Concrete {@link ConfigConverter} implementation that provides the support for {@link Long} values in the
 * configuration.
 */
public final class LongConverter implements ConfigConverter<Long> {

    /**
     * {@inheritDoc}
     */
    @Override
    public Long convert(@NonNull final String value) throws IllegalArgumentException {
        if (value == null) {
            throw new NullPointerException("null can not be converted");
        }
        return Long.valueOf(value.replace("_", ""));
    }
}
