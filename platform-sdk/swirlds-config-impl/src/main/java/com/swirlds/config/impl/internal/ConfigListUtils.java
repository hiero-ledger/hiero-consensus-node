// SPDX-License-Identifier: Apache-2.0
package com.swirlds.config.impl.internal;

import com.swirlds.config.api.Configuration;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * Class that contains functionality for the general parsing of list properties.
 */
final class ConfigListUtils {

    private ConfigListUtils() {}

    /**
     * Returns a list based on the raw value.
     *
     * @param rawValue the raw value
     * @return the list
     */
    @Nullable
    static List<String> createList(@Nullable final String rawValue) {
        if (rawValue == null) {
            return null;
        }
        if (Objects.equals(Configuration.EMPTY_LIST, rawValue) || rawValue.isBlank()) {
            return List.of();
        }
        return Arrays.stream(rawValue.split(",")).toList();
    }
}
