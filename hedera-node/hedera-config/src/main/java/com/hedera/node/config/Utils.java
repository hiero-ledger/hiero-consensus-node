// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.config;

import com.swirlds.config.api.Configuration;
import com.swirlds.config.extensions.reflection.ConfigReflectionUtils;
import com.swirlds.config.extensions.reflection.ConfigReflectionUtils.ConfigDataProperty;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.SortedMap;
import java.util.function.Predicate;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Utilities for working with config. Ideally this class would not exist, but such functionality would be built into
 * {@link Configuration} itself.
 */
public final class Utils {
    private static final Logger logger = LogManager.getLogger(Utils.class);

    private Utils() {
        // Do not instantiate
        throw new UnsupportedOperationException("Utility Class");
    }

    /**
     * Returns a map of all properties in the given configuration that are annotated with {@link NetworkProperty},
     * including the properties of nested records.
     * <p>
     * The annotation has to be on the property itself. A record component that holds a nested config data object is not
     * a property that can be set, so annotating it has no effect (see {@link NetworkProperty}).
     *
     * @param configuration the configuration to get properties from
     * @return a map of all network properties in the given configuration
     */
    public static SortedMap<String, Object> networkProperties(@NonNull final Configuration configuration) {
        return properties(configuration, property -> property.annotation(NetworkProperty.class) != null);
    }

    /**
     * Returns a map of all properties in the given configuration, including nested records and those with null values.
     *
     * @param configuration the configuration to get properties from
     * @return a map of all properties in the given configuration
     */
    public static SortedMap<String, Object> allProperties(@NonNull final Configuration configuration) {
        return properties(configuration, _ -> true);
    }

    /**
     * Collects the matching properties, skipping every property whose value can not be read.
     * <p>
     * A property is unreadable when the config data record that declares it lives in a package that its module does not
     * export to the module the config reflection runs in. Neither caller of this is important enough to fail for that:
     * {@code allProperties} only logs the configuration, so an unreadable property has to be dropped with a warning
     * rather than take the node down.
     */
    private static SortedMap<String, Object> properties(
            @NonNull final Configuration configuration, @NonNull final Predicate<ConfigDataProperty> filter) {
        return ConfigReflectionUtils.getAllPropertiesAsMap(
                configuration,
                filter,
                (name, failure) -> logger.warn("Unable to load config property value for {}", name, failure));
    }
}
