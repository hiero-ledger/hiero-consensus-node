// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.gossip.shadowgraph;

import static org.hiero.consensus.model.event.AncientMode.GENERATION_THRESHOLD;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.swirlds.common.context.PlatformContext;
import com.swirlds.common.merkle.synchronization.config.ReconnectConfig;
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
import java.util.List;
import java.util.Random;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import org.hiero.base.constructable.ConstructableRegistry;
import org.hiero.consensus.model.hashgraph.EventWindow;
import org.hiero.consensus.model.node.NodeId;
import org.hiero.consensus.model.test.fixtures.event.TestingEventBuilder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class GossipRpcShadowgraphSynchronizerTest {

    static final int NUM_NODES = 10;
    private PlatformContext platformContext;
    private Shadowgraph shadowgraph;
    private SyncMetrics syncMetrics;
    private FallenBehindManagerImpl fallenBehindManager;
    private NodeId selfId;
    private Consumer eventHandler;
    private GossipRpcSender gossipSender;
    private GossipRpcShadowgraphSynchronizer synchronizer;
    private StatusActionSubmitter statusSubmitter;

    @BeforeEach
    void testSetup() throws Exception {
        ConstructableRegistry.getInstance().registerConstructables("");

        final ConfigurationBuilder configurationBuilder = ConfigurationBuilder.create()
                .withSource(SystemEnvironmentConfigSource.getInstance())
                .withSource(SystemPropertiesConfigSource.getInstance())
                .autoDiscoverExtensions();

        configurationBuilder.withValue("reconnect.fallenBehindThreshold", "0");

        final Configuration configuration = configurationBuilder.build();

        this.platformContext = PlatformContext.create(configuration);

        this.shadowgraph = new Shadowgraph(platformContext, NUM_NODES, new NoOpIntakeEventCounter());
        this.shadowgraph.updateEventWindow(EventWindow.getGenesisEventWindow(GENERATION_THRESHOLD));

        this.syncMetrics = mock(SyncMetrics.class);
        this.selfId = NodeId.of(1);
        this.statusSubmitter = mock(StatusActionSubmitter.class);
        this.fallenBehindManager = new FallenBehindManagerImpl(
                selfId, NUM_NODES - 1, statusSubmitter, configuration.getConfigData(ReconnectConfig.class));

        this.eventHandler = mock(Consumer.class);
        this.gossipSender = mock(GossipRpcSender.class);
        when(gossipSender.sendEndOfEvents()).thenReturn(CompletableFuture.completedFuture(null));
        this.synchronizer = new GossipRpcShadowgraphSynchronizer(
                platformContext,
                shadowgraph,
                NUM_NODES,
                syncMetrics,
                eventHandler,
                fallenBehindManager,
                new NoOpIntakeEventCounter(),
                selfId);
    }

    @Test
    void synchronizeStartSync() {
        var otherNodeId = NodeId.of(5);
        var conversation = synchronizer.synchronize(gossipSender, selfId, otherNodeId);
        conversation.possiblyStartSync();
        Mockito.verify(gossipSender).sendSyncData(any());
    }

    @Test
    void fullEmptySync() {
        var otherNodeId = NodeId.of(5);
        var conversation = synchronizer.synchronize(gossipSender, selfId, otherNodeId);
        conversation.possiblyStartSync();
        Mockito.verify(gossipSender).sendSyncData(any());
        conversation.receiveSyncData(new SyncData(EventWindow.getGenesisEventWindow(GENERATION_THRESHOLD), List.of()));
        Mockito.verify(gossipSender).sendTips(List.of());
        conversation.receiveTips(List.of());
        Mockito.verify(gossipSender).sendEvents(List.of());
        Mockito.verify(gossipSender).sendEndOfEvents();
        Mockito.verifyNoMoreInteractions(gossipSender);
    }

    @Test
    void testFallenBehind() {
        var otherNodeId = NodeId.of(5);
        var conversation = synchronizer.synchronize(gossipSender, selfId, otherNodeId);
        conversation.possiblyStartSync();
        Mockito.verify(gossipSender).sendSyncData(any());
        conversation.receiveSyncData(
                new SyncData(new EventWindow(100, 101, 1000, 800, GENERATION_THRESHOLD), List.of()));
        Mockito.verify(gossipSender).breakConversation();
        Mockito.verifyNoMoreInteractions(gossipSender);
        Mockito.verify(statusSubmitter).submitStatusAction(new FallenBehindAction());
    }

    @Test
    void removeFallenBehind() {
        var otherNodeId = NodeId.of(5);
        var conversation = synchronizer.synchronize(gossipSender, selfId, otherNodeId);
        shadowgraph.updateEventWindow(new EventWindow(100, 101, 1000, 800, GENERATION_THRESHOLD));
        conversation.possiblyStartSync();
        Mockito.verify(gossipSender).sendSyncData(any());
        conversation.receiveSyncData(new SyncData(EventWindow.getGenesisEventWindow(GENERATION_THRESHOLD), List.of()));
        conversation.possiblyStartSync();
        conversation.possiblyStartSync();
        conversation.possiblyStartSync();
        Mockito.verifyNoMoreInteractions(gossipSender);
        Mockito.clearInvocations(gossipSender);
        conversation.receiveSyncData(
                new SyncData(new EventWindow(100, 101, 1000, 800, GENERATION_THRESHOLD), List.of()));
        Mockito.verify(gossipSender).sendSyncData(any());
    }

    @Test
    void broadcastSelfEvent() {
        var otherNodeId5 = NodeId.of(5);
        var otherNodeId7 = NodeId.of(7);
        var gossipSender7 = mock(GossipRpcSender.class);
        var conversation5 = synchronizer.synchronize(gossipSender, selfId, otherNodeId5);
        var conversation7 = synchronizer.synchronize(gossipSender7, selfId, otherNodeId7);
        var event = new TestingEventBuilder(new Random())
                .setSystemTransactionCount(0)
                .setAppTransactionCount(0)
                .setCreatorId(selfId)
                .build();
        var gossipEvent = event.getGossipEvent();
        synchronizer.addEvent(event);
        Mockito.verify(gossipSender).sendEvents(List.of(gossipEvent));
        Mockito.verify(gossipSender7).sendEvents(List.of(gossipEvent));
    }
}
