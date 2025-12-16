// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.internal;

import com.swirlds.component.framework.schedulers.builders.TaskSchedulerConfiguration;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.hiero.otter.fixtures.Configurable;

/**
 * A configurable class that manages overridden properties for node and network configurations.
 *
 * <p>This class provides a fluent API to set various types of properties (boolean, string, numeric,
 * enums, durations, lists, paths, and task scheduler configurations) that override default values.
 * The overridden properties can be retrieved as an immutable map and applied to other
 * {@code OverrideProperties} instances.
 */
public class OverrideProperties implements Configurable<OverrideProperties> {

    private final Map<String, String> overriddenProperties = new HashMap<>();

    /**
     * Get the overridden properties.
     *
     * @return a map of overridden properties
     */
    @NonNull
    public Map<String, String> properties() {
        return overriddenProperties;
    }

    /**
     * Applies the provided override properties to the current settings, overriding any properties that conflict.
     * @param overrideProperties the override properties to set
     */
    public void apply(@NonNull final OverrideProperties overrideProperties) {
        this.overriddenProperties.putAll(overrideProperties.properties());
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public OverrideProperties withConfigValue(@NonNull final String key, final boolean value) {
        overriddenProperties.put(key, Boolean.toString(value));
        return this;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public OverrideProperties withConfigValue(@NonNull final String key, @NonNull final String value) {
        overriddenProperties.put(key, value);
        return this;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public OverrideProperties withConfigValue(@NonNull final String key, final int value) {
        overriddenProperties.put(key, Integer.toString(value));
        return this;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public OverrideProperties withConfigValue(@NonNull final String key, final double value) {
        overriddenProperties.put(key, Double.toString(value));
        return this;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public OverrideProperties withConfigValue(@NonNull final String key, final long value) {
        overriddenProperties.put(key, Long.toString(value));
        return this;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public OverrideProperties withConfigValue(@NonNull final String key, @NonNull final Enum<?> value) {
        overriddenProperties.put(key, value.toString());
        return this;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public OverrideProperties withConfigValue(@NonNull final String key, @NonNull final Duration value) {
        overriddenProperties.put(key, value.toString());
        return this;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public OverrideProperties withConfigValue(@NonNull final String key, @NonNull final List<String> values) {
        overriddenProperties.put(key, String.join(",", values));
        return this;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public OverrideProperties withConfigValue(@NonNull final String key, @NonNull final Path path) {
        overriddenProperties.put(key, path.toString());
        return this;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public OverrideProperties withConfigValue(
            @NonNull final String key, @NonNull final TaskSchedulerConfiguration configuration) {
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
}
