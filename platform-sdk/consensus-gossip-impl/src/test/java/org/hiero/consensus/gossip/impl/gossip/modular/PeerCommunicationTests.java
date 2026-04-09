// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.gossip.impl.gossip.modular;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.hedera.hapi.node.base.SemanticVersion;
import com.swirlds.base.time.Time;
import com.swirlds.config.api.Configuration;
import com.swirlds.config.api.ConfigurationBuilder;
import com.swirlds.config.extensions.sources.SystemEnvironmentConfigSource;
import com.swirlds.config.extensions.sources.SystemPropertiesConfigSource;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;
import org.hiero.consensus.concurrent.manager.AdHocThreadManager;
import org.hiero.consensus.crypto.KeysAndCertsGenerator;
import org.hiero.consensus.gossip.config.ProtocolConfig;
import org.hiero.consensus.gossip.impl.network.PeerCommunication;
import org.hiero.consensus.gossip.impl.network.PeerInfo;
import org.hiero.consensus.gossip.impl.network.communication.handshake.VersionCompareHandshake;
import org.hiero.consensus.gossip.impl.network.protocol.HeartbeatProtocol;
import org.hiero.consensus.gossip.impl.network.protocol.Protocol;
import org.hiero.consensus.gossip.impl.network.protocol.ProtocolRunnable;
import org.hiero.consensus.metrics.noop.NoOpMetrics;
import org.hiero.consensus.model.node.KeysAndCerts;
import org.hiero.consensus.model.node.NodeId;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class PeerCommunicationTests {

    private static final int MAX_NODES = 10;
    private static final int CHECK_LOOPS = 30;
    private static final int LOOP_WAIT = 500;

    private Map<NodeId, KeysAndCerts> perNodeCerts;
    private Configuration configuration;
    private List<PeerInfo> allPeers;
    private PeerCommunication[] peerCommunications;
    private final List<TestPeerProtocol> protocolsForDebug = Collections.synchronizedList(new ArrayList<>());
    private final ArrayList<CommunicationEvent> events = new ArrayList<>();

    @BeforeEach
    void testSetup() {
        final ConfigurationBuilder configurationBuilder = ConfigurationBuilder.create()
                .withSource(SystemEnvironmentConfigSource.getInstance())
                .withSource(SystemPropertiesConfigSource.getInstance())
                .autoDiscoverExtensions();

        configurationBuilder.withValue("socket.timeoutServerAcceptConnect", "100");
        configurationBuilder.withValue("socket.timeoutSyncClientSocket", "100");
        configurationBuilder.withValue("socket.timeoutSyncClientConnect", "100");

        this.configuration = configurationBuilder.build();

        events.clear();
        protocolsForDebug.clear();
    }

    @AfterEach
    void testTeardown() {
        for (PeerCommunication pc : this.peerCommunications) {
            pc.stop();
        }
    }

    private static final byte[] EMPTY_ARRAY = new byte[] {};

    private void loadAddressBook(int nodeCount) throws Exception {

        // 15301 is used by other tests and may cause conflicts with parallel runs
        final int portBase = 16301;

        this.perNodeCerts = new HashMap<>();
        this.allPeers = new ArrayList<>();
        for (int i = 0; i < nodeCount; i++) {
            NodeId nodeId = NodeId.of(i);
            KeysAndCerts keysAndCerts = KeysAndCertsGenerator.generate(nodeId);
            perNodeCerts.put(nodeId, keysAndCerts);
            allPeers.add(new PeerInfo(nodeId, "127.0.0.1", portBase + i, keysAndCerts.sigCert()));
        }
    }

    private void startNonConnected() {
        var threadManager = AdHocThreadManager.getStaticThreadManager();

        peerCommunications = new PeerCommunication[allPeers.size()];

        for (int i = 0; i < allPeers.size(); i++) {
            var selfPeer = allPeers.get(i);
            var pc = new PeerCommunication(
                    configuration,
                    new NoOpMetrics(),
                    Time.getCurrent(),
                    new ArrayList<>(),
                    selfPeer,
                    perNodeCerts.get(selfPeer.nodeId()));

            final ProtocolConfig protocolConfig = configuration.getConfigData(ProtocolConfig.class);
            final VersionCompareHandshake versionCompareHandshake = new VersionCompareHandshake(
                    SemanticVersion.newBuilder().major(1).build(), !protocolConfig.tolerateMismatchedVersion());
            final List<ProtocolRunnable> handshakeProtocols = List.of(versionCompareHandshake);

            List<Protocol> protocols = new ArrayList<>();
            var testProtocol = new TestProtocol(selfPeer.nodeId(), events, protocolsForDebug);
            protocols.add(testProtocol);
            protocols.add(HeartbeatProtocol.create(configuration, Time.getCurrent(), pc.getNetworkMetrics()));

            pc.initialize(threadManager, handshakeProtocols, protocols);

            pc.start();

            peerCommunications[i] = pc;
        }
    }

    private void establishBidirectionalConnection(int nodeFrom, int... to) {
        var otherPeers = new ArrayList<PeerInfo>();
        for (int otherNodeIndex : to) {
            otherPeers.add(allPeers.get(otherNodeIndex));
        }
        peerCommunications[nodeFrom].addRemovePeers(otherPeers, Collections.emptyList());

        for (int otherNodeIndex : to) {
            peerCommunications[otherNodeIndex].addRemovePeers(
                    Collections.singletonList(allPeers.get(nodeFrom)), Collections.emptyList());
        }
    }

    private void disconnect(int nodeFrom, int to) {
        peerCommunications[nodeFrom].addRemovePeers(
                Collections.emptyList(), Collections.singletonList(allPeers.get(to)));
    }

    private void validateCommunication(int nodeA, int nodeB) {
        validateCommunication(nodeA, nodeB, 1);
    }

    private void validateCommunication(int nodeA, int nodeB, int threshold) {
        for (int i = 0; i < CHECK_LOOPS; i++) {
            synchronized (events) {
                if (events.stream().filter(evt -> evt.isFrom(nodeA, nodeB)).count() >= threshold
                        && events.stream()
                                        .filter(evt -> evt.isFrom(nodeB, nodeA))
                                        .count()
                                >= threshold) {
                    return;
                }
            }
            try {
                Thread.sleep(LOOP_WAIT);
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
        }

        synchronized (events) {
            // if we haven't done early exit, something is fishy, even if we succeed just afterwards
            // let's dump whatever metadata we can

            synchronized (protocolsForDebug) {
                protocolsForDebug.forEach(protocol -> {
                    System.out.println(protocol.getDebugInfo());
                });
            }

            // this is a temporary debug code, will be removed
            ThreadMXBean threadMxBean = ManagementFactory.getThreadMXBean();

            for (ThreadInfo ti : threadMxBean.dumpAllThreads(true, true)) {
                System.out.print(ti.toString());
            }

            assertTrue(
                    events.stream().filter(evt -> evt.isFrom(nodeA, nodeB)).count() >= threshold,
                    () -> "Expected communication between " + nodeA + " and " + nodeB);
            assertTrue(
                    events.stream().filter(evt -> evt.isFrom(nodeB, nodeA)).count() >= threshold,
                    () -> "Expected communication between " + nodeB + " and " + nodeA);
        }
    }

    private void validateNoCommunication(int nodeA, int nodeB) {
        synchronized (events) {
            assertEquals(
                    0, events.stream().filter(evt -> evt.isFrom(nodeA, nodeB)).count());
            assertEquals(
                    0, events.stream().filter(evt -> evt.isFrom(nodeB, nodeA)).count());
        }
    }

    private void clearEvents() {
        synchronized (events) {
            events.clear();
        }
    }

    int[] range(int fromInclusive, int toInclusive) {
        return IntStream.range(fromInclusive, toInclusive + 1).toArray();
    }

    @Test
    public void testBasic() throws Exception {

        loadAddressBook(5);
        startNonConnected();
        establishBidirectionalConnection(0, 1, 2);

        validateCommunication(0, 1);
        validateCommunication(0, 2);
        validateNoCommunication(1, 2);
        validateNoCommunication(1, 3);
        validateNoCommunication(0, 3);
        validateNoCommunication(2, 3);

        establishBidirectionalConnection(2, 1);
        establishBidirectionalConnection(3, 1);
        clearEvents();

        validateCommunication(0, 1);
        validateCommunication(0, 2);
        validateCommunication(1, 2);
        validateCommunication(1, 3);
        validateNoCommunication(0, 3);
        validateNoCommunication(2, 3);
    }

    @Test
    // Used to be flaky, c.f. https://github.com/hiero-ledger/hiero-consensus-node/issues/18549
    // if it happens again, mark it with @Ignore and open a ticket referencing old one
    public void testFullyConnected() throws Exception {

        loadAddressBook(MAX_NODES);
        startNonConnected();
        for (int i = 0; i < MAX_NODES - 1; i++) {
            establishBidirectionalConnection(i, range(i + 1, MAX_NODES - 1));
        }

        for (int i = 0; i < MAX_NODES; i++) {
            for (int j = 0; j < MAX_NODES; j++) {
                if (i != j) {
                    validateCommunication(i, j);
                }
            }
        }
    }

    @Test
    public void testDisconnectOne() throws Exception {
        loadAddressBook(MAX_NODES);
        startNonConnected();
        for (int i = 0; i < MAX_NODES - 1; i++) {
            establishBidirectionalConnection(i, range(i + 1, MAX_NODES - 1));
        }

        for (int i = 0; i < MAX_NODES; i++) {
            for (int j = 0; j < MAX_NODES; j++) {
                if (i != j) {
                    validateCommunication(i, j);
                }
            }
        }

        disconnect(0, 5);
        Thread.sleep(3000);
        clearEvents();
        for (int i = 1; i < MAX_NODES; i++) {
            if (i != 5) {
                validateCommunication(0, i);
            } else {
                validateNoCommunication(0, i);
            }
        }
    }

    @Test
    public void testUpdateSamePeer() throws Exception {

        loadAddressBook(3);
        startNonConnected();
        establishBidirectionalConnection(0, 1, 2);

        validateCommunication(0, 1);
        validateCommunication(0, 2);
        validateNoCommunication(1, 2);
        validateNoCommunication(0, 3);

        peerCommunications[0].addRemovePeers(
                Collections.singletonList(allPeers.get(1)), Collections.singletonList(allPeers.get(1)));

        Thread.sleep(1000);
        clearEvents();
        validateCommunication(0, 1);
    }
}
