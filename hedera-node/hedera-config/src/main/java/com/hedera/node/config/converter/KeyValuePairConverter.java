// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.config.converter;

import com.hedera.node.config.types.KeyValuePair;
import com.swirlds.config.api.converter.ConfigConverter;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Implementation of {@link ConfigConverter} that supports {@link KeyValuePair} as data type for the config.
 */
public class KeyValuePairConverter implements ConfigConverter<KeyValuePair> {

    private static final String PATTERN = Pattern.quote("=");

    @Override
    public KeyValuePair convert(@NonNull final String value) throws IllegalArgumentException, NullPointerException {
        Objects.requireNonNull(value, "Parameter 'value' cannot be null");
        final String[] split = value.split(PATTERN, 2);
        if (split.length == 1) {
            throw new IllegalArgumentException("Invalid key value pair: " + value);
        }
        final String key = split[0];
        final String val = split[1];
        return new KeyValuePair(key, val);
    }
}
