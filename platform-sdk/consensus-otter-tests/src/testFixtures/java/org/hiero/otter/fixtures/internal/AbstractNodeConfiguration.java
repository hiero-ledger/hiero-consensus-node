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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import org.hiero.otter.fixtures.NodeConfiguration;
import org.hiero.otter.fixtures.internal.AbstractNode.LifeCycle;

/**
 * An abstract base class for node configurations that provides common functionality
 */
public abstract class AbstractNodeConfiguration implements NodeConfiguration {

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
    public NodeConfiguration set(@NonNull final String key, final boolean value) {
        throwIfNodeIsRunning();
        overriddenProperties.put(key, Boolean.toString(value));
        return this;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public NodeConfiguration set(@NonNull final String key, @NonNull final String value) {
        throwIfNodeIsRunning();
        overriddenProperties.put(key, value);
        return this;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public NodeConfiguration set(@NonNull final String key, final int value) {
        throwIfNodeIsRunning();
        overriddenProperties.put(key, Integer.toString(value));
        return this;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public NodeConfiguration set(@NonNull final String key, final double value) {
        throwIfNodeIsRunning();
        overriddenProperties.put(key, Double.toString(value));
        return this;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public NodeConfiguration set(@NonNull final String key, final long value) {
        throwIfNodeIsRunning();
        overriddenProperties.put(key, Long.toString(value));
        return this;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public NodeConfiguration set(@NonNull final String key, @NonNull final Enum<?> value) {
        throwIfNodeIsRunning();
        overriddenProperties.put(key, value.toString());
        return this;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public NodeConfiguration set(@NonNull final String key, @NonNull final Duration value) {
        throwIfNodeIsRunning();
        overriddenProperties.put(key, value.toString());
        return this;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public NodeConfiguration set(@NonNull final String key, @NonNull final List<String> values) {
        throwIfNodeIsRunning();
        overriddenProperties.put(key, String.join(",", values));
        return this;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public NodeConfiguration set(@NonNull final String key, @NonNull final Path path) {
        throwIfNodeIsRunning();
        overriddenProperties.put(key, path.toString());
        return this;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public NodeConfiguration set(@NonNull final String key, @NonNull final TaskSchedulerConfiguration configuration) {
        throwIfNodeIsRunning();
        final StringBuilder builder = new StringBuilder();
        if (configuration.type() != null) {
            builder.append(configuration.type()).append(" ");
        } else {
            builder.append("SEQUENTIAL ");
        }
        if (configuration.unhandledTaskCapacity() != null && configuration.unhandledTaskCapacity() > 0) {
            builder.append(" CAPACITY(")
                    .append(configuration.unhandledTaskCapacity())
                    .append(") ");
        }
        if (Boolean.TRUE.equals(configuration.unhandledTaskMetricEnabled())) {
            builder.append("UNHANDLED_TASK_METRIC ");
        }
        if (Boolean.TRUE.equals(configuration.busyFractionMetricEnabled())) {
            builder.append("BUSY_FRACTION_METRIC ");
        }
        if (Boolean.TRUE.equals(configuration.flushingEnabled())) {
            builder.append("FLUSHABLE ");
        }
        if (Boolean.TRUE.equals(configuration.squelchingEnabled())) {
            builder.append("SQUELCHABLE");
        }
        overriddenProperties.put(key, builder.toString());
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
        return createConfiguration(overriddenProperties);
    }
}
