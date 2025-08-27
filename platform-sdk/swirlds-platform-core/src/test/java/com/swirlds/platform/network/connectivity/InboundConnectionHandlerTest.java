// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.network.connectivity;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.hedera.hapi.node.state.roster.Roster;
import com.swirlds.base.time.Time;
import com.swirlds.common.context.PlatformContext;
import com.swirlds.common.test.fixtures.platform.TestPlatformContextBuilder;
import com.swirlds.platform.Utilities;
import com.swirlds.platform.network.Connection;
import com.swirlds.platform.network.ConnectionTracker;
import com.swirlds.platform.network.NetworkUtils;
import com.swirlds.platform.network.PeerInfo;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.List;
import java.util.Map;
import java.util.Random;
import org.hiero.base.concurrent.interrupt.InterruptableConsumer;
import org.hiero.consensus.model.node.KeysAndCerts;
import org.hiero.consensus.model.node.NodeId;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.Mockito;

class InboundConnectionHandlerTest extends ConnectivityTestBase {

    private static final int PORT = 31_000;
    private static final PlatformContext platformContext = TestPlatformContextBuilder.create()
            .withConfiguration(TLS_NO_IP_TOS_CONFIG)
            .build();
    private static final ConnectionTracker ct = Mockito.mock(ConnectionTracker.class);

    /**
     * asserts that when an inbound connection successfully identifies a peer, connection is created
     */
    @ParameterizedTest
    @MethodSource({"com.swirlds.platform.crypto.CryptoArgsProvider#basicTestArgs"})
    void handleInboundOnePeerTest(final Roster roster, final Map<NodeId, KeysAndCerts> keysAndCerts) throws Throwable {
        assertTrue(roster.rosterEntries().size() > 1, "Address book must contain at least 2 nodes");
        // choose 2 random nodes to test
        final Random random = new Random();
        final List<Integer> nodeIndexes = random.ints(0, roster.rosterEntries().size())
                .distinct()
                .limit(2)
                .boxed()
                .toList();
        final NodeId node1 =
                NodeId.of(roster.rosterEntries().get(nodeIndexes.get(0)).nodeId());
        final NodeId node2 =
                NodeId.of(roster.rosterEntries().get(nodeIndexes.get(1)).nodeId());
        final KeysAndCerts thisKeysAndCerts = keysAndCerts.get(node1);
        final KeysAndCerts OtherKeysAndCerts = keysAndCerts.get(node2);
        final List<PeerInfo> node1Peers = Utilities.createPeerInfoList(roster, node1);
        final List<PeerInfo> node2Peers = Utilities.createPeerInfoList(roster, node2);

        final SocketFactory socketFactory1 =
                NetworkUtils.createSocketFactory(node1, node1Peers, thisKeysAndCerts, TLS_NO_IP_TOS_CONFIG);
        final SocketFactory socketFactory2 =
                NetworkUtils.createSocketFactory(node2, node2Peers, OtherKeysAndCerts, TLS_NO_IP_TOS_CONFIG);

        final ServerSocket serverSocket = socketFactory1.createServerSocket(PORT);
        final Thread serverThread = createSocketThread(serverSocket);
        serverThread.start();

        final Socket socket = socketFactory2.createClientSocket(STRING_IP, PORT);
        socket.getOutputStream().write(TEST_DATA);

        final InterruptableConsumer<Connection> connConsumer = conn -> {
            Assertions.assertNotNull(conn);
            Assertions.assertTrue(conn.connected());
            Assertions.assertFalse(conn.isOutbound());
            Assertions.assertEquals(conn.getSelfId(), node1);
        };

        final InboundConnectionHandler inbound =
                new InboundConnectionHandler(platformContext, ct, node1Peers, node1, connConsumer, Time.getCurrent());
        inbound.handle(socket); // 2 can talk to 1 via tls ok
        socket.close();
    }

    /**
     * asserts that when an inbound connection unsuccessfully identifies a peer, connection is dropped
     */
    @ParameterizedTest
    @MethodSource({"com.swirlds.platform.crypto.CryptoArgsProvider#basicTestArgs"})
    void handleInboundNoPeerTest(final Roster roster, final Map<NodeId, KeysAndCerts> keysAndCerts) throws Throwable {
        assertTrue(roster.rosterEntries().size() > 1, "Address book must contain at least 2 nodes");
        final Random random = new Random();
        final List<Integer> nodeIndexes = random.ints(0, roster.rosterEntries().size())
                .distinct()
                .limit(2)
                .boxed()
                .toList();
        final NodeId node1 =
                NodeId.of(roster.rosterEntries().get(nodeIndexes.get(0)).nodeId());
        final NodeId node2 =
                NodeId.of(roster.rosterEntries().get(nodeIndexes.get(1)).nodeId());
        final KeysAndCerts keysAndCerts1 = keysAndCerts.get(node1);
        final KeysAndCerts keysAndCerts2 = keysAndCerts.get(node2);

        final List<PeerInfo> node1Peers = Utilities.createPeerInfoList(roster, node1);
        final List<PeerInfo> node2Peers = Utilities.createPeerInfoList(roster, node2);

        final SocketFactory s1 =
                NetworkUtils.createSocketFactory(node1, node1Peers, keysAndCerts1, TLS_NO_IP_TOS_CONFIG);
        final SocketFactory s2 =
                NetworkUtils.createSocketFactory(node2, node2Peers, keysAndCerts2, TLS_NO_IP_TOS_CONFIG);

        final ServerSocket serverSocket = s1.createServerSocket(PORT);
        final Thread serverThread = createSocketThread(serverSocket);
        serverThread.start();

        final Socket socket = s2.createClientSocket(STRING_IP, PORT);
        socket.getOutputStream().write(TEST_DATA);

        final InterruptableConsumer<Connection> connConsumer =
                conn -> Assertions.fail("connection should never have been created");

        final InboundConnectionHandler inbound =
                new InboundConnectionHandler(platformContext, ct, node1Peers, node1, connConsumer, Time.getCurrent());
        inbound.handle(socket);
        Assertions.assertTrue(socket.isClosed());
        serverThread.join();
    }
}
