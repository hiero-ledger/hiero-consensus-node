// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures;

import com.swirlds.config.api.Configuration;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.nio.file.Path;

/**
 * This class contains the current configuration of the node at the time it was requested via
 * {@link Node#configuration()}. It can also be used to modify the configuration.
 */
public interface NodeConfiguration {

    /**
     * Updates a single property of the configuration. Can only be invoked when the node is not running.
     *
     * @param key the key of the property
     * @param value the value of the property
     * @return this {@code NodeConfiguration} instance for method chaining
     */
    @NonNull
    NodeConfiguration set(@NonNull String key, boolean value);

    /**
     * Updates a single property of the configuration. Can only be invoked when the node is not running.
     *
     * @param key the key of the property
     * @param value the value of the property
     * @return this {@code NodeConfiguration} instance for method chaining
     */
    @NonNull
    NodeConfiguration set(@NonNull String key, @NonNull String value);

    /**
     * Updates a single property of the configuration to an integer value. Can only be invoked when the node is not
     * running.
     *
     * @param key the key of the property
     * @param value the integer value to set
     * @return this {@code NodeConfiguration} instance for method chaining
     */
    NodeConfiguration set(@NonNull String key, int value);

    /**
     * Updates a single property of the configuration to a long value. Can only be invoked when the node is not
     * running.
     *
     * @param key the key of the property
     * @param value the long value to set
     * @return this {@code NodeConfiguration} instance for method chaining
     */
    NodeConfiguration set(@NonNull String key, long value);

    /**
     * Updates a single property of the configuration to a file path. Can only be invoked when the node is not running.
     *
     * @param key the key of the property
     * @param path the file path to set
     * @return this {@code NodeConfiguration} instance for method chaining
     */
    NodeConfiguration set(@NonNull String key, @NonNull Path path);

    /**
     * Returns the current configuration of the node including all overridden properties.
     *
     * @return the current configuration of the node
     */
    @NonNull
    Configuration current();
}
