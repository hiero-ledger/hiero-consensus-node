// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.internal.network;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Random;
import java.util.function.Function;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.assertj.core.data.Percentage;
import org.hiero.consensus.model.node.NodeId;
import org.hiero.otter.fixtures.InstrumentedNode;
import org.hiero.otter.fixtures.MiniNode;
import org.hiero.otter.fixtures.Node;
import org.hiero.otter.fixtures.network.BandwidthLimit;
import org.hiero.otter.fixtures.network.GeoMeshTopologyConfiguration;
import org.hiero.otter.fixtures.network.PredefinedPingTopologyConfiguration;
import org.hiero.otter.fixtures.network.Topology;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

@DisplayName("Topology mini-node Test")
class TopologyMiniNodeTest {

    private static final int[][] PING_MATRIX = {{0, 10, 20}, {10, 0, 30}, {20, 30, 0}};

    private static long nextId;

    private static <T extends MiniNode> T mockNode(final Class<T> type) {
        final T node = mock(type);
        when(node.selfId()).thenReturn(NodeId.of(nextId++));
        return node;
    }

    private static <T extends MiniNode> Function<Integer, List<T>> factory(final Class<T> type) {
        return count -> IntStream.range(0, count).mapToObj(i -> mockNode(type)).toList();
    }

    private static PredefinedPingTopologyImpl pingTopology() {
        return new PredefinedPingTopologyImpl(
                factory(Node.class),
                () -> mockNode(InstrumentedNode.class),
                factory(MiniNode.class),
                new PredefinedPingTopologyConfiguration(
                        PING_MATRIX, Percentage.withPercentage(0), BandwidthLimit.UNLIMITED_BANDWIDTH));
    }

    private static GeoMeshTopologyImpl geoMeshTopology() {
        return new GeoMeshTopologyImpl(
                GeoMeshTopologyConfiguration.DEFAULT,
                new Random(0),
                factory(Node.class),
                () -> mockNode(InstrumentedNode.class),
                factory(MiniNode.class));
    }

    static Stream<Topology> topologies() {
        return Stream.of(
                new MeshTopologyImpl(
                        factory(Node.class), () -> mockNode(InstrumentedNode.class), factory(MiniNode.class)),
                geoMeshTopology(),
                pingTopology());
    }

    @ParameterizedTest
    @MethodSource("topologies")
    @DisplayName("Mini-nodes are part of allNodes() but not of nodes()")
    void miniNodesOnlyInAllNodes(final Topology topology) {
        final List<Node> nodes = topology.addNodes(1);
        final InstrumentedNode instrumentedNode = topology.addInstrumentedNode();
        final MiniNode miniNode = topology.addMiniNode();

        assertThat(topology.nodes()).containsExactly(nodes.getFirst(), instrumentedNode);
        assertThat(topology.allNodes()).containsExactly(nodes.getFirst(), instrumentedNode, miniNode);
    }

    @Test
    @DisplayName("Mini-nodes count towards the size of the predefined ping matrix")
    void miniNodesCountTowardsPingMatrix() {
        final PredefinedPingTopologyImpl topology = pingTopology();
        final Node node = topology.addNodes(1).getFirst();
        final MiniNode miniNode = topology.addMiniNodes(2).getLast();

        assertThat(topology.getConnectionData(node, miniNode).latency().toMillis())
                .isEqualTo(10);
        assertThatThrownBy(topology::addMiniNode).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> topology.addNodes(1)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("Mini-nodes in a geo mesh have a location that determines their latency")
    void miniNodesHaveGeoLocation() {
        final GeoMeshTopologyImpl topology = geoMeshTopology();
        final Node node = topology.addNodes(1, "AETHERMOOR", "Region-1").getFirst();
        final MiniNode sameRegion = topology.addMiniNode("AETHERMOOR", "Region-1");
        final MiniNode otherContinent =
                topology.addMiniNodes(1, "BRIMHAVEN", "Region-1").getFirst();

        assertThat(topology.getContinent(sameRegion)).isEqualTo("AETHERMOOR");
        assertThat(topology.getRegion(otherContinent)).isEqualTo("Region-1");
        assertThat(topology.getConnectionData(node, sameRegion).latency())
                .isBetween(
                        GeoMeshTopologyConfiguration.DEFAULT.sameRegion().min(),
                        GeoMeshTopologyConfiguration.DEFAULT.sameRegion().max());
        assertThat(topology.getConnectionData(sameRegion, otherContinent).latency())
                .isBetween(
                        GeoMeshTopologyConfiguration.DEFAULT.intercontinental().min(),
                        GeoMeshTopologyConfiguration.DEFAULT.intercontinental().max());
    }
}
