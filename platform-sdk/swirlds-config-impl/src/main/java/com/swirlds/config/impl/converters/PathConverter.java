// SPDX-License-Identifier: Apache-2.0
package com.swirlds.config.impl.converters;

import com.swirlds.config.api.converter.ConfigConverter;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Concrete {@link ConfigConverter} implementation that provides the support for {@link Path} values in the
 * configuration.
 */
public final class PathConverter implements ConfigConverter<Path> {

    /**
     * {@inheritDoc}
     */
    @Override
    public Path convert(@NonNull final String value) throws IllegalArgumentException {
        return Paths.get(value);
    }
}
