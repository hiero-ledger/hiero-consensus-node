// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures;

import edu.umd.cs.findbugs.annotations.NonNull;

/**
 * This class contains the current configuration of the node at the time it was requested via
 * {@link Node#getConfiguration()}. It can also be used to modify the configuration.
 */
public interface NodeConfiguration<T extends NodeConfiguration> {

    /**
     * Updates a single property of the configuration.
     *
     * @param key the key of the property
     * @param value the value of the property
     * @return this {@code NodeConfiguration} instance for method chaining
     */
    T set(@NonNull String key, boolean value);

    /**
     * Updates a single property of the configuration.
     *
     * @param key the key of the property
     * @param value the value of the property
     * @return this {@code NodeConfiguration} instance for method chaining
     */
    T set(@NonNull String key, @NonNull String value);
}
