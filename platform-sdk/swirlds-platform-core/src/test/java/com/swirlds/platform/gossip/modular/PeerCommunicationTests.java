// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.gossip.modular;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import com.swirlds.common.constructable.ConstructableRegistry;
import com.swirlds.common.context.PlatformContext;
import com.swirlds.common.platform.NodeId;
import com.swirlds.common.threading.manager.AdHocThreadManager;
import com.swirlds.config.api.Configuration;
import com.swirlds.config.api.ConfigurationBuilder;
import com.swirlds.config.extensions.sources.SystemEnvironmentConfigSource;
import com.swirlds.config.extensions.sources.SystemPropertiesConfigSource;
import com.swirlds.platform.crypto.KeysAndCerts;
import com.swirlds.platform.crypto.PublicStores;
import com.swirlds.platform.gossip.NoOpIntakeEventCounter;
import com.swirlds.platform.gossip.ProtocolConfig;
import com.swirlds.platform.gossip.permits.SyncPermitProvider;
import com.swirlds.platform.gossip.sync.SyncManagerImpl;
import com.swirlds.platform.network.PeerInfo;
import com.swirlds.platform.network.communication.handshake.VersionCompareHandshake;
import com.swirlds.platform.network.protocol.HeartbeatProtocol;
import com.swirlds.platform.network.protocol.Protocol;
import com.swirlds.platform.network.protocol.ProtocolRunnable;
import com.swirlds.platform.system.BasicSoftwareVersion;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.IntStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class PeerCommunicationTests {

    private static final int MAX_NODES = 10;
    private static final int CHECK_LOOPS = 10;
    private static final int LOOP_WAIT = 200;

    private Map<NodeId, KeysAndCerts> perNodeCerts;
    private PlatformContext platformContext;
    private List<PeerInfo> allPeers;
    private PeerCommunication[] peerCommunications;
    private SyncGossipController[] controllers;
    private final ArrayList<CommunicationEvent> events = new ArrayList<>();

    @BeforeEach
    void testSetup() throws Exception {
        ConstructableRegistry.getInstance().registerConstructables("");

        final ConfigurationBuilder configurationBuilder = ConfigurationBuilder.create()
                .withSource(SystemEnvironmentConfigSource.getInstance())
                .withSource(SystemPropertiesConfigSource.getInstance())
                .autoDiscoverExtensions();

        configurationBuilder.withValue("socket.timeoutServerAcceptConnect", "100");
        configurationBuilder.withValue("socket.timeoutSyncClientSocket", "500");
        configurationBuilder.withValue("socket.timeoutSyncClientConnect", "100");
        configurationBuilder.withValue("socket.waitBetweenReconnects", "100");

        final Configuration configuration = configurationBuilder.build();

        this.platformContext = PlatformContext.create(configuration);
        events.clear();
    }

    @AfterEach
    void testTeardown() throws Exception {
        for (SyncGossipController controller : this.controllers) {
            controller.stop();
        }
    }

    private static final byte[] EMPTY_ARRAY = new byte[] {};

    private void loadAddressBook(int nodeCount) throws Exception {

        this.perNodeCerts = new HashMap<>();
        this.allPeers = new ArrayList<>();
        for (int i = 0; i < nodeCount; i++) {
            NodeId nodeId = NodeId.of(i);
            KeysAndCerts keysAndCerts =
                    KeysAndCerts.generate(nodeId, EMPTY_ARRAY, EMPTY_ARRAY, EMPTY_ARRAY, new PublicStores());
            perNodeCerts.put(nodeId, keysAndCerts);
            allPeers.add(new PeerInfo(nodeId, "127.0.0.1", 15301 + i, keysAndCerts.sigCert()));
        }
    }

    private void startNonConnected() {
        var threadManager = AdHocThreadManager.getStaticThreadManager();

        peerCommunications = new PeerCommunication[allPeers.size()];
        controllers = new SyncGossipController[allPeers.size()];

        for (int i = 0; i < allPeers.size(); i++) {
            var selfPeer = allPeers.get(i);
            var pc = new PeerCommunication(
                    platformContext, new ArrayList<>(), selfPeer, perNodeCerts.get(selfPeer.nodeId()));

            final ProtocolConfig protocolConfig =
                    platformContext.getConfiguration().getConfigData(ProtocolConfig.class);
            final VersionCompareHandshake versionCompareHandshake = new VersionCompareHandshake(
                    new BasicSoftwareVersion(1), !protocolConfig.tolerateMismatchedVersion());
            final List<ProtocolRunnable> handshakeProtocols = List.of(versionCompareHandshake);

            List<Protocol> protocols = new ArrayList<>();
            protocols.add(new TestProtocol(selfPeer.nodeId(), events));
            protocols.add(HeartbeatProtocol.create(platformContext, pc.getNetworkMetrics()));

            var globalThreads = pc.initialize(threadManager, handshakeProtocols, protocols);
            final List<DedicatedStoppableThread<NodeId>> threads = pc.buildProtocolThreadsFromCurrentNeighbors();

            SyncGossipController controller = new SyncGossipController(
                    new NoOpIntakeEventCounter(),
                    new SyncGossipSharedProtocolState(
                            pc.getNetworkMetrics(),
                            mock(SyncPermitProvider.class),
                            null,
                            mock(SyncManagerImpl.class),
                            new AtomicBoolean(),
                            null,
                            null,
                            null,
                            null));

            globalThreads.forEach(controller::registerThingToStart);
            controller.registerDedicatedThreads(threads);

            controller.start();

            peerCommunications[i] = pc;
            controllers[i] = controller;
        }
    }

    private void establishBidirectionalConnection(int nodeFrom, int... to) {
        var otherPeers = new ArrayList<PeerInfo>();
        for (int otherNodeIndex : to) {
            otherPeers.add(allPeers.get(otherNodeIndex));
        }
        controllers[nodeFrom].registerDedicatedThreads(
                peerCommunications[nodeFrom].addRemovePeers(otherPeers, Collections.emptyList()));
        controllers[nodeFrom].applyDedicatedThreadsToModify();

        for (int otherNodeIndex : to) {
            controllers[otherNodeIndex].registerDedicatedThreads(peerCommunications[otherNodeIndex].addRemovePeers(
                    Collections.singletonList(allPeers.get(nodeFrom)), Collections.emptyList()));
            controllers[otherNodeIndex].applyDedicatedThreadsToModify();
        }
    }

    private void disconnect(int nodeFrom, int to) {
        controllers[nodeFrom].registerDedicatedThreads(peerCommunications[nodeFrom].addRemovePeers(
                Collections.emptyList(), Collections.singletonList(allPeers.get(to))));
        controllers[nodeFrom].applyDedicatedThreadsToModify();
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

        controllers[0].registerDedicatedThreads(peerCommunications[0].addRemovePeers(
                Collections.singletonList(allPeers.get(1)), Collections.singletonList(allPeers.get(1))));
        controllers[0].applyDedicatedThreadsToModify();

        Thread.sleep(1000);
        clearEvents();
        validateCommunication(0, 1);
    }
}
