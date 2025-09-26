// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.internal;

import static java.util.Objects.requireNonNull;
import static org.hiero.otter.fixtures.internal.helpers.Utils.createConfiguration;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.fasterxml.jackson.dataformat.yaml.YAMLGenerator.Feature;
import com.swirlds.config.api.Configuration;
import com.swirlds.platform.config.PathsConfig_;
import com.swirlds.platform.gossip.config.NetworkEndpoint;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import org.hiero.otter.fixtures.NodeConfiguration;
import org.hiero.otter.fixtures.internal.AbstractNode.LifeCycle;
import org.jetbrains.annotations.NotNull;

/**
 * An abstract base class for node configurations that provides common functionality
 */
public abstract class AbstractNodeConfiguration implements NodeConfiguration {

    private static final ObjectMapper OBJECT_MAPPER =
            new ObjectMapper(new YAMLFactory().disable(Feature.WRITE_DOC_START_MARKER));

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
    public NodeConfiguration set(@NotNull final String key, @NotNull final List<NetworkEndpoint> endpoints) {
        throwIfNodeIsRunning();
        final String value = endpoints.stream()
                .map(AbstractNodeConfiguration::convertEndpoint)
                .collect(Collectors.joining(","));
        overriddenProperties.put(key, value);
        return this;
    }

    private static String convertEndpoint(@NonNull final NetworkEndpoint endpoint) {
        try {
            return OBJECT_MAPPER.writeValueAsString(endpoint).replaceAll("\"", "\\\"");
        } catch (final JsonProcessingException e) {
            // This should not happen as the list is expected to be serializable
            throw new RuntimeException("Exception while serializing endpoints", e);
        }
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
}
