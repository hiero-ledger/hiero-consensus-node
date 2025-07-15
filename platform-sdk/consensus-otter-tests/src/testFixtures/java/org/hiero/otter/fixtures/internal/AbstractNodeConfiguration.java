// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.internal;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.HashMap;
import java.util.Map;
import org.hiero.otter.fixtures.NodeConfiguration;

/**
 * An abstract base class for node configurations that provides common functionality
 *
 * @param <T> the type of the configuration, allowing for method chaining
 */
public abstract class AbstractNodeConfiguration<T extends AbstractNodeConfiguration<T>>
        implements NodeConfiguration<T> {

    protected final Map<String, String> overriddenProperties = new HashMap<>();

    @NonNull
    @Override
    public T set(@NonNull final String key, final boolean value) {
        overriddenProperties.put(key, Boolean.toString(value));
        return self();
    }

    @NonNull
    @Override
    public T set(@NonNull final String key, @NonNull final String value) {
        overriddenProperties.put(key, value);
        return self();
    }

    /**
     * Returns the current instance of the configuration for method chaining.
     *
     * @return this instance
     */
    protected abstract T self();

    /**
     * Get the string value of a configuration key
     *
     * @param key the key of the configuration
     * @return the value of the configuration as a string
     */
    protected abstract String get(@NonNull final String key);

    /**
     * {@inheritDoc}
     */
    public String getString(@NonNull final String key) {
        return get(key);
    }

    /**
     * {@inheritDoc}
     */
    public int getInt(@NonNull final String key) {
        try {
            return Integer.parseInt(get(key));
        } catch (final NumberFormatException e) {
            throw new IllegalArgumentException(
                    String.format("Configuration key '%s' could not be converted to an integer", key), e);
        }
    }

    /**
     * {@inheritDoc}
     */
    public boolean getBoolean(@NonNull final String key) {
        try {
            return Boolean.parseBoolean(get(key));
        } catch (final IllegalArgumentException e) {
            throw new IllegalArgumentException(
                    String.format("Configuration key '%s' could not be converted to a boolean", key), e);
        }
    }

    /**
     * {@inheritDoc}
     */
    public long getLong(@NonNull final String key) {
        try {
            return Long.parseLong(get(key));
        } catch (final NumberFormatException e) {
            throw new IllegalArgumentException(
                    String.format("Configuration key '%s' could not be converted to a long", key), e);
        }
    }
}
