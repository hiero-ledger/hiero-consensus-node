// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.config;

import com.swirlds.config.api.Configuration;
import com.swirlds.config.extensions.reflection.ConfigReflectionUtils;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.SortedMap;

/**
 * Utilities for working with config. Ideally this class would not exist, but such functionality would be built into
 * {@link Configuration} itself.
 */
public final class Utils {

    private Utils() {
        // Do not instantiate
        throw new UnsupportedOperationException("Utility Class");
    }

    /**
     * Returns a map of all properties in the given configuration that are annotated with {@link NetworkProperty},
     * including the properties of nested records.
     *
     * @param configuration the configuration to get properties from
     * @return a map of all network properties in the given configuration
     */
    public static SortedMap<String, Object> networkProperties(@NonNull final Configuration configuration) {
        return ConfigReflectionUtils.getAllPropertiesAsMap(
                configuration, property -> property.annotation(NetworkProperty.class) != null);
    }

    /**
     * Returns a map of all properties in the given configuration, including nested records and those with null values.
     *
     * @param configuration the configuration to get properties from
     * @return a map of all properties in the given configuration
     */
    public static SortedMap<String, Object> allProperties(@NonNull final Configuration configuration) {
        return ConfigReflectionUtils.getAllPropertiesAsMap(configuration, _ -> true);
    }
}
