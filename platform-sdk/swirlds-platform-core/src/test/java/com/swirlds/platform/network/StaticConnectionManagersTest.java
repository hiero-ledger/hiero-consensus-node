// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.network;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.hedera.hapi.node.state.roster.Roster;
import com.swirlds.platform.Utilities;
import com.swirlds.platform.network.connectivity.OutboundConnectionCreator;
import com.swirlds.platform.network.topology.NetworkTopology;
import com.swirlds.platform.network.topology.StaticConnectionManagers;
import com.swirlds.platform.network.topology.StaticTopology;
import com.swirlds.platform.test.fixtures.addressbook.RandomRosterBuilder;
import com.swirlds.platform.test.fixtures.sync.FakeConnection;
import java.util.List;
import java.util.Random;
import org.hiero.consensus.model.node.NodeId;
import org.hiero.consensus.utility.test.fixtures.RandomUtils;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class StaticConnectionManagersTest {
    @Mock
    OutboundConnectionCreator connectionCreator;

    private static List<Arguments> topologicalVariations() {
        return List.of(Arguments.of(10, 10), Arguments.of(20, 20), Arguments.of(50, 40), Arguments.of(60, 40));
    }

    @ParameterizedTest
    @MethodSource("topologicalVariations")
    void testShouldConnectToMe(final int numNodes) throws Exception {
        final Random r = RandomUtils.getRandomPrintSeed();
        final Roster roster = RandomRosterBuilder.create(r).withSize(numNodes).build();
        final NodeId selfId =
                NodeId.of(roster.rosterEntries().get(r.nextInt(numNodes)).nodeId());

        final List<PeerInfo> peers = Utilities.createPeerInfoList(roster, selfId);
        final NetworkTopology topology = new StaticTopology(peers, selfId);

        final StaticConnectionManagers managers = new StaticConnectionManagers(topology, connectionCreator);
        final List<NodeId> neighbors = topology.getNeighbors().stream().toList();
        final NodeId neighbor = neighbors.get(r.nextInt(neighbors.size()));

        if (topology.shouldConnectToMe(neighbor)) {
            final ConnectionManager manager = managers.getManager(neighbor);
            assertNotNull(manager, "should have a manager for this connection");
            assertFalse(manager.isOutbound(), "should be inbound connection");
            final Connection c1 = new FakeConnection(selfId, neighbor);
            managers.newConnection(c1);
            assertSame(c1, manager.waitForConnection(), "the manager should have received the connection supplied");
            assertTrue(c1.connected(), "a new inbound connection should be connected");
            final Connection c2 = new FakeConnection(selfId, neighbor);
            managers.newConnection(c2);
            assertFalse(c1.connected(), "the new connection should have disconnected the old one");
            assertSame(c2, manager.waitForConnection(), "c2 should have replaced c1");
        } else {
            final ConnectionManager manager = managers.getManager(neighbor);
            assertNotNull(manager, "should have a manager for this connection");
            assertTrue(manager.isOutbound(), "should be outbound connection");
            final Connection c = new FakeConnection(selfId, neighbor);
            managers.newConnection(c);
            assertFalse(
                    c.connected(), "if an illegal connection is established, it should be disconnected immediately");
        }
    }

    @ParameterizedTest
    @MethodSource("topologicalVariations")
    void testShouldConnectTo(final int numNodes) throws Exception {
        final Random r = RandomUtils.getRandomPrintSeed();
        final Roster roster = RandomRosterBuilder.create(r).withSize(numNodes).build();
        final NodeId selfId =
                NodeId.of(roster.rosterEntries().get(r.nextInt(numNodes)).nodeId());
        final List<PeerInfo> peers = Utilities.createPeerInfoList(roster, selfId);
        final NetworkTopology topology = new StaticTopology(peers, selfId);

        final StaticConnectionManagers managers = new StaticConnectionManagers(topology, connectionCreator);
        final List<NodeId> neighbors = topology.getNeighbors().stream().toList();
        final NodeId neighbor = neighbors.get(r.nextInt(neighbors.size()));

        if (topology.shouldConnectTo(neighbor)) {
            Mockito.when(connectionCreator.createConnection(Mockito.any())).thenAnswer(inv -> {
                final NodeId peerId = inv.getArgument(0, NodeId.class);
                return new FakeConnection(selfId, peerId);
            });
            final ConnectionManager manager = managers.getManager(neighbor);
            assertNotNull(manager, "should have a manager for this connection");
            assertTrue(manager.isOutbound(), "should be outbound connection");
            assertTrue(
                    manager.waitForConnection().connected(),
                    "outbound connections should be established by the manager");
        } else {
            final ConnectionManager manager = managers.getManager(neighbor);
            assertNotNull(manager, "should have a manager for this connection");
            assertFalse(manager.isOutbound(), "should be inbound connection");
        }
    }
}
