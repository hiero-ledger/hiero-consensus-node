// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures;

import com.swirlds.config.api.Configuration;
import edu.umd.cs.findbugs.annotations.NonNull;

/**
 * This class contains the current configuration of the node at the time it was requested via
 * {@link Node#configuration()}. It can also be used to modify the configuration.
 *
 * @param <T> the type of the configuration, allowing for method chaining
 */
public interface NodeConfiguration<T extends NodeConfiguration<T>> {

    /**
     * Updates a single property of the configuration. Can only be invoked when the node is not running.
     *
     * @param key the key of the property
     * @param value the value of the property
     * @return this {@code NodeConfiguration} instance for method chaining
     */
    @NonNull
    T set(@NonNull String key, boolean value);

    /**
     * Updates a single property of the configuration. Can only be invoked when the node is not running.
     *
     * @param key the key of the property
     * @param value the value of the property
     * @return this {@code NodeConfiguration} instance for method chaining
     */
    @NonNull
    T set(@NonNull String key, @NonNull String value);

    /**
     * Returns the current configuration of the node including all overridden properties.
     *
     * @return the current configuration of the node
     */
    @NonNull
    Configuration current();
}
