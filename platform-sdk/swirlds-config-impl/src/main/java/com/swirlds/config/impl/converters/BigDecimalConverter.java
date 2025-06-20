// SPDX-License-Identifier: Apache-2.0
package com.swirlds.config.impl.converters;

import com.swirlds.config.api.converter.ConfigConverter;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.math.BigDecimal;

/**
 * Concrete {@link ConfigConverter} implementation that provides the support for {@link BigDecimal} values in the
 * configuration.
 */
public final class BigDecimalConverter implements ConfigConverter<BigDecimal> {

    /**
     * {@inheritDoc}
     */
    @Override
    public BigDecimal convert(@NonNull final String value) throws IllegalArgumentException {
        return new BigDecimal(value);
    }
}
