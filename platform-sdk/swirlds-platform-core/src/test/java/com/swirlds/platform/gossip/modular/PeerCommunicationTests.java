// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.gossip.modular;

import static com.swirlds.common.io.utility.FileUtils.rethrowIO;
import static java.nio.file.StandardCopyOption.REPLACE_EXISTING;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
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
import com.swirlds.platform.config.legacy.LegacyConfigPropertiesLoader;
import com.swirlds.platform.crypto.EnhancedKeyStoreLoader;
import com.swirlds.platform.crypto.KeysAndCerts;
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
import com.swirlds.platform.system.address.Address;
import com.swirlds.platform.system.address.AddressBook;
import com.swirlds.platform.test.fixtures.resource.ResourceLoader;
import com.swirlds.platform.util.BootstrapUtils;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

public class PeerCommunicationTests {

    @TempDir
    Path testDataDirectory;

    private AddressBook addressBook;
    private Map<NodeId, KeysAndCerts> kc;
    private PlatformContext platformContext;
    private ArrayList<PeerInfo> allPeers;
    private PeerCommunication[] peerCommunications;
    private SyncGossipController[] controllers;
    private ArrayList<CommunicationEvent> events = new ArrayList<>();

    @BeforeEach
    void testSetup() throws Exception {
        ConstructableRegistry.getInstance().registerConstructables("");
        final ResourceLoader<PeerCommunicationTests> loader = new ResourceLoader<>(PeerCommunicationTests.class);
        final Path tempDir = loader.loadDirectory("com/swirlds/platform/gossip.files");

        Files.move(tempDir, testDataDirectory, REPLACE_EXISTING);

        final ConfigurationBuilder configurationBuilder = ConfigurationBuilder.create()
                .withSource(SystemEnvironmentConfigSource.getInstance())
                .withSource(SystemPropertiesConfigSource.getInstance());

        rethrowIO(() -> BootstrapUtils.setupConfigBuilder(
                configurationBuilder, testDataDirectory.resolve("settings.txt").toAbsolutePath()));
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

    /**
     * A helper method used to load the {@code config.txt} configuration file and extract the address book.
     *
     * @return the fully initialized address book.
     */
    private AddressBook addressBook() {
        return LegacyConfigPropertiesLoader.loadConfigFile(testDataDirectory.resolve("config.txt"))
                .getAddressBook();
    }

    /**
     * A helper method used to load the {@code settings.txt} configuration file and override the default key directory
     * path with the provided key directory path.
     *
     * @param keyDirectory the key directory path to use.
     * @return a fully initialized configuration object with the key path overridden.
     * @throws IOException if an I/O error occurs while loading the configuration file.
     */
    private Configuration configure(final Path keyDirectory) throws IOException {
        final ConfigurationBuilder builder = ConfigurationBuilder.create();
        BootstrapUtils.setupConfigBuilder(builder, testDataDirectory.resolve("settings.txt"));

        builder.withValue("paths.keysDirPath", keyDirectory.toAbsolutePath().toString());

        return builder.build();
    }

    private void loadAddressBook(int nodeCount) throws Exception {

        this.addressBook = addressBook();

        for (int i = this.addressBook.getSize(); i > nodeCount; i--) {
            this.addressBook.remove(this.addressBook.getNodeId(i - 1));
        }

        final Set<NodeId> nodesToStart = addressBook.getNodeIdSet();

        final Path keyDirectory = testDataDirectory.resolve("certs");
        final EnhancedKeyStoreLoader loader =
                EnhancedKeyStoreLoader.using(addressBook, configure(keyDirectory), nodesToStart);

        assertThat(loader).isNotNull();
        assertThatCode(loader::migrate).doesNotThrowAnyException();
        assertThatCode(loader::scan).doesNotThrowAnyException();
        assertThatCode(loader::generate).doesNotThrowAnyException();
        assertThatCode(loader::verify).doesNotThrowAnyException();
        assertThatCode(loader::injectInAddressBook).doesNotThrowAnyException();

        this.kc = loader.keysAndCerts();

        this.allPeers = new ArrayList<PeerInfo>();
        for (Address address : addressBook) {
            var pi = new PeerInfo(
                    address.getNodeId(),
                    address.getHostnameExternal(),
                    address.getListenPort(),
                    kc.get(address.getNodeId()).sigCert());
            allPeers.add(pi);
        }
    }

    private void startNonConnected() {
        var threadManager = AdHocThreadManager.getStaticThreadManager();

        peerCommunications = new PeerCommunication[allPeers.size()];
        controllers = new SyncGossipController[allPeers.size()];

        for (int i = 0; i < allPeers.size(); i++) {
            var selfPeer = allPeers.get(i);
            var pc = new PeerCommunication(platformContext, new ArrayList<>(), selfPeer, kc.get(selfPeer.nodeId()));

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
        for (int oidx : to) {
            otherPeers.add(allPeers.get(oidx));
        }
        controllers[nodeFrom].registerDedicatedThreads(
                peerCommunications[nodeFrom].addRemovePeers(otherPeers, Collections.emptyList()));
        controllers[nodeFrom].applyDedicatedThreadsToModify();

        for (int oidx : to) {
            controllers[oidx].registerDedicatedThreads(peerCommunications[oidx].addRemovePeers(
                    Collections.singletonList(allPeers.get(nodeFrom)), Collections.emptyList()));
            controllers[oidx].applyDedicatedThreadsToModify();
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

    private void validateCommunication(int nodeA, int nodeB, int treshold) {
        for (int i = 0; i < 10; i++) {
            synchronized (events) {
                if (events.stream().filter(evt -> evt.isFrom(nodeA, nodeB)).count() >= treshold
                        && events.stream()
                                        .filter(evt -> evt.isFrom(nodeB, nodeA))
                                        .count()
                                >= treshold) {
                    return;
                }
            }
            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
        }

        synchronized (events) {
            assertTrue(
                    events.stream().filter(evt -> evt.isFrom(nodeA, nodeB)).count() >= treshold,
                    () -> "Expected communication between " + nodeA + " and " + nodeB);
            assertTrue(
                    events.stream().filter(evt -> evt.isFrom(nodeB, nodeA)).count() >= treshold,
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

    int[] rangeExcept(int upTo, int except) {
        int[] result = new int[upTo - 1];
        int i = 0;
        for (int node = 0; node < upTo; node++) {
            if (node != except) {
                result[i] = node;
                i++;
            }
        }
        return result;
    }

    int[] range(int fromInclusive, int toInclusive) {
        int[] result = new int[toInclusive - fromInclusive + 1];
        int i = 0;
        for (int node = fromInclusive; node <= toInclusive; node++) {
            result[i++] = node;
        }
        return result;
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

        loadAddressBook(10);
        startNonConnected();
        for (int i = 0; i < 9; i++) {
            establishBidirectionalConnection(i, range(i + 1, 9));
        }

        for (int i = 0; i < 10; i++) {
            for (int j = 0; j < 10; j++) {
                if (i != j) {
                    validateCommunication(i, j);
                }
            }
        }
    }

    @Test
    public void testDisconnectOne() throws Exception {
        loadAddressBook(10);
        startNonConnected();
        for (int i = 0; i < 9; i++) {
            establishBidirectionalConnection(i, range(i + 1, 9));
        }

        for (int i = 0; i < 10; i++) {
            for (int j = 0; j < 10; j++) {
                if (i != j) {
                    validateCommunication(i, j);
                }
            }
        }

        disconnect(0, 5);
        Thread.sleep(3000);
        clearEvents();
        for (int i = 1; i < 10; i++) {
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
