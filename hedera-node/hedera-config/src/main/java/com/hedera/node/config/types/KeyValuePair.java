// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.config.types;

import com.hedera.node.config.validation.EmulatesMap;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Objects;

/**
 * A simple key-value pair. This record is used to create {@link java.util.Map} like structures for config data
 * properties. See {@link EmulatesMap} for more details.
 */
public record KeyValuePair(@NonNull String key, @NonNull String value) {

    /**
     * Creates a new {@link KeyValuePair} instance.
     *
     * @param key   the key
     * @param value the value
     * @throws NullPointerException if either key or value is null
     */
    public KeyValuePair {
        Objects.requireNonNull(key, "key cannot be null");
        Objects.requireNonNull(value, "value cannot be null");
    }
}
