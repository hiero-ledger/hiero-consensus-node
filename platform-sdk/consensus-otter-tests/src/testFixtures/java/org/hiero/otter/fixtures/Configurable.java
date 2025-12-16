// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures;

import com.swirlds.component.framework.schedulers.builders.TaskSchedulerConfiguration;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

/**
 * A generic interface for configurable objects that support setting properties of various types.
 *
 * <p>This interface provides a fluent API for configuring properties with support for multiple value types
 * including boolean, string, numeric (int, double, long), enum, duration, lists, paths, and task scheduler
 * configurations. All configuration modifications can only be performed when the node is not running.
 *
 * <p>Implementations should return the same instance for method chaining, allowing for a fluent builder-style
 * configuration pattern.
 *
 * @param <T> the type of the configurable object, allowing for proper method chaining with the implementing type
 */
public interface Configurable<T extends Configurable<T>> {

    /**
     * Updates a single property of the configuration. Can only be invoked when the node is not running.
     *
     * @param key the key of the property
     * @param value the value of the property
     * @return this {@code NodeConfiguration} instance for method chaining
     */
    @NonNull
    T withConfigValue(@NonNull String key, boolean value);

    /**
     * Updates a single property of the configuration. Can only be invoked when the node is not running.
     *
     * @param key the key of the property
     * @param value the value of the property
     * @return this {@code NodeConfiguration} instance for method chaining
     */
    @NonNull
    T withConfigValue(@NonNull String key, @NonNull String value);

    /**
     * Updates a single property of the configuration to an integer value. Can only be invoked when
     * the node is not running.
     *
     * @param key the key of the property
     * @param value the integer value to set
     * @return this {@code NodeConfiguration} instance for method chaining
     */
    @NonNull
    T withConfigValue(@NonNull String key, int value);

    /**
     * Updates a single property of the configuration to a double value. Can only be invoked when
     * the node is not running.
     *
     * @param key the key of the property
     * @param value the double value to set
     * @return this {@code NodeConfiguration} instance for method chaining
     */
    @NonNull
    T withConfigValue(@NonNull String key, double value);

    /**
     * Updates a single property of the configuration to a long value. Can only be invoked when the
     * node is not running.
     *
     * @param key the key of the property
     * @param value the long value to set
     * @return this {@code NodeConfiguration} instance for method chaining
     */
    @NonNull
    T withConfigValue(@NonNull String key, long value);

    /**
     * Updates a single property of the configuration to an enum value. Can only be invoked when the
     * node is not running.
     *
     * @param key the key of the property
     * @param value the enum value to set
     * @return this {@code NodeConfiguration} instance for method chaining
     */
    @NonNull
    T withConfigValue(@NonNull String key, @NonNull Enum<?> value);

    /**
     * Updates a single property of the configuration to a {@link Duration} value. Can only be
     * invoked when the node is not running.
     *
     * @param key the key of the property
     * @param value the {@code Duration} value to set
     * @return this {@code NodeConfiguration} instance for method chaining
     */
    @NonNull
    T withConfigValue(@NonNull String key, @NonNull Duration value);

    /**
     * Updates a single property of the configuration to a {@code List<String>} value. Can only be
     * invoked when the node is not running.
     *
     * @param key the key of the property
     * @param values the {@code String} values to set
     * @return this {@code NodeConfiguration} instance for method chaining
     */
    @NonNull
    T withConfigValue(@NonNull String key, @NonNull List<String> values);

    /**
     * Updates a single property of the configuration to a file path. Can only be invoked when the
     * node is not running.
     *
     * @param key the key of the property
     * @param path the file path to set
     * @return this {@code NodeConfiguration} instance for method chaining
     */
    @NonNull
    T withConfigValue(@NonNull String key, @NonNull Path path);

    /**
     * Updates a single property of the configuration to a {@link TaskSchedulerConfiguration}. Can
     * only be invoked when the node is not running.
     *
     * @param key the key of the property
     * @param value the {@code TaskSchedulerConfiguration} value to set
     * @return this {@code NodeConfiguration} instance for method chaining
     */
    @NonNull
    T withConfigValue(@NonNull String key, @NonNull TaskSchedulerConfiguration value);
}
