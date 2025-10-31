// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.internal.network;

import static java.util.Objects.requireNonNull;
import static org.hiero.otter.fixtures.network.utils.BandwidthLimit.UNLIMITED_BANDWIDTH;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Function;
import java.util.function.Supplier;
import org.assertj.core.data.Percentage;
import org.hiero.otter.fixtures.InstrumentedNode;
import org.hiero.otter.fixtures.Node;
import org.hiero.otter.fixtures.network.MeshTopology;

/**
 * An implementation of {@link MeshTopology}.
 */
public class MeshTopologyImpl implements MeshTopology {

    private static final Duration AVERAGE_NETWORK_DELAY = Duration.ofMillis(200);
    private static final ConnectionData DEFAULT =
            new ConnectionData(true, AVERAGE_NETWORK_DELAY, Percentage.withPercentage(5), UNLIMITED_BANDWIDTH);

    private final Function<Integer, List<? extends Node>> nodeFactory;
    private final Supplier<InstrumentedNode> instrumentedNodeFactory;
    private final List<Node> nodes = new ArrayList<>();

    /**
     * Constructor for the {@link MeshTopologyImpl} class.
     *
     * @param nodeFactory a function that creates a list of nodes given the count
     * @param instrumentedNodeFactory a supplier that creates an instrumented node
     * @throws NullPointerException if {@code nodeFactory} or {@code instrumentedNodeFactory} is {@code null}
     */
    public MeshTopologyImpl(
            @NonNull final Function<Integer, List<? extends Node>> nodeFactory,
            @NonNull final Supplier<InstrumentedNode> instrumentedNodeFactory) {
        this.nodeFactory = requireNonNull(nodeFactory);
        this.instrumentedNodeFactory = requireNonNull(instrumentedNodeFactory);
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
    public InstrumentedNode addInstrumentedNode() {
        final InstrumentedNode instrumentedNode = instrumentedNodeFactory.get();
        nodes.add(instrumentedNode);
        return instrumentedNode;
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
    public ConnectionData getConnectionData(@NonNull final Node sender, @NonNull final Node receiver) {
        return DEFAULT;
    }
}
