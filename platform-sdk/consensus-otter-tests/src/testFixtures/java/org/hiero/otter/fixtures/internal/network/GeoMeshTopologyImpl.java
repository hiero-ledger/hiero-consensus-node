// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.internal.network;

import static java.util.Objects.requireNonNull;
import static org.hiero.otter.fixtures.internal.network.GeoDistributor.calculateNextLocation;
import static org.hiero.otter.fixtures.network.BandwidthLimit.UNLIMITED_BANDWIDTH;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Random;
import java.util.function.Function;
import java.util.function.Supplier;
import org.hiero.otter.fixtures.InstrumentedNode;
import org.hiero.otter.fixtures.MiniNode;
import org.hiero.otter.fixtures.Node;
import org.hiero.otter.fixtures.network.GeoMeshTopology;
import org.hiero.otter.fixtures.network.GeoMeshTopologyConfiguration;
import org.hiero.otter.fixtures.network.LatencyRange;

/**
 * An implementation of {@link GeoMeshTopology}.
 */
public class GeoMeshTopologyImpl implements GeoMeshTopology {

    private final List<Node> nodes = new ArrayList<>();
    private final List<MiniNode> allNodes = new ArrayList<>();
    private final Map<MiniNode, Location> locations = new LinkedHashMap<>();
    private final Map<Connection, ConnectionState> connectionDataMap = new LinkedHashMap<>();

    private final Function<Integer, List<Node>> nodeFactory;
    private final Supplier<InstrumentedNode> instrumentedNodeFactory;
    private final Function<Integer, List<MiniNode>> miniNodeFactory;
    private final Random random;

    private final GeoMeshTopologyConfiguration configuration;

    /**
     * Constructor for the {@link GeoMeshTopologyImpl} class.
     *
     * @param configuration the geographic latency configuration
     * @param random a random number generator for simulating network conditions
     * @param nodeFactory a function that creates a list of nodes given the count
     * @param instrumentedNodeFactory a supplier that creates an instrumented node
     * @param miniNodeFactory a function that creates a list of mini nodes given the count
     * @throws NullPointerException if any parameter is {@code null}
     */
    public GeoMeshTopologyImpl(
            @NonNull final GeoMeshTopologyConfiguration configuration,
            @NonNull final Random random,
            @NonNull final Function<Integer, List<Node>> nodeFactory,
            @NonNull final Supplier<InstrumentedNode> instrumentedNodeFactory,
            @NonNull final Function<Integer, List<MiniNode>> miniNodeFactory) {
        this.configuration = requireNonNull(configuration);
        this.nodeFactory = requireNonNull(nodeFactory);
        this.instrumentedNodeFactory = requireNonNull(instrumentedNodeFactory);
        this.miniNodeFactory = requireNonNull(miniNodeFactory);
        this.random = requireNonNull(random);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public List<Node> addNodes(final int count) {
        final List<Node> newNodes = nodeFactory.apply(count);
        for (final Node node : newNodes) {
            locations.put(node, calculateNextLocation(configuration, locations));
        }
        nodes.addAll(newNodes);
        allNodes.addAll(newNodes);

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
        final List<Node> newNodes = nodeFactory.apply(count);
        for (final Node node : newNodes) {
            locations.put(node, new Location(continent, region));
        }
        nodes.addAll(newNodes);
        allNodes.addAll(newNodes);
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
        locations.put(newNode, new Location(continent, region));
        nodes.add(newNode);
        allNodes.add(newNode);
        return newNode;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public InstrumentedNode addInstrumentedNode() {
        final InstrumentedNode newNode = instrumentedNodeFactory.get();
        locations.put(newNode, calculateNextLocation(configuration, locations));
        nodes.add(newNode);
        allNodes.add(newNode);
        return newNode;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public List<MiniNode> addMiniNodes(final int count) {
        final List<MiniNode> newNodes = miniNodeFactory.apply(count);
        for (final MiniNode node : newNodes) {
            locations.put(node, calculateNextLocation(configuration, locations));
        }
        allNodes.addAll(newNodes);
        return Collections.unmodifiableList(newNodes);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public List<MiniNode> addMiniNodes(final int count, @NonNull final String continent, @NonNull final String region) {
        requireNonNull(continent);
        requireNonNull(region);
        final List<MiniNode> newNodes = miniNodeFactory.apply(count);
        for (final MiniNode node : newNodes) {
            locations.put(node, new Location(continent, region));
        }
        allNodes.addAll(newNodes);
        return Collections.unmodifiableList(newNodes);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public String getContinent(@NonNull final MiniNode node) {
        final Location location = locations.get(node);
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
    public String getRegion(@NonNull final MiniNode node) {
        final Location location = locations.get(node);
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
        return Collections.unmodifiableList(nodes);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public List<MiniNode> allNodes() {
        return Collections.unmodifiableList(allNodes);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public ConnectionState getConnectionData(@NonNull final MiniNode sender, @NonNull final MiniNode receiver) {
        final Connection connection = new Connection(sender, receiver);
        return connectionDataMap.computeIfAbsent(connection, this::addConnectionData);
    }

    @NonNull
    private ConnectionState addConnectionData(@NonNull final Connection connection) {
        final MiniNode node1 = connection.node1;
        final MiniNode node2 = connection.node2;
        final LatencyRange latencyRange;
        final Location location1 = locations.get(node1);
        final Location location2 = locations.get(node2);
        if (!Objects.equals(location1.continent, location2.continent)) {
            latencyRange = configuration.intercontinental();
        } else if (!Objects.equals(location1.region, location2.region)) {
            latencyRange = configuration.sameContinent();
        } else {
            latencyRange = configuration.sameRegion();
        }
        final long nanos =
                random.nextLong(latencyRange.min().toNanos(), latencyRange.max().toNanos());
        final Duration latency = Duration.ofNanos(nanos);
        return new ConnectionState(true, latency, latencyRange.jitterPercent(), UNLIMITED_BANDWIDTH);
    }

    private record Connection(
            @NonNull MiniNode node1, @NonNull MiniNode node2) {
        private Connection(@NonNull final MiniNode node1, @NonNull final MiniNode node2) {
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
    public record Location(
            @NonNull String continent, @NonNull String region) {}
}
