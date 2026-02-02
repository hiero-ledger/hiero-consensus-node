// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.gossip.shadowgraph;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;

import com.swirlds.base.test.fixtures.time.FakeTime;
import com.swirlds.config.api.Configuration;
import com.swirlds.config.api.ConfigurationBuilder;
import com.swirlds.config.extensions.sources.SystemEnvironmentConfigSource;
import com.swirlds.config.extensions.sources.SystemPropertiesConfigSource;
import com.swirlds.platform.test.fixtures.addressbook.RandomRosterBuilder;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Random;
import java.util.function.Consumer;
import org.hiero.base.constructable.ConstructableRegistry;
import org.hiero.consensus.event.IntakeEventCounter;
import org.hiero.consensus.gossip.impl.gossip.NoOpIntakeEventCounter;
import org.hiero.consensus.gossip.impl.gossip.permits.SyncGuard;
import org.hiero.consensus.gossip.impl.gossip.permits.SyncGuardFactory;
import org.hiero.consensus.gossip.impl.gossip.rpc.GossipRpcSender;
import org.hiero.consensus.gossip.impl.gossip.rpc.SyncData;
import org.hiero.consensus.gossip.impl.gossip.shadowgraph.RpcPeerHandler;
import org.hiero.consensus.gossip.impl.gossip.shadowgraph.ShadowgraphSynchronizer;
import org.hiero.consensus.gossip.impl.gossip.sync.SyncMetrics;
import org.hiero.consensus.metrics.noop.NoOpMetrics;
import org.hiero.consensus.model.gossip.SyncProgress;
import org.hiero.consensus.model.hashgraph.EventWindow;
import org.hiero.consensus.model.node.NodeId;
import org.hiero.consensus.monitoring.FallenBehindMonitor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class RpcPeerHandlerTest {

    static final int NUM_NODES = 10;
    public static final SyncData EMPTY_SYNC_MESSAGE =
            new SyncData(EventWindow.getGenesisEventWindow(), List.of(), false);
    public static final SyncData EMPTY_SYNC_MESSAGE_IGNORE_EVENTS =
            new SyncData(EventWindow.getGenesisEventWindow(), List.of(), true);
    private FakeTime time;
    private SyncMetrics syncMetrics;
    private FallenBehindMonitor fallenBehindManager;
    private NodeId selfId;
    private Consumer eventHandler;
    private GossipRpcSender gossipSender;
    private ShadowgraphSynchronizer synchronizer;
    private Consumer<SyncProgress> syncProgressReporter;
    private IntakeEventCounter intakeCounter;
    private SyncGuard syncGuard;

    @BeforeEach
    void testSetup() throws Exception {
        ConstructableRegistry.getInstance().registerConstructables("");

        final Configuration configuration = ConfigurationBuilder.create()
                .withSource(SystemEnvironmentConfigSource.getInstance())
                .withSource(SystemPropertiesConfigSource.getInstance())
                .autoDiscoverExtensions()
                .withValue("reconnect.fallenBehindThreshold", "0")
                .build();

        this.time = new FakeTime(Instant.now(), Duration.ofMillis(1));

        this.syncMetrics = mock(SyncMetrics.class);
        this.selfId = NodeId.of(1);
        this.fallenBehindManager = new FallenBehindMonitor(
                RandomRosterBuilder.create(new Random()).withSize(NUM_NODES).build(), configuration, new NoOpMetrics());
        this.eventHandler = mock(Consumer.class);
        this.gossipSender = mock(GossipRpcSender.class);
        this.syncProgressReporter = mock(Consumer.class);
        this.intakeCounter = new NoOpIntakeEventCounter();
        this.synchronizer = new ShadowgraphSynchronizer(
                configuration,
                new NoOpMetrics(),
                time,
                NUM_NODES,
                syncMetrics,
                fallenBehindManager,
                intakeCounter,
                syncProgressReporter);

        this.synchronizer.updateEventWindow(EventWindow.getGenesisEventWindow());

        this.syncGuard = SyncGuardFactory.create(-1, 0.3, 10);
    }

    @Test
    void createPeerHandlerStartSync() {
        var otherNodeId = NodeId.of(5);
        var conversation = createPeerHandler(gossipSender, otherNodeId);
        conversation.checkForPeriodicActions(false, false);
        Mockito.verify(gossipSender).sendSyncData(any());
    }

    private RpcPeerHandler createPeerHandler(final GossipRpcSender gossipSender, final NodeId otherNodeId) {
        return new RpcPeerHandler(
                synchronizer,
                gossipSender,
                selfId,
                otherNodeId,
                Duration.ofMillis(5),
                syncMetrics,
                time,
                intakeCounter,
                eventHandler,
                syncGuard,
                fallenBehindManager);
    }

    @Test
    void fullEmptySync() {
        var otherNodeId = NodeId.of(5);
        var conversation = createPeerHandler(gossipSender, otherNodeId);
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
    void errorOnDoubleSyncData() {
        var otherNodeId = NodeId.of(5);
        var conversation = createPeerHandler(gossipSender, otherNodeId);
        conversation.checkForPeriodicActions(false, false);
        Mockito.verify(gossipSender).sendSyncData(any());
        conversation.receiveSyncData(EMPTY_SYNC_MESSAGE);
        assertThrows(IllegalStateException.class, () -> conversation.receiveSyncData(EMPTY_SYNC_MESSAGE));
    }

    @Test
    void errorOnTipsWithoutSyncData() {
        var otherNodeId = NodeId.of(5);
        var conversation = createPeerHandler(gossipSender, otherNodeId);
        conversation.checkForPeriodicActions(false, false);
        Mockito.verify(gossipSender).sendSyncData(any());
        assertThrows(IllegalStateException.class, () -> conversation.receiveTips(List.of()));
    }

    @Test
    void errorOnDoubleTips() {
        var otherNodeId = NodeId.of(5);
        var conversation = createPeerHandler(gossipSender, otherNodeId);
        conversation.checkForPeriodicActions(false, false);
        Mockito.verify(gossipSender).sendSyncData(any());
        conversation.receiveSyncData(EMPTY_SYNC_MESSAGE);
        Mockito.verify(gossipSender).sendTips(List.of());
        conversation.receiveTips(List.of());
        Mockito.verify(gossipSender).sendEvents(List.of());
        Mockito.verify(gossipSender).sendEndOfEvents();
        assertThrows(IllegalStateException.class, () -> conversation.receiveTips(List.of()));
    }

    @Test
    void disconnectInMiddleOfEventSendingNotBreakingNextSync() {
        var otherNodeId = NodeId.of(5);
        var conversation = createPeerHandler(gossipSender, otherNodeId);
        conversation.checkForPeriodicActions(false, false);
        Mockito.verify(gossipSender).sendSyncData(any());
        conversation.receiveSyncData(EMPTY_SYNC_MESSAGE);
        Mockito.verify(gossipSender).sendTips(List.of());
        conversation.receiveTips(List.of());
        Mockito.verify(gossipSender).sendEvents(List.of());
        Mockito.verify(gossipSender).sendEndOfEvents();

        // emulate disconnect
        conversation.cleanup();
        time.tick(Duration.ofSeconds(10));
        Mockito.clearInvocations(gossipSender);

        // try starting new sync, even if old was broken in middle of receiving events
        conversation.checkForPeriodicActions(false, false);
        Mockito.verify(gossipSender).sendSyncData(any());
    }

    @Test
    void fullEmptySyncIgnoreEvents() {
        var otherNodeId = NodeId.of(5);
        var conversation = createPeerHandler(gossipSender, otherNodeId);
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
        var conversation = createPeerHandler(gossipSender, otherNodeId);
        conversation.checkForPeriodicActions(false, false);
        Mockito.verify(gossipSender).sendSyncData(any());
        conversation.receiveSyncData(new SyncData(new EventWindow(100, 101, 10, 5), List.of(), false));
        Mockito.verify(syncProgressReporter)
                .accept(new SyncProgress(otherNodeId, new EventWindow(0, 1, 1, 1), new EventWindow(100, 101, 10, 5)));
        Mockito.verify(gossipSender).breakConversation();
        Mockito.verifyNoMoreInteractions(gossipSender);
    }

    @Test
    void testSyncProgressReporting() {
        for (int i = 2; i <= 5; i++) {
            var otherNodeId = NodeId.of(i);
            var conversation = createPeerHandler(gossipSender, otherNodeId);
            conversation.checkForPeriodicActions(false, false);
            var eventWindow = new EventWindow(20 + i, 20 + i, 10, 5);
            conversation.receiveSyncData(new SyncData(eventWindow, List.of(), false));
            Mockito.verify(syncProgressReporter)
                    .accept(new SyncProgress(otherNodeId, new EventWindow(0, 1, 1, 1), eventWindow));
        }
    }

    @Test
    void testUnhealthyExit() {
        var otherNodeId = NodeId.of(5);
        var conversation = createPeerHandler(gossipSender, otherNodeId);
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

        // if sync is finished, we shouldn't be starting new one if system is unhealthy
        assertFalse(conversation.checkForPeriodicActions(true, false));
        Mockito.verifyNoMoreInteractions(gossipSender);
    }

    @Test
    void removeFallenBehind() {
        var otherNodeId = NodeId.of(5);
        var conversation = createPeerHandler(gossipSender, otherNodeId);
        synchronizer.updateEventWindow(new EventWindow(100, 101, 10, 5));
        conversation.checkForPeriodicActions(false, false);
        Mockito.verify(gossipSender).sendSyncData(any());
        conversation.receiveSyncData(EMPTY_SYNC_MESSAGE);
        time.tick(Duration.ofSeconds(10));
        conversation.checkForPeriodicActions(false, false);
        time.tick(Duration.ofSeconds(10));
        conversation.checkForPeriodicActions(false, false);
        time.tick(Duration.ofSeconds(10));
        conversation.checkForPeriodicActions(false, false);
        time.tick(Duration.ofSeconds(10));
        Mockito.verifyNoMoreInteractions(gossipSender);
        Mockito.clearInvocations(gossipSender);
        conversation.receiveSyncData(new SyncData(new EventWindow(100, 101, 10, 5), List.of(), false));
        time.tick(Duration.ofSeconds(10));
        conversation.checkForPeriodicActions(false, false);
        Mockito.verify(gossipSender).sendSyncData(any());
    }
}
