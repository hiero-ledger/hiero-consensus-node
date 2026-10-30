// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.internal;

import static java.util.Objects.requireNonNull;
import static org.hiero.otter.fixtures.internal.AbstractMiniNode.LifeCycle.RUNNING;
import static org.hiero.otter.fixtures.internal.helpers.Utils.createConfiguration;

import com.swirlds.config.api.Configuration;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import org.hiero.consensus.wiring.framework.schedulers.builders.TaskSchedulerConfiguration;
import org.hiero.otter.fixtures.NodeConfiguration;
import org.hiero.otter.fixtures.internal.AbstractMiniNode.LifeCycle;

/**
 * An abstract base class for node configurations that provides common functionality
 */
public abstract class AbstractNodeConfiguration implements NodeConfiguration {

    /** Defaults of the node type. Values set by the user take precedence. */
    protected final OverrideProperties defaultProperties = new OverrideProperties();

    /** Values set by the user, either for the whole network or for this node. */
    protected final OverrideProperties overrideProperties = new OverrideProperties();

    /** A supplier that provides the current lifecycle state of the node, used to determine if modifying the configuration is allowed. */
    private final Supplier<LifeCycle> lifecycleSupplier;

    /**
     * Constructor for the {@link AbstractNodeConfiguration} class.
     *
     * @param lifecycleSupplier a supplier that provides the current lifecycle state of the node, used to determine if
     * modifying the configuration is allowed
     */
    protected AbstractNodeConfiguration(@NonNull final Supplier<LifeCycle> lifecycleSupplier) {
        this(lifecycleSupplier, new OverrideProperties());
    }

    /**
     * Constructor for the {@link AbstractNodeConfiguration} class.
     *
     * @param lifecycleSupplier a supplier that provides the current lifecycle state of the node, used to determine if
     * modifying the configuration is allowed
     */
    protected AbstractNodeConfiguration(
            @NonNull final Supplier<LifeCycle> lifecycleSupplier,
            @NonNull final OverrideProperties overrideProperties) {
        this.lifecycleSupplier = requireNonNull(lifecycleSupplier, "lifecycleSupplier must not be null");
        this.overrideProperties.apply(overrideProperties);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public NodeConfiguration withConfigValue(@NonNull final String key, final boolean value) {
        throwIfNodeIsRunning();
        overrideProperties.withConfigValue(key, value);
        return this;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public NodeConfiguration withConfigValue(@NonNull final String key, @NonNull final String value) {
        throwIfNodeIsRunning();
        overrideProperties.withConfigValue(key, value);
        return this;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public NodeConfiguration withConfigValue(@NonNull final String key, final int value) {
        throwIfNodeIsRunning();
        overrideProperties.withConfigValue(key, value);
        return this;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public NodeConfiguration withConfigValue(@NonNull final String key, final double value) {
        throwIfNodeIsRunning();
        overrideProperties.withConfigValue(key, value);
        return this;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public NodeConfiguration withConfigValue(@NonNull final String key, final long value) {
        throwIfNodeIsRunning();
        overrideProperties.withConfigValue(key, value);
        return this;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public NodeConfiguration withConfigValue(@NonNull final String key, @NonNull final Enum<?> value) {
        throwIfNodeIsRunning();
        overrideProperties.withConfigValue(key, value);
        return this;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public NodeConfiguration withConfigValue(@NonNull final String key, @NonNull final Duration value) {
        throwIfNodeIsRunning();
        overrideProperties.withConfigValue(key, value);
        return this;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public NodeConfiguration withConfigValue(@NonNull final String key, @NonNull final List<String> values) {
        throwIfNodeIsRunning();
        overrideProperties.withConfigValue(key, values);
        return this;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public NodeConfiguration withConfigValue(@NonNull final String key, @NonNull final Path path) {
        throwIfNodeIsRunning();
        overrideProperties.withConfigValue(key, path);
        return this;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public NodeConfiguration withConfigValue(
            @NonNull final String key, @NonNull final TaskSchedulerConfiguration configuration) {
        throwIfNodeIsRunning();
        overrideProperties.withConfigValue(key, configuration);
        return this;
    }

    /**
     * Throws an {@link IllegalStateException} if the node is running, indicating that configuration modification is not allowed.
     */
    protected final void throwIfNodeIsRunning() {
        if (lifecycleSupplier.get() == RUNNING) {
            throw new IllegalStateException("Configuration modification is not allowed when the node is running.");
        }
    }

    /**
     * Returns the properties the node is configured with: the defaults of the node type, overridden by the values set
     * by the user.
     *
     * @return a new map with the effective properties
     */
    @NonNull
    protected Map<String, String> effectiveProperties() {
        final Map<String, String> properties = new HashMap<>(defaultProperties.properties());
        properties.putAll(overrideProperties.properties());
        return properties;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public Configuration current() {
        return createConfiguration(effectiveProperties());
    }
}
