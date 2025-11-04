// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.internal.network;

import static java.util.Objects.requireNonNull;
import static org.hiero.otter.fixtures.internal.network.GeoDistributor.calculateNextLocation;
import static org.hiero.otter.fixtures.network.utils.BandwidthLimit.UNLIMITED_BANDWIDTH;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Random;
import java.util.function.Function;
import java.util.function.Supplier;
import org.hiero.otter.fixtures.InstrumentedNode;
import org.hiero.otter.fixtures.Node;
import org.hiero.otter.fixtures.network.GeoMeshTopology;
import org.hiero.otter.fixtures.network.utils.GeographicLatencyConfiguration;
import org.hiero.otter.fixtures.network.utils.LatencyRange;

/**
 * An implementation of {@link GeoMeshTopology}.
 */
public class GeoMeshTopologyImpl implements GeoMeshTopology {

    private final Map<Node, Location> nodes = new LinkedHashMap<>();
    private final Map<Connection, ConnectionData> connectionDataMap = new LinkedHashMap<>();

    private final Function<Integer, List<? extends Node>> nodeFactory;
    private final Supplier<InstrumentedNode> instrumentedNodeFactory;
    private final Random random;

    private GeographicLatencyConfiguration configuration = GeographicLatencyConfiguration.DEFAULT;

    /**
     * Constructor for the {@link GeoMeshTopologyImpl} class.
     *
     * @param random a random number generator for simulating network conditions
     * @param nodeFactory a function that creates a list of nodes given the count
     * @param instrumentedNodeFactory a supplier that creates an instrumented node
     */
    public GeoMeshTopologyImpl(
            @NonNull final Random random,
            @NonNull final Function<Integer, List<? extends Node>> nodeFactory,
            @NonNull final Supplier<InstrumentedNode> instrumentedNodeFactory) {
        this.nodeFactory = requireNonNull(nodeFactory);
        this.instrumentedNodeFactory = requireNonNull(instrumentedNodeFactory);
        this.random = requireNonNull(random);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public List<Node> addNodes(final int count) {
        final List<? extends Node> newNodes = nodeFactory.apply(count);
        for (int i = 0; i < count; i++) {
            nodes.put(newNodes.get(i), calculateNextLocation(configuration, nodes));
        }
        return Collections.unmodifiableList(newNodes);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public List<Node> addNodes(final int count, @NonNull final String continent, @NonNull final String region) {
        requireNonNull(continent);
        requireNonNull(region);
        final List<? extends Node> newNodes = nodeFactory.apply(count);
        for (final Node node : newNodes) {
            nodes.put(node, new Location(continent, region));
        }
        return Collections.unmodifiableList(newNodes);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public InstrumentedNode addInstrumentedNode(@NonNull final String continent, @NonNull final String region) {
        requireNonNull(continent);
        requireNonNull(region);
        final InstrumentedNode newNode = instrumentedNodeFactory.get();
        nodes.put(newNode, new Location(continent, region));
        return newNode;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public InstrumentedNode addInstrumentedNode() {
        final InstrumentedNode newNode = instrumentedNodeFactory.get();
        nodes.put(newNode, calculateNextLocation(configuration, nodes));
        return newNode;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public String getContinent(@NonNull final Node node) {
        final Location location = nodes.get(node);
        if (location == null) {
            throw new IllegalArgumentException("The node is not part of this topology");
        }
        return location.continent;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public String getRegion(@NonNull final Node node) {
        final Location location = nodes.get(node);
        if (location == null) {
            throw new IllegalArgumentException("The node is not part of this topology");
        }
        return location.region;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public List<Node> nodes() {
        return nodes.keySet().stream().toList();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public ConnectionData getConnectionData(@NonNull final Node sender, @NonNull final Node receiver) {
        final Connection connection = new Connection(sender, receiver);
        return connectionDataMap.computeIfAbsent(connection, this::addConnectionData);
    }

    @NonNull
    private ConnectionData addConnectionData(@NonNull final Connection connection) {
        final Node node1 = connection.node1;
        final Node node2 = connection.node2;
        final LatencyRange latencyRange;
        if (!Objects.equals(nodes.get(node1).continent, nodes.get(node2).continent)) {
            latencyRange = configuration.intercontinental();
        } else if (!Objects.equals(nodes.get(node1).region, nodes.get(node2).region)) {
            latencyRange = configuration.sameContinent();
        } else {
            latencyRange = configuration.sameRegion();
        }
        final long nanos =
                random.nextLong(latencyRange.min().toNanos(), latencyRange.max().toNanos());
        final Duration latency = Duration.ofNanos(nanos);
        return new ConnectionData(true, latency, latencyRange.jitterPercent(), UNLIMITED_BANDWIDTH);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void setGeographicLatencyConfiguration(@NonNull final GeographicLatencyConfiguration configuration) {
        this.configuration = requireNonNull(configuration);
    }

    private record Connection(@NonNull Node node1, @NonNull Node node2) {
        private Connection(@NonNull final Node node1, @NonNull final Node node2) {
            final boolean isFirstIdLess = node1.selfId().id() < node2.selfId().id();
            this.node1 = isFirstIdLess ? node1 : node2;
            this.node2 = isFirstIdLess ? node2 : node1;
        }
    }

    /**
     * The location of a node in terms of continent and region.
     *
     * @param continent the continent of the node
     * @param region the region within the continent
     */
    public record Location(@NonNull String continent, @NonNull String region) {}
}
