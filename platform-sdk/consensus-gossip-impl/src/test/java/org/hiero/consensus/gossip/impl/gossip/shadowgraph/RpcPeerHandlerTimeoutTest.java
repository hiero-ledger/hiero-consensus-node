// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.gossip.impl.gossip.shadowgraph;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.swirlds.base.test.fixtures.time.FakeTime;
import com.swirlds.config.api.Configuration;
import com.swirlds.config.extensions.test.fixtures.TestConfigBuilder;
import java.time.Duration;
import java.util.List;
import org.hiero.consensus.event.IntakeEventCounter;
import org.hiero.consensus.gossip.config.BroadcastConfig;
import org.hiero.consensus.gossip.config.SyncConfig;
import org.hiero.consensus.gossip.impl.gossip.permits.SyncGuard;
import org.hiero.consensus.gossip.impl.gossip.rpc.GossipRpcSender;
import org.hiero.consensus.gossip.impl.gossip.sync.SyncMetrics;
import org.hiero.consensus.model.hashgraph.EventWindow;
import org.hiero.consensus.model.node.NodeId;
import org.hiero.consensus.monitoring.FallenBehindMonitor;
import org.junit.jupiter.api.Test;

/**
 * Verifies that {@link RpcPeerHandler} aborts a sync that stalls with no progress for
 * {@link SyncConfig#maxSyncTime()}, while leaving a sync that keeps making progress alone.
 */
class RpcPeerHandlerTimeoutTest {

    private static final NodeId SELF = NodeId.of(0L);
    private static final NodeId PEER = NodeId.of(1L);
    private static final Duration MAX_SYNC_TIME = Duration.ofSeconds(30);

    @Test
    void stalledSyncIsAbortedAfterMaxSyncTime() {
        final FakeTime time = new FakeTime();

        final Configuration configuration =
                new TestConfigBuilder().withValue("sync.maxSyncTime", "30s").getOrCreateConfig();
        final SyncConfig syncConfig = configuration.getConfigData(SyncConfig.class);
        final BroadcastConfig broadcastConfig = configuration.getConfigData(BroadcastConfig.class);

        final GossipRpcSender sender = mock(GossipRpcSender.class);

        // enough of the synchronizer for the handler to start a sync
        final ShadowgraphSynchronizer synchronizer = mock(ShadowgraphSynchronizer.class);
        final ReservedEventWindow reservedWindow = mock(ReservedEventWindow.class);
        when(reservedWindow.getEventWindow()).thenReturn(EventWindow.getGenesisEventWindow());
        when(synchronizer.reserveEventWindow()).thenReturn(reservedWindow);
        when(synchronizer.getTips()).thenReturn(List.of());

        final SyncGuard syncGuard = mock(SyncGuard.class);
        when(syncGuard.isSyncAllowed(any())).thenReturn(true);

        final IntakeEventCounter intakeEventCounter = mock(IntakeEventCounter.class);
        when(intakeEventCounter.hasUnprocessedEvents(any())).thenReturn(false);

        final RpcPeerHandler handler = new RpcPeerHandler(
                synchronizer,
                sender,
                SELF,
                PEER,
                mock(SyncMetrics.class),
                time,
                intakeEventCounter,
                event -> {},
                syncGuard,
                mock(FallenBehindMonitor.class),
                syncConfig,
                broadcastConfig);

        // first tick starts a sync; the peer never replies
        handler.checkForPeriodicActions(false, false);
        verify(sender).sendSyncData(any());

        // just short of maxSyncTime: not aborted yet
        time.tick(MAX_SYNC_TIME.minusSeconds(1));
        handler.checkForPeriodicActions(false, false);
        verify(sender, never()).breakConversation();

        // past maxSyncTime with no progress: aborted
        time.tick(Duration.ofSeconds(2));
        handler.checkForPeriodicActions(false, false);
        verify(sender).breakConversation();

        // must break only once
        time.tick(MAX_SYNC_TIME);
        handler.checkForPeriodicActions(false, false);
        verify(sender, times(1)).breakConversation();
    }

    @Test
    void progressingSyncIsNotAborted() {
        final FakeTime time = new FakeTime();

        final Configuration configuration =
                new TestConfigBuilder().withValue("sync.maxSyncTime", "30s").getOrCreateConfig();
        final SyncConfig syncConfig = configuration.getConfigData(SyncConfig.class);
        final BroadcastConfig broadcastConfig = configuration.getConfigData(BroadcastConfig.class);

        final GossipRpcSender sender = mock(GossipRpcSender.class);

        final ShadowgraphSynchronizer synchronizer = mock(ShadowgraphSynchronizer.class);
        final ReservedEventWindow reservedWindow = mock(ReservedEventWindow.class);
        when(reservedWindow.getEventWindow()).thenReturn(EventWindow.getGenesisEventWindow());
        when(synchronizer.reserveEventWindow()).thenReturn(reservedWindow);
        when(synchronizer.getTips()).thenReturn(List.of());

        final SyncGuard syncGuard = mock(SyncGuard.class);
        when(syncGuard.isSyncAllowed(any())).thenReturn(true);

        final IntakeEventCounter intakeEventCounter = mock(IntakeEventCounter.class);
        when(intakeEventCounter.hasUnprocessedEvents(any())).thenReturn(false);

        final RpcPeerHandler handler = new RpcPeerHandler(
                synchronizer,
                sender,
                SELF,
                PEER,
                mock(SyncMetrics.class),
                time,
                intakeEventCounter,
                event -> {},
                syncGuard,
                mock(FallenBehindMonitor.class),
                syncConfig,
                broadcastConfig);

        // start a sync
        handler.checkForPeriodicActions(false, false);
        verify(sender).sendSyncData(any());

        // a sync that keeps receiving events is making progress and must never be aborted
        for (int i = 0; i < 5; i++) {
            time.tick(MAX_SYNC_TIME.minusSeconds(1));
            handler.receiveEvents(List.of()); // counts as progress
            handler.checkForPeriodicActions(false, false);
        }
        verify(sender, never()).breakConversation();
    }
}
