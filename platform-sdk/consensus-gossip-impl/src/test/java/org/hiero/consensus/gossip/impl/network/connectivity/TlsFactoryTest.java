// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.gossip.impl.network.connectivity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.hedera.hapi.node.state.roster.Roster;
import com.hedera.hapi.node.state.roster.RosterEntry;
import com.swirlds.config.api.Configuration;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.security.PrivateKey;
import java.security.cert.Certificate;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.hiero.base.crypto.config.CryptoConfig;
import org.hiero.base.crypto.config.CryptoConfig_;
import org.hiero.consensus.gossip.impl.gossip.Utilities;
import org.hiero.consensus.gossip.impl.network.NetworkUtils;
import org.hiero.consensus.gossip.impl.network.PeerInfo;
import org.hiero.consensus.model.node.KeysAndCerts;
import org.hiero.consensus.model.node.NodeId;
import org.hiero.consensus.roster.test.fixtures.RandomRosterBuilder;
import org.hiero.consensus.roster.test.fixtures.RosterWithKeys;
import org.hiero.consensus.test.fixtures.Randotron;
import org.hiero.consensus.test.fixtures.WeightGenerators;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The tests in this class are unidirectional server socket tests. For bidirectional tests, see
 * {@link SocketFactoryTest}
 */
class TlsFactoryTest extends ConnectivityTestBase {

    private static SocketFactory socketFactoryA;
    private static SocketFactory socketFactoryC;
    private static Socket clientSocketB;
    private static ServerSocket serverSocket;
    private static Thread serverThread;
    private final AtomicBoolean closeSeverConnection = new AtomicBoolean(false);
    List<PeerInfo> peersA;
    private int ephemeralPort;

    /**
     * Set up the test by creating the address book, keys and certs, and the socket factories for nodes A and B. The
     * base case is that the client socket of a node B can connect to the server socket of another node A. Subsequent
     * tests verify the different behaviours of a third node C
     */
    @BeforeEach
    void setUp() throws Throwable {
        // create addressBook, keysAndCerts
        final RosterWithKeys rosterAndCerts = genRosterLoadKeys(2);
        final Roster roster = rosterAndCerts.getRoster();
        final Map<NodeId, KeysAndCerts> keysAndCerts = rosterAndCerts.getAllKeysAndCerts();
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
        serverSocket = socketFactoryA.createServerSocket(0);
        this.ephemeralPort = serverSocket.getLocalPort();
        serverThread = createSocketThread(serverSocket, closeSeverConnection);

        clientSocketB = socketFactoryB.createClientSocket(STRING_IP, ephemeralPort);
        testSocket(serverThread, clientSocketB);
        Assertions.assertFalse(serverSocket.isClosed());

        // create a new address book with keys and new set of nodes
        final RosterWithKeys updatedRosterAndCerts = genRosterLoadKeys(6);
        final Roster updatedRoster = Roster.newBuilder()
                .rosterEntries(updatedRosterAndCerts.getRoster().rosterEntries().stream()
                        .map(entry -> {
                            if (entry.nodeId() == nodeA.id()) {
                                return entry.copyBuilder()
                                        .nodeId(updatedRosterAndCerts.getRoster().rosterEntries().stream()
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
        final Map<NodeId, KeysAndCerts> updatedKeysAndCerts = updatedRosterAndCerts.getAllKeysAndCerts();
        assertTrue(updatedRoster.rosterEntries().size() > 1, "Roster must contain at least 2 nodes");

        peersA = Utilities.createPeerInfoList(updatedRoster, nodeA); // Peers of A as in updated addressBook

        // pick a node for the 3rd connection C.
        final NodeId nodeC = NodeId.of(updatedRoster.rosterEntries().get(4).nodeId());
        final List<PeerInfo> peersC = Utilities.createPeerInfoList(updatedRoster, nodeC);
        socketFactoryC =
                NetworkUtils.createSocketFactory(nodeC, peersC, updatedKeysAndCerts.get(nodeC), TLS_NO_IP_TOS_CONFIG);
    }

    @AfterEach
    void tearDown() throws IOException, InterruptedException {
        closeSeverConnection.set(true);
        if (serverSocket != null && !serverSocket.isClosed()) {
            serverSocket.close();
        }
        if (serverThread != null && serverThread.isAlive()) {
            serverThread.join();
        }
        if (clientSocketB != null && !clientSocketB.isClosed()) {
            clientSocketB.close();
        }
    }

    /**
     * Asserts that for sockets A and B that can connect to each other, if A's peer list changes and in effect its trust
     * store reloaded, B, as well as a new peer C in the updated peer list can both connect to A.
     */
    @Test
    void tlsFactoryRefreshTest() throws Throwable {
        // we expect that C can't talk to A yet, as C's certificate is not yet in A's trust store
        assertThrows(IOException.class, () -> socketFactoryC.createClientSocket(STRING_IP, ephemeralPort));
        // re-initialize SSLContext for A using a new peer list which contains C
        socketFactoryA.reload(peersA);
        // now, we expect that C can talk to A
        final Socket clientSocketC = socketFactoryC.createClientSocket(STRING_IP, ephemeralPort);
        testSocket(serverThread, clientSocketC);
        // also, B can still talk to A
        testSocket(serverThread, clientSocketB);

        // we're done
        closeSeverConnection.set(true);
        serverThread.join();
        Assertions.assertTrue(serverSocket.isClosed());
    }

    @Test
    void tlsFactoryThrowsIfKeystorePasswordIsNull() {
        final Configuration configuration = mock(Configuration.class);
        when(configuration.getConfigData(CryptoConfig.class)).thenReturn(new CryptoConfig(null));

        final IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> new TlsFactory(
                        mock(Certificate.class), mock(PrivateKey.class), List.of(), NodeId.of(0), configuration));

        final String expectedMessage = CryptoConfig_.KEYSTORE_PASSWORD + " must not be null or blank";
        final String assertionMessage =
                "TlsFactory should fail fast when " + CryptoConfig_.KEYSTORE_PASSWORD + " is null";

        assertEquals(expectedMessage, exception.getMessage(), assertionMessage);
    }

    @Test
    void tlsFactoryThrowsIfKeystorePasswordIsBlank() {
        final Configuration configuration = mock(Configuration.class);
        when(configuration.getConfigData(CryptoConfig.class)).thenReturn(new CryptoConfig("   "));

        final IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> new TlsFactory(
                        mock(Certificate.class), mock(PrivateKey.class), List.of(), NodeId.of(0), configuration));

        final String expectedMessage = CryptoConfig_.KEYSTORE_PASSWORD + " must not be null or blank";
        final String assertionMessage =
                "TlsFactory should fail fast when " + CryptoConfig_.KEYSTORE_PASSWORD + " is blank";

        assertEquals(expectedMessage, exception.getMessage(), assertionMessage);
    }

    /**
     * Creates a roster.
     *
     * @param size the size of the required roster
     */
    @NonNull
    private static RosterWithKeys genRosterLoadKeys(final int size) {
        return RandomRosterBuilder.create(Randotron.create())
                .withSize(size)
                .withRealKeysEnabled(true)
                .withWeightGenerator(WeightGenerators.BALANCED_1000_PER_NODE)
                .buildWithKeys();
    }
}
