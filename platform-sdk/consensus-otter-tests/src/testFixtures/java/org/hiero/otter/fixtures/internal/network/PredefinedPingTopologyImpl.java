// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.internal.network;

import static java.util.Objects.requireNonNull;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Function;
import java.util.function.Supplier;
import org.assertj.core.data.Percentage;
import org.hiero.consensus.test.fixtures.io.RealisticPingSamples;
import org.hiero.otter.fixtures.InstrumentedNode;
import org.hiero.otter.fixtures.Node;
import org.hiero.otter.fixtures.network.BandwidthLimit;
import org.hiero.otter.fixtures.network.MeshTopology;
import org.hiero.otter.fixtures.network.PredefinedPingTopologyConfiguration;

/**
 * An implementation of {@link MeshTopology} which represents specific ping times between various nodes. To be used
 * with measurements taken from real networks, to best simulate the timings in them.
 */
public class PredefinedPingTopologyImpl implements MeshTopology {

    private final Function<Integer, List<? extends Node>> nodeFactory;
    private final Supplier<InstrumentedNode> instrumentedNodeFactory;
    private final List<Node> nodes = new ArrayList<>();
    private final PredefinedPingTopologyConfiguration configuration;

    /**
     * Constructor for the {@link PredefinedPingTopologyImpl} class with default configuration, using mainnet
     * latencies
     *
     * @param nodeFactory             a function that creates a list of nodes given the count
     * @param instrumentedNodeFactory a supplier that creates an instrumented node
     */
    public PredefinedPingTopologyImpl(
            @NonNull final Function<Integer, List<? extends Node>> nodeFactory,
            @NonNull final Supplier<InstrumentedNode> instrumentedNodeFactory) {
        this(
                nodeFactory,
                instrumentedNodeFactory,
                new PredefinedPingTopologyConfiguration(
                        RealisticPingSamples.MAINNET,
                        Percentage.withPercentage(10),
                        BandwidthLimit.UNLIMITED_BANDWIDTH));
    }

    /**
     * Constructor for the {@link PredefinedPingTopologyImpl} class with a custom configuration.
     *
     * @param nodeFactory             a function that creates a list of nodes given the count
     * @param instrumentedNodeFactory a supplier that creates an instrumented node
     * @param configuration           the mesh topology configuration
     */
    public PredefinedPingTopologyImpl(
            @NonNull final Function<Integer, List<? extends Node>> nodeFactory,
            @NonNull final Supplier<InstrumentedNode> instrumentedNodeFactory,
            @NonNull final PredefinedPingTopologyConfiguration configuration) {
        this.nodeFactory = requireNonNull(nodeFactory);
        this.instrumentedNodeFactory = requireNonNull(instrumentedNodeFactory);
        this.configuration = configuration;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public List<Node> addNodes(final int count) {
        if (nodes.size() + count > configuration.pingMatrix().length) {
            throw new IllegalArgumentException(
                    "Predefined ping matrix supports only " + configuration.pingMatrix().length + " nodes");
        }
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
        if (nodes.size() >= configuration.pingMatrix().length) {
            throw new IllegalArgumentException(
                    "Predefined ping matrix supports only " + configuration.pingMatrix().length + " nodes");
        }
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
    public ConnectionState getConnectionData(@NonNull final Node sender, @NonNull final Node receiver) {
        // latency is half of ping; computed in nanos so that odd pings are not truncated to whole milliseconds
        final int pingMillis = configuration.pingMatrix()[nodes.indexOf(sender)][nodes.indexOf(receiver)];
        return new ConnectionState(
                true, Duration.ofNanos(pingMillis * 1_000_000L / 2), configuration.jitter(), configuration.bandwidth());
    }
}
