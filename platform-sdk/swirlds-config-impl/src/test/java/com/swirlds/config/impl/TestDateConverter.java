// SPDX-License-Identifier: Apache-2.0
package com.swirlds.config.impl;

import com.swirlds.config.api.converter.ConfigConverter;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Date;

/**
 * Custom converter that is used for tests
 */
public class TestDateConverter implements ConfigConverter<Date> {

    @Override
    public Date convert(@NonNull final String value) throws IllegalArgumentException, NullPointerException {
        return new Date(Long.parseLong(value));
    }
}
