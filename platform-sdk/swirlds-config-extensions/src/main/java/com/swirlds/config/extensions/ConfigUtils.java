// SPDX-License-Identifier: Apache-2.0
package com.swirlds.config.extensions;

import com.swirlds.config.api.Configuration;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Utility class for configuration operations.
 */
public class ConfigUtils {

    /**
     * Utility class constructor.
     */
    private ConfigUtils() {
        throw new UnsupportedOperationException("Utility class");
    }

    /**
     * Check if two configurations have exactly the same properties (key and value for each property must be equals).
     * Everything next to the properties is ignored in this check.
     *
     * @param config1 the first configuration
     * @param config2 the second configuration
     * @return true if the two configurations have exactly the same properties, false otherwise
     */
    public static boolean haveEqualProperties(
            @NonNull final Configuration config1, @NonNull final Configuration config2) {
        Objects.requireNonNull(config1, "config1 must not be null");
        Objects.requireNonNull(config2, "config2 must not be null");
        final Map<String, String> properties1 =
                config1.getPropertyNames().collect(Collectors.toMap(s -> s, s -> config1.getValue(s)));
        final Map<String, String> properties2 =
                config2.getPropertyNames().collect(Collectors.toMap(s -> s, s -> config2.getValue(s)));
        return Objects.equals(properties1, properties2);
    }
}
