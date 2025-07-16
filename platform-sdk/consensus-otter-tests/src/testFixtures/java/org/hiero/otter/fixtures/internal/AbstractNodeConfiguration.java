// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.internal;

import static org.hiero.otter.fixtures.internal.helpers.Utils.createConfiguration;

import com.swirlds.config.api.Configuration;
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

    /**
     * {@inheritDoc}
     */
    @NonNull
    @Override
    public T set(@NonNull final String key, final boolean value) {
        overriddenProperties.put(key, Boolean.toString(value));
        return self();
    }

    /**
     * {@inheritDoc}
     */
    @NonNull
    @Override
    public T set(@NonNull final String key, @NonNull final String value) {
        overriddenProperties.put(key, value);
        return self();
    }

    /**
     * {@inheritDoc}
     */
    @NonNull
    @Override
    public Configuration current() {
        return createConfiguration(overriddenProperties);
    }

    /**
     * Returns the current instance of the configuration for method chaining.
     *
     * @return this instance
     */
    protected abstract T self();
}
