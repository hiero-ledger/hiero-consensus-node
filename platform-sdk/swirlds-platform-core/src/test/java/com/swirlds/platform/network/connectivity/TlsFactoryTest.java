// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.network.connectivity;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.hedera.hapi.node.state.roster.Roster;
import com.hedera.hapi.node.state.roster.RosterEntry;
import com.swirlds.platform.Utilities;
import com.swirlds.platform.crypto.CryptoArgsProvider;
import com.swirlds.platform.crypto.RosterAndCerts;
import com.swirlds.platform.network.NetworkUtils;
import com.swirlds.platform.network.PeerInfo;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.hiero.consensus.model.node.KeysAndCerts;
import org.hiero.consensus.model.node.NodeId;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The tests in this class are unidirectional server socket tests. For bidirectional tests, see
 * {@link SocketFactoryTest}
 */
class TlsFactoryTest extends ConnectivityTestBase {
    private static final int PORT = 34_000;

    private static SocketFactory socketFactoryA;
    private static SocketFactory socketFactoryC;
    private static Socket clientSocketB;
    private static ServerSocket serverSocket;
    private static Thread serverThread;
    private final AtomicBoolean closeSeverConnection = new AtomicBoolean(false);
    List<PeerInfo> peersA;
    /**
     * Set up the test by creating the address book, keys and certs, and the socket factories for nodes A and B. The
     * base case is that the client socket of a node B can connect to the server socket of another node A. Subsequent
     * tests verify the different behaviours of a third node C
     */
    @BeforeEach
    void setUp() throws Throwable {
        // create addressBook, keysAndCerts
        final RosterAndCerts rosterAndCerts = CryptoArgsProvider.loadAddressBookWithKeys(2);
        final Roster roster = rosterAndCerts.roster();
        final Map<NodeId, KeysAndCerts> keysAndCerts = rosterAndCerts.nodeIdKeysAndCertsMap();
        assertTrue(roster.rosterEntries().size() > 1, "Roster must contain at least 2 nodes");

        // choose 2 nodes to test connections
        final NodeId nodeA = NodeId.of(roster.rosterEntries().get(0).nodeId());
        final NodeId nodeB = NodeId.of(roster.rosterEntries().get(1).nodeId());

        peersA = Utilities.createPeerInfoList(roster, nodeA);
        final List<PeerInfo> peersB = Utilities.createPeerInfoList(roster, nodeB);

        // create their socket factories
        socketFactoryA = NetworkUtils.createSocketFactory(nodeA, peersA, keysAndCerts.get(nodeA), TLS_NO_IP_TOS_CONFIG);
        final SocketFactory socketFactoryB =
                NetworkUtils.createSocketFactory(nodeB, peersB, keysAndCerts.get(nodeB), TLS_NO_IP_TOS_CONFIG);

        // test that B can talk to A - A(serverSocket) -> B(clientSocket1)
        serverSocket = socketFactoryA.createServerSocket(PORT);
        serverThread = createSocketThread(serverSocket, closeSeverConnection);

        clientSocketB = socketFactoryB.createClientSocket(STRING_IP, PORT);
        testSocket(serverThread, clientSocketB);
        Assertions.assertFalse(serverSocket.isClosed());

        // create a new address book with keys and new set of nodes
        final RosterAndCerts updatedRosterAndCerts = CryptoArgsProvider.loadAddressBookWithKeys(6);
        final Roster updatedRoster = Roster.newBuilder()
                .rosterEntries(updatedRosterAndCerts.roster().rosterEntries().stream()
                        .map(entry -> {
                            if (entry.nodeId() == nodeA.id()) {
                                return entry.copyBuilder()
                                        .nodeId(updatedRosterAndCerts.roster().rosterEntries().stream()
                                                        .mapToLong(RosterEntry::nodeId)
                                                        .max()
                                                        .getAsLong()
                                                + 1)
                                        .build();
                            } else {
                                return entry;
                            }
                        })
                        .toList())
                .build();
        final Map<NodeId, KeysAndCerts> updatedKeysAndCerts = updatedRosterAndCerts.nodeIdKeysAndCertsMap();
        assertTrue(updatedRoster.rosterEntries().size() > 1, "Roster must contain at least 2 nodes");

        peersA = Utilities.createPeerInfoList(updatedRoster, nodeA); // Peers of A as in updated addressBook

        // pick a node for the 3rd connection C.
        final NodeId nodeC = NodeId.of(updatedRoster.rosterEntries().get(4).nodeId());
        final List<PeerInfo> peersC = Utilities.createPeerInfoList(updatedRoster, nodeC);
        socketFactoryC =
                NetworkUtils.createSocketFactory(nodeC, peersC, updatedKeysAndCerts.get(nodeC), TLS_NO_IP_TOS_CONFIG);
    }

    /**
     * Asserts that for sockets A and B that can connect to each other, if A's peer list changes and in effect its trust
     * store reloaded, B, as well as a new peer C in the updated peer list can both connect to A.
     */
    @Test
    void tlsFactoryRefreshTest() throws Throwable {
        // we expect that C can't talk to A yet, as C's certificate is not yet in A's trust store
        assertThrows(IOException.class, () -> socketFactoryC.createClientSocket(STRING_IP, PORT));
        // re-initialize SSLContext for A using a new peer list which contains C
        socketFactoryA.reload(peersA);
        // now, we expect that C can talk to A
        final Socket clientSocketC = socketFactoryC.createClientSocket(STRING_IP, PORT);
        testSocket(serverThread, clientSocketC);
        // also, B can still talk to A
        testSocket(serverThread, clientSocketB);

        // we're done
        closeSeverConnection.set(true);
        serverThread.join();
        Assertions.assertTrue(serverSocket.isClosed());
    }
}
