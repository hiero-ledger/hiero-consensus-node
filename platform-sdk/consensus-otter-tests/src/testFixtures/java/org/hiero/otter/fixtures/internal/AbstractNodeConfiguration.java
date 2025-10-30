// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.internal;

import static java.util.Objects.requireNonNull;
import static org.hiero.otter.fixtures.internal.helpers.Utils.createConfiguration;

import com.swirlds.component.framework.schedulers.builders.TaskSchedulerConfiguration;
import com.swirlds.config.api.Configuration;
import com.swirlds.platform.config.PathsConfig_;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.function.Supplier;
import org.hiero.otter.fixtures.NodeConfiguration;
import org.hiero.otter.fixtures.internal.AbstractNode.LifeCycle;

/**
 * An abstract base class for node configurations that provides common functionality
 */
public abstract class AbstractNodeConfiguration implements NodeConfiguration {

    protected final OverrideProperties overriddenProperties = new OverrideProperties();

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
    protected AbstractNodeConfiguration(@NonNull final Supplier<LifeCycle> lifecycleSupplier,
            @NonNull final OverrideProperties overrideProperties) {
        this.lifecycleSupplier = requireNonNull(lifecycleSupplier, "lifecycleSupplier must not be null");
        this.overriddenProperties.apply(overrideProperties);
        this.overriddenProperties.withConfigValue(PathsConfig_.WRITE_PLATFORM_MARKER_FILES, "true");
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public NodeConfiguration withConfigValue(@NonNull final String key, final boolean value) {
        throwIfNodeIsRunning();
        overriddenProperties.withConfigValue(key, value);
        return this;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public NodeConfiguration withConfigValue(@NonNull final String key, @NonNull final String value) {
        throwIfNodeIsRunning();
        overriddenProperties.withConfigValue(key, value);
        return this;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public NodeConfiguration withConfigValue(@NonNull final String key, final int value) {
        throwIfNodeIsRunning();
        overriddenProperties.withConfigValue(key, value);
        return this;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public NodeConfiguration withConfigValue(@NonNull final String key, final double value) {
        throwIfNodeIsRunning();
        overriddenProperties.withConfigValue(key, value);
        return this;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public NodeConfiguration withConfigValue(@NonNull final String key, final long value) {
        throwIfNodeIsRunning();
        overriddenProperties.withConfigValue(key, value);
        return this;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public NodeConfiguration withConfigValue(@NonNull final String key, @NonNull final Enum<?> value) {
        throwIfNodeIsRunning();
        overriddenProperties.withConfigValue(key, value);
        return this;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public NodeConfiguration withConfigValue(@NonNull final String key, @NonNull final Duration value) {
        throwIfNodeIsRunning();
        overriddenProperties.withConfigValue(key, value);
        return this;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public NodeConfiguration withConfigValue(@NonNull final String key, @NonNull final List<String> values) {
        throwIfNodeIsRunning();
        overriddenProperties.withConfigValue(key, values);
        return this;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public NodeConfiguration withConfigValue(@NonNull final String key, @NonNull final Path path) {
        throwIfNodeIsRunning();
        overriddenProperties.withConfigValue(key, path);
        return this;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public NodeConfiguration withConfigValue(@NonNull final String key, @NonNull final TaskSchedulerConfiguration configuration) {
        throwIfNodeIsRunning();
        overriddenProperties.withConfigValue(key, configuration);
        return this;
    }

    protected final void throwIfNodeIsRunning() {
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
        return createConfiguration(overriddenProperties.properties());
    }

}
