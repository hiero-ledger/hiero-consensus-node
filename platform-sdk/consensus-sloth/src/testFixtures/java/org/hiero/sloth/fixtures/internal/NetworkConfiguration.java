// SPDX-License-Identifier: Apache-2.0
package org.hiero.sloth.fixtures.internal;

import static org.hiero.sloth.fixtures.internal.AbstractNode.UNSET_WEIGHT;

import com.hedera.hapi.node.base.SemanticVersion;
import com.swirlds.component.framework.schedulers.builders.TaskSchedulerConfiguration;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.hiero.sloth.fixtures.Configurable;
import org.hiero.sloth.fixtures.Node;

/**
 * Configuration object for network-level settings in the sloth test framework.
 *
 * <p>This class manages network-wide properties that apply to all nodes within a test network,
 * including the saved state directory, node weights, and the semantic version. It provides a fluent API for configuring
 * these properties and supports setting arbitrary configuration overrides through the {@link OverrideProperties}
 * mechanism.
 */
public class NetworkConfiguration implements Configurable<NetworkConfiguration> {

    /** The weight of each node in the network */
    private long weight = UNSET_WEIGHT;

    /** The version of each node in the network */
    @NonNull
    private SemanticVersion version = Node.DEFAULT_VERSION;

    /** Node configuration values */
    @NonNull
    private final OverrideProperties overrideProperties = new OverrideProperties();

    /**
     * Get the node configuration.
     *
     * @return the node configuration
     */
    @NonNull
    public OverrideProperties overrideProperties() {
        return overrideProperties;
    }

    /**
     * Set the weight of the node.
     *
     * @param weight the weight of the node
     */
    public void weight(final long weight) {
        this.weight = weight;
    }

    /**
     * Get the weight of the node.
     *
     * @return the weight of the node
     */
    public long weight() {
        return weight;
    }

    /**
     * Get the version of the node.
     *
     * @return the version of the node
     */
    @NonNull
    public SemanticVersion version() {
        return version;
    }

    /**
     * Set the version of the node.
     *
     * @param version the version of the node
     */
    public void version(@NonNull final SemanticVersion version) {
        this.version = version;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public NetworkConfiguration withConfigValue(@NonNull final String key, final boolean value) {
        overrideProperties.withConfigValue(key, value);
        return this;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public NetworkConfiguration withConfigValue(@NonNull final String key, @NonNull final String value) {
        overrideProperties.withConfigValue(key, value);
        return this;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public NetworkConfiguration withConfigValue(@NonNull final String key, final int value) {
        overrideProperties.withConfigValue(key, value);
        return this;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public NetworkConfiguration withConfigValue(@NonNull final String key, final double value) {
        overrideProperties.withConfigValue(key, value);
        return this;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public NetworkConfiguration withConfigValue(@NonNull final String key, final long value) {
        overrideProperties.withConfigValue(key, value);
        return this;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public NetworkConfiguration withConfigValue(@NonNull final String key, @NonNull final Enum<?> value) {
        overrideProperties.withConfigValue(key, value);
        return this;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public NetworkConfiguration withConfigValue(@NonNull final String key, final @NonNull Duration value) {
        overrideProperties.withConfigValue(key, value);
        return this;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public NetworkConfiguration withConfigValue(@NonNull final String key, @NonNull final List<String> values) {
        overrideProperties.withConfigValue(key, values);
        return this;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public NetworkConfiguration withConfigValue(@NonNull final String key, @NonNull final Path path) {
        overrideProperties.withConfigValue(key, path);
        return this;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public NetworkConfiguration withConfigValue(
            @NonNull final String key, @NonNull final TaskSchedulerConfiguration value) {
        overrideProperties.withConfigValue(key, value);
        return this;
    }
}
