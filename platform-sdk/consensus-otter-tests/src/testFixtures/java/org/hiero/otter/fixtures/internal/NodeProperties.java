// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.internal;

import static org.hiero.otter.fixtures.internal.AbstractNode.UNSET_WEIGHT;

import com.hedera.hapi.node.base.SemanticVersion;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.nio.file.Path;
import org.hiero.otter.fixtures.Node;

/**
 * Properties set at the network level that need to be applied to nodes once they are created.
 */
public class NodeProperties {

    /** The directory of the saved state to load as the initial state, null if starting from genesis */
    @Nullable
    private Path savedStateDirectory;

    /** The weight of each node in the network */
    private long weight = UNSET_WEIGHT;

    /** The version of each node in the network */
    @NonNull
    private SemanticVersion version = Node.DEFAULT_VERSION;

    /** Node configuration values */
    @NonNull
    private final AbstractNodeConfiguration nodeConfiguration;

    /**
     * Constructor for NodeProperties.
     *
     * @param nodeConfiguration the configuration to apply to nodes created in the network
     */
    public NodeProperties(@NonNull final AbstractNodeConfiguration nodeConfiguration) {
        this.nodeConfiguration = nodeConfiguration;
    }

    /**
     * Get the saved state directory.
     *
     * @return the saved state directory, or null if starting from genesis
     */
    @Nullable
    public Path savedStateDirectory() {
        return savedStateDirectory;
    }

    /**
     * Set the saved state directory.
     *
     * @param savedStateDirectory the saved state directory, or null to start from genesis
     */
    public void savedStateDirectory(@Nullable final Path savedStateDirectory) {
        this.savedStateDirectory = savedStateDirectory;
    }

    /**
     * Get the node configuration.
     *
     * @return the node configuration
     */
    @NonNull
    public AbstractNodeConfiguration configuration() {
        return nodeConfiguration;
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
}
