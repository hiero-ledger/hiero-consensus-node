// SPDX-License-Identifier: Apache-2.0
package org.hiero.sloth.fixtures.internal.network;

import static java.util.Objects.requireNonNull;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Function;
import org.hiero.sloth.fixtures.Node;
import org.hiero.sloth.fixtures.network.MeshTopology;
import org.hiero.sloth.fixtures.network.MeshTopologyConfiguration;

/**
 * An implementation of {@link MeshTopology}.
 */
public class MeshTopologyImpl implements MeshTopology {

    private static final Duration AVERAGE_NETWORK_DELAY = Duration.ofMillis(0);

    private final Function<Integer, List<? extends Node>> nodeFactory;
    private final List<Node> nodes = new ArrayList<>();
    private final ConnectionState connectionData;

    /**
     * Constructor for the {@link MeshTopologyImpl} class with a custom configuration.
     *
     * @param configuration the mesh topology configuration
     * @param nodeFactory a function that creates a list of nodes given the count
     * @throws NullPointerException if any parameter is {@code null}
     */
    public MeshTopologyImpl(
            @NonNull final MeshTopologyConfiguration configuration,
            @NonNull final Function<Integer, List<? extends Node>> nodeFactory) {
        this.nodeFactory = requireNonNull(nodeFactory);
        requireNonNull(configuration);
        this.connectionData = new ConnectionState(
                true, configuration.averageLatency(), configuration.jitter(), configuration.bandwidth());
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public List<Node> addNodes(final int count) {
        final List<? extends Node> newNodes = nodeFactory.apply(count);
        nodes.addAll(newNodes);
        return Collections.unmodifiableList(newNodes);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public List<Node> nodes() {
        return Collections.unmodifiableList(nodes);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public ConnectionState getConnectionData(@NonNull final Node sender, @NonNull final Node receiver) {
        return connectionData;
    }
}
