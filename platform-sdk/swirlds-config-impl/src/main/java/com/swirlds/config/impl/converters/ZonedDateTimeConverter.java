// SPDX-License-Identifier: Apache-2.0
package com.swirlds.config.impl.converters;

import com.swirlds.config.api.converter.ConfigConverter;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Concrete {@link ConfigConverter} implementation that provides the support for {@link ZonedDateTime} values in the
 * configuration.
 */
public final class ZonedDateTimeConverter implements ConfigConverter<ZonedDateTime> {

    /**
     * {@inheritDoc}
     */
    @Override
    public ZonedDateTime convert(@NonNull final String value) throws IllegalArgumentException {
        return ZonedDateTime.parse(value, DateTimeFormatter.ISO_DATE_TIME);
    }
}
