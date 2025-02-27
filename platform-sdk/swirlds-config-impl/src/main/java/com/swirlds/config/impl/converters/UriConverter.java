// SPDX-License-Identifier: Apache-2.0
package com.swirlds.config.impl.converters;

import com.swirlds.config.api.converter.ConfigConverter;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.net.URI;
import java.net.URISyntaxException;

/**
 * Concrete {@link ConfigConverter} implementation that provides the support for {@link URI} values in the
 * configuration.
 */
public final class UriConverter implements ConfigConverter<URI> {

    /**
     * {@inheritDoc}
     */
    @Override
    public URI convert(@NonNull final String value) throws IllegalArgumentException {
        try {
            return new URI(value);
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException("Value '" + value + "' is not a valid URI", e);
        }
    }
}
