// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.config;

import com.swirlds.config.api.Configuration;
import com.swirlds.config.extensions.reflection.ConfigReflectionUtils;
import com.swirlds.config.extensions.reflection.ConfigReflectionUtils.ConfigDataProperty;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Collections;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.function.Predicate;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Utility class for working with the configuration.
 */
public final class Utils {

    private static final Logger logger = LogManager.getLogger(Utils.class);

    private Utils() {
        throw new UnsupportedOperationException("Utility Class");
    }

    /**
     * Returns a map of all properties in the given configuration that are annotated with {@link NetworkProperty},
     * including the properties of nested config data objects.
     * <p>
     * The annotation belongs on the property itself. A record component that holds a nested config data object is not a
     * property that a config source can set, so annotating it marks nothing.
     *
     * @param configuration the configuration to get properties from
     * @return a map of all network properties in the given configuration
     */
    public static SortedMap<String, Object> networkProperties(@NonNull final Configuration configuration) {
        // Get all properties annotated with @NetworkProperty
        return properties(configuration, property -> property.annotation(NetworkProperty.class) != null);
    }

    /**
     * Returns a map of all properties in the given configuration, including the properties of nested config data
     * objects and those whose value is null.
     *
     * @param configuration the configuration to get properties from
     * @return a map of all properties in the given configuration
     */
    public static SortedMap<String, Object> allProperties(@NonNull final Configuration configuration) {
        return properties(configuration, _ -> true);
    }

    /**
     * Collects the matching properties, keyed by the full name of the property.
     * <p>
     * A nested config data object groups properties rather than holding a value, so it contributes the properties below
     * it and not itself: the result is the same set of properties that the same declaration would define if it had been
     * written flat, with dotted names and scalar components.
     * <p>
     * A property whose value can not be read is logged and skipped. Neither caller is important enough to fail for
     * that: {@code allProperties} only logs the configuration, so an unreadable property has to be dropped with a
     * warning rather than take the node down.
     */
    private static SortedMap<String, Object> properties(
            @NonNull final Configuration configuration, @NonNull final Predicate<ConfigDataProperty> filter) {
        final var recordProperties = new TreeMap<String, Object>();
        ConfigReflectionUtils.getAllProperties(configuration).filter(filter).forEach(property -> {
            try {
                recordProperties.put(property.propertyName(), property.propertyValue());
            } catch (final RuntimeException e) {
                logger.warn("Unable to load config property value for {}", property.propertyName(), e);
            }
        });
        return Collections.unmodifiableSortedMap(recordProperties);
    }
}
