// SPDX-License-Identifier: Apache-2.0
package com.swirlds.config.impl.converters;

import com.swirlds.config.api.converter.ConfigConverter;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.File;

/**
 * Concrete {@link ConfigConverter} implementation that provides the support for {@link File} values in the
 * configuration.
 */
public final class FileConverter implements ConfigConverter<File> {

    /**
     * {@inheritDoc}
     */
    @Override
    public File convert(@NonNull final String value) throws IllegalArgumentException {
        return new File(value);
    }
}
