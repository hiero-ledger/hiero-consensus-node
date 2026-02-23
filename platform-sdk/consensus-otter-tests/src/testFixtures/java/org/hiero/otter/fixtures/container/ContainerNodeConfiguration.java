// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.container;

import static org.hiero.otter.fixtures.container.utils.ContainerConstants.CONTAINER_APP_WORKING_DIR;
import static org.hiero.otter.fixtures.container.utils.ContainerConstants.EVENT_STREAM_DIRECTORY;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.fasterxml.jackson.dataformat.yaml.YAMLGenerator.Feature;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import org.hiero.consensus.config.EventConfig_;
import org.hiero.consensus.gossip.config.NetworkEndpoint;
import org.hiero.otter.fixtures.NodeConfiguration;
import org.hiero.otter.fixtures.internal.AbstractNode.LifeCycle;
import org.hiero.otter.fixtures.internal.AbstractNodeConfiguration;
import org.hiero.otter.fixtures.internal.OverrideProperties;

/**
 * An implementation of {@link NodeConfiguration} for a container environment.
 */
@SuppressWarnings("UnusedReturnValue")
public class ContainerNodeConfiguration extends AbstractNodeConfiguration {

    private static final ObjectMapper OBJECT_MAPPER =
            new ObjectMapper(new YAMLFactory().disable(Feature.WRITE_DOC_START_MARKER));

    /**
     * Constructor for the {@link ContainerNodeConfiguration} class
     *
     * @param lifecycleSupplier a supplier that provides the current lifecycle state of the node
     */
    public ContainerNodeConfiguration(@NonNull final Supplier<LifeCycle> lifecycleSupplier) {
        this(lifecycleSupplier, new OverrideProperties());
    }

    /**
     * Constructor for the {@link ContainerNodeConfiguration} class.
     *
     * @param lifecycleSupplier a supplier that provides the current lifecycle state of the node
     * @param overrideProperties override properties to initialize the configuration with
     */
    public ContainerNodeConfiguration(
            @NonNull final Supplier<LifeCycle> lifecycleSupplier,
            @NonNull final OverrideProperties overrideProperties) {
        super(lifecycleSupplier, overrideProperties);
        this.overrideProperties.withConfigValue(
                EventConfig_.EVENTS_LOG_DIR, Path.of(CONTAINER_APP_WORKING_DIR, EVENT_STREAM_DIRECTORY));
    }

    /**
     * Updates a single property of the configuration to a {@link List} of {@link NetworkEndpoint}. Can only be invoked
     * when the node is not running.
     *
     * @param key the key of the property
     * @param endpoints the list of network endpoints to set
     * @return this {@code NodeConfiguration} instance for method chaining
     */
    @NonNull
    public NodeConfiguration setNetworkEndpoints(
            @NonNull final String key, @NonNull final List<NetworkEndpoint> endpoints) {
        throwIfNodeIsRunning();
        final String value = endpoints.stream()
                .map(ContainerNodeConfiguration::convertEndpoint)
                .collect(Collectors.joining(","));
        overrideProperties.withConfigValue(key, value);
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

    /**
     * Returns the overridden properties for this node configuration.
     *
     * <p>The returned map is unmodifiable and contains all properties that have been set on this
     * configuration instance using the various {@code set()} methods.
     *
     * @return an unmodifiable map of overridden properties
     */
    @NonNull
    Map<String, String> overrideProperties() {
        return Collections.unmodifiableMap(overrideProperties.properties());
    }
}
