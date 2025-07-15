// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures;

import edu.umd.cs.findbugs.annotations.NonNull;

/**
 * This class contains the current configuration of the node at the time it was requested via
 * {@link Node#configuration()}. It can also be used to modify the configuration.
 *
 * @param <T> the type of the configuration, allowing for method chaining
 */
public interface NodeConfiguration<T extends NodeConfiguration<T>> {

    /**
     * Updates a single property of the configuration.
     *
     * @param key the key of the property
     * @param value the value of the property
     * @return this {@code NodeConfiguration} instance for method chaining
     */
    @NonNull
    T set(@NonNull String key, boolean value);

    /**
     * Updates a single property of the configuration.
     *
     * @param key the key of the property
     * @param value the value of the property
     * @return this {@code NodeConfiguration} instance for method chaining
     */
    @NonNull
    T set(@NonNull String key, @NonNull String value);

    /**
     * Gets the value of a configuration as a string.
     *
     * @param key the key of the configuration
     * @return the value of the configuration as a string
     * @throws IllegalArgumentException if the key does not exist in the configuration
     */
    String getString(@NonNull String key);

    /**
     * Gets the value of a configuration as an integer.
     *
     * @param key the key of the configuration
     * @return the value of the configuration as an integer
     * @throws IllegalArgumentException if the key does not exist in the configuration or if the value cannot be parsed
     * as an integer
     */
    int getInt(@NonNull String key);

    /**
     * Gets the value of a configuration as a boolean.
     *
     * @param key the key of the configuration
     * @return the value of the configuration as a boolean
     * @throws IllegalArgumentException if the key does not exist in the configuration or if the value cannot be parsed
     * as a boolean
     */
    boolean getBoolean(@NonNull String key);

    /**
     * Gets the value of a configuration as a long.
     *
     * @param key the key of the configuration
     * @return the value of the configuration as a long
     * @throws IllegalArgumentException if the key does not exist in the configuration or if the value cannot be parsed
     * as a long
     */
    long getLong(@NonNull String key);
}
