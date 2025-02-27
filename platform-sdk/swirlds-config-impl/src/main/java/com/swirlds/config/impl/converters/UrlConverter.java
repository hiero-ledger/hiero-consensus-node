// SPDX-License-Identifier: Apache-2.0
package com.swirlds.config.impl.converters;

import com.swirlds.config.api.converter.ConfigConverter;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.net.MalformedURLException;
import java.net.URL;

/**
 * Concrete {@link ConfigConverter} implementation that provides the support for {@link URL} values in the
 * configuration.
 */
public final class UrlConverter implements ConfigConverter<URL> {

    /**
     * {@inheritDoc}
     */
    @Override
    public URL convert(@NonNull final String value) throws IllegalArgumentException {
        if (value == null) {
            throw new NullPointerException("null can not be converted");
        }
        try {
            return new URL(value);
        } catch (MalformedURLException e) {
            throw new IllegalArgumentException("Value '" + value + "' is not a valid URL", e);
        }
    }
}
