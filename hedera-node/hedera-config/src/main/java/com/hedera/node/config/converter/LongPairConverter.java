// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.config.converter;

import com.hedera.node.config.types.LongPair;
import com.swirlds.config.api.converter.ConfigConverter;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Implementation of {@link ConfigConverter} that supports {@link LongPair} as data type for the config.
 */
public class LongPairConverter implements ConfigConverter<LongPair> {

    private static final String PATTERN = Pattern.quote("-");

    @Override
    public LongPair convert(@NonNull final String value) throws IllegalArgumentException, NullPointerException {
        Objects.requireNonNull(value, "Parameter 'value' cannot be null");
        final String[] split = value.split(PATTERN, 2);
        if (split.length <= 1) {
            throw new IllegalArgumentException("Invalid long pair: " + value);
        }
        final String left = split[0];
        final String right = split[1];
        return new LongPair(Long.valueOf(left), Long.valueOf(right));
    }
}
