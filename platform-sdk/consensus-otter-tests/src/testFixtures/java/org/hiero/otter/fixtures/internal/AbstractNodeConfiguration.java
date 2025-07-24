// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.internal;

import static java.util.Objects.requireNonNull;
import static org.hiero.otter.fixtures.internal.helpers.Utils.createConfiguration;

import com.swirlds.config.api.Configuration;
import com.swirlds.platform.config.PathsConfig_;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;
import org.hiero.otter.fixtures.NodeConfiguration;
import org.hiero.otter.fixtures.internal.AbstractNode.LifeCycle;

/**
 * An abstract base class for node configurations that provides common functionality
 *
 * @param <T> the type of the configuration, allowing for method chaining
 */
public abstract class AbstractNodeConfiguration<T extends AbstractNodeConfiguration<T>>
        implements NodeConfiguration<T> {

    protected final Map<String, String> overriddenProperties = new HashMap<>();

    private final Supplier<LifeCycle> lifecycleSupplier;

    /**
     * Constructor for the {@link AbstractNodeConfiguration} class.
     *
     * @param lifecycleSupplier a supplier that provides the current lifecycle state of the node, used to determine if
     * modifying the configuration is allowed
     */
    protected AbstractNodeConfiguration(@NonNull final Supplier<LifeCycle> lifecycleSupplier) {
        this.lifecycleSupplier = requireNonNull(lifecycleSupplier, "lifecycleSupplier must not be null");

        overriddenProperties.put(PathsConfig_.WRITE_PLATFORM_MARKER_FILES, "true");
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public T set(@NonNull final String key, final boolean value) {
        throwIfNodeIsRunning();
        overriddenProperties.put(key, Boolean.toString(value));
        return self();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public T set(@NonNull final String key, @NonNull final String value) {
        throwIfNodeIsRunning();
        overriddenProperties.put(key, value);
        return self();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public T set(@NonNull final String key, final int value) {
        throwIfNodeIsRunning();
        overriddenProperties.put(key, Integer.toString(value));
        return self();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public T set(@NonNull final String key, @NonNull final Path path) {
        throwIfNodeIsRunning();
        overriddenProperties.put(key, path.toString());
        return self();
    }

    private void throwIfNodeIsRunning() {
        if (lifecycleSupplier.get() == LifeCycle.RUNNING) {
            throw new IllegalStateException("Configuration modification is not allowed when the node is running.");
        }
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
