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
}
