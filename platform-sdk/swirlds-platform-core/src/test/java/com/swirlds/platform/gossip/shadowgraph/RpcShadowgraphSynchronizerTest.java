// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.gossip.shadowgraph;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;

import com.swirlds.base.test.fixtures.time.FakeTime;
import com.swirlds.common.context.PlatformContext;
import com.swirlds.common.io.filesystem.FileSystemManager;
import com.swirlds.common.io.utility.NoOpRecycleBin;
import com.swirlds.common.merkle.crypto.MerkleCryptography;
import com.swirlds.common.merkle.crypto.MerkleCryptographyFactory;
import com.swirlds.common.merkle.synchronization.config.ReconnectConfig;
import com.swirlds.common.metrics.noop.NoOpMetrics;
import com.swirlds.config.api.Configuration;
import com.swirlds.config.api.ConfigurationBuilder;
import com.swirlds.config.extensions.sources.SystemEnvironmentConfigSource;
import com.swirlds.config.extensions.sources.SystemPropertiesConfigSource;
import com.swirlds.platform.gossip.FallenBehindManagerImpl;
import com.swirlds.platform.gossip.NoOpIntakeEventCounter;
import com.swirlds.platform.gossip.rpc.GossipRpcSender;
import com.swirlds.platform.gossip.rpc.SyncData;
import com.swirlds.platform.metrics.SyncMetrics;
import com.swirlds.platform.system.status.StatusActionSubmitter;
import com.swirlds.platform.system.status.actions.FallenBehindAction;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.function.Consumer;
import org.hiero.base.constructable.ConstructableRegistry;
import org.hiero.consensus.model.hashgraph.EventWindow;
import org.hiero.consensus.model.node.NodeId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class RpcShadowgraphSynchronizerTest {

    static final int NUM_NODES = 10;
    public static final SyncData EMPTY_SYNC_MESSAGE =
            new SyncData(EventWindow.getGenesisEventWindow(), List.of(), false);
    public static final SyncData EMPTY_SYNC_MESSAGE_IGNORE_EVENTS =
            new SyncData(EventWindow.getGenesisEventWindow(), List.of(), true);
    private PlatformContext platformContext;
    private SyncMetrics syncMetrics;
    private FallenBehindManagerImpl fallenBehindManager;
    private NodeId selfId;
    private Consumer eventHandler;
    private GossipRpcSender gossipSender;
    private RpcShadowgraphSynchronizer synchronizer;
    private StatusActionSubmitter statusSubmitter;
    private Consumer<Double> lagReporter;

    @BeforeEach
    void testSetup() throws Exception {
        ConstructableRegistry.getInstance().registerConstructables("");

        final ConfigurationBuilder configurationBuilder = ConfigurationBuilder.create()
                .withSource(SystemEnvironmentConfigSource.getInstance())
                .withSource(SystemPropertiesConfigSource.getInstance())
                .autoDiscoverExtensions();

        configurationBuilder.withValue("reconnect.fallenBehindThreshold", "0");

        final Configuration configuration = configurationBuilder.build();

        final FileSystemManager fileSystemManager = FileSystemManager.create(configuration);
        final MerkleCryptography merkleCryptography = MerkleCryptographyFactory.create(configuration);

        this.platformContext = PlatformContext.create(
                configuration,
                new FakeTime(Instant.now(), Duration.ofMillis(1)),
                new NoOpMetrics(),
                fileSystemManager,
                new NoOpRecycleBin(),
                merkleCryptography);

        this.syncMetrics = mock(SyncMetrics.class);
        this.selfId = NodeId.of(1);
        this.statusSubmitter = mock(StatusActionSubmitter.class);
        this.fallenBehindManager = new FallenBehindManagerImpl(
                selfId, NUM_NODES - 1, statusSubmitter, configuration.getConfigData(ReconnectConfig.class));

        this.eventHandler = mock(Consumer.class);
        this.gossipSender = mock(GossipRpcSender.class);
        this.lagReporter = mock(Consumer.class);
        this.synchronizer = new RpcShadowgraphSynchronizer(
                platformContext,
                NUM_NODES,
                syncMetrics,
                eventHandler,
                fallenBehindManager,
                new NoOpIntakeEventCounter(),
                selfId,
                lagReporter);

        this.synchronizer.updateEventWindow(EventWindow.getGenesisEventWindow());
    }

    @Test
    void createPeerHandlerStartSync() {
        var otherNodeId = NodeId.of(5);
        var conversation = synchronizer.createPeerHandler(gossipSender, otherNodeId);
        conversation.checkForPeriodicActions(false, false);
        Mockito.verify(gossipSender).sendSyncData(any());
    }

    @Test
    void fullEmptySync() {
        var otherNodeId = NodeId.of(5);
        var conversation = synchronizer.createPeerHandler(gossipSender, otherNodeId);
        conversation.checkForPeriodicActions(false, false);
        Mockito.verify(gossipSender).sendSyncData(any());
        conversation.receiveSyncData(EMPTY_SYNC_MESSAGE);
        Mockito.verify(gossipSender).sendTips(List.of());
        conversation.receiveTips(List.of());
        Mockito.verify(gossipSender).sendEvents(List.of());
        Mockito.verify(gossipSender).sendEndOfEvents();
        Mockito.verifyNoMoreInteractions(gossipSender);
    }

    @Test
    void fullEmptySyncIgnoreEvents() {
        var otherNodeId = NodeId.of(5);
        var conversation = synchronizer.createPeerHandler(gossipSender, otherNodeId);
        conversation.checkForPeriodicActions(false, true);
        Mockito.verify(gossipSender).sendSyncData(any());
        conversation.receiveSyncData(EMPTY_SYNC_MESSAGE_IGNORE_EVENTS);
        Mockito.verify(gossipSender).sendTips(List.of());
        conversation.receiveTips(List.of());
        Mockito.verify(gossipSender).sendEndOfEvents();
        Mockito.verifyNoMoreInteractions(gossipSender);
    }

    @Test
    void testFallenBehind() {
        var otherNodeId = NodeId.of(5);
        var conversation = synchronizer.createPeerHandler(gossipSender, otherNodeId);
        conversation.checkForPeriodicActions(false, false);
        Mockito.verify(gossipSender).sendSyncData(any());
        conversation.receiveSyncData(new SyncData(new EventWindow(100, 101, 10, 5), List.of(), false));
        Mockito.verify(lagReporter).accept(100.0);
        Mockito.verify(gossipSender).breakConversation();
        Mockito.verifyNoMoreInteractions(gossipSender);
        Mockito.verify(statusSubmitter).submitStatusAction(new FallenBehindAction());
    }

    @Test
    void testMedianLag() {
        for (int i = 2; i <= 5; i++) {
            var otherNodeId = NodeId.of(i);
            var conversation = synchronizer.createPeerHandler(gossipSender, otherNodeId);
            conversation.checkForPeriodicActions(false, false);
            conversation.receiveSyncData(new SyncData(new EventWindow(20 + i, 20 + i, 10, 5), List.of(), false));
            Mockito.verify(lagReporter).accept(21 + i / 2.0);
        }
    }

    @Test
    void testMedianLagDifferentOrder() {
        for (int i = 5; i >= 2; i--) {
            var otherNodeId = NodeId.of(i);
            var conversation = synchronizer.createPeerHandler(gossipSender, otherNodeId);
            conversation.checkForPeriodicActions(false, false);
            conversation.receiveSyncData(new SyncData(new EventWindow(20 + i, 20 + i, 10, 5), List.of(), false));
            if (i != 2) {
                Mockito.reset(lagReporter);
            }
        }

        Mockito.verify(lagReporter).accept(23.5);
    }

    @Test
    void testUnhealthyExit() {
        var otherNodeId = NodeId.of(5);
        var conversation = synchronizer.createPeerHandler(gossipSender, otherNodeId);
        // we don't want to start sync in unhealthy state
        assertFalse(conversation.checkForPeriodicActions(true, false));
        Mockito.verifyNoMoreInteractions(gossipSender);

        // we are now healthy, so start sync
        assertTrue(conversation.checkForPeriodicActions(false, false));
        Mockito.verify(gossipSender).sendSyncData(any());

        // event if system is unhealthy, we need to finish sync
        assertTrue(conversation.checkForPeriodicActions(true, false));
        conversation.receiveSyncData(new SyncData(new EventWindow(100, 101, 10, 5), List.of(), false));
        Mockito.verify(gossipSender).breakConversation();
        Mockito.verify(statusSubmitter).submitStatusAction(new FallenBehindAction());

        // if sync is finished, we shouldn't be starting new one if system is unhealthy
        assertFalse(conversation.checkForPeriodicActions(true, false));
        Mockito.verifyNoMoreInteractions(gossipSender);
    }

    @Test
    void removeFallenBehind() {
        var otherNodeId = NodeId.of(5);
        var conversation = synchronizer.createPeerHandler(gossipSender, otherNodeId);
        synchronizer.updateEventWindow(new EventWindow(100, 101, 10, 5));
        conversation.checkForPeriodicActions(false, false);
        Mockito.verify(gossipSender).sendSyncData(any());
        conversation.receiveSyncData(EMPTY_SYNC_MESSAGE);
        ((FakeTime) this.platformContext.getTime()).tick(Duration.ofSeconds(10));
        conversation.checkForPeriodicActions(false, false);
        ((FakeTime) this.platformContext.getTime()).tick(Duration.ofSeconds(10));
        conversation.checkForPeriodicActions(false, false);
        ((FakeTime) this.platformContext.getTime()).tick(Duration.ofSeconds(10));
        conversation.checkForPeriodicActions(false, false);
        ((FakeTime) this.platformContext.getTime()).tick(Duration.ofSeconds(10));
        Mockito.verifyNoMoreInteractions(gossipSender);
        Mockito.clearInvocations(gossipSender);
        conversation.receiveSyncData(new SyncData(new EventWindow(100, 101, 10, 5), List.of(), false));
        ((FakeTime) this.platformContext.getTime()).tick(Duration.ofSeconds(10));
        conversation.checkForPeriodicActions(false, false);
        Mockito.verify(gossipSender).sendSyncData(any());
    }
}
