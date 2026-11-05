// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.gossip.impl.gossip.shadowgraph;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hiero.base.utility.test.fixtures.RandomUtils.getRandomPrintSeed;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.hedera.hapi.platform.event.GossipEvent;
import com.swirlds.base.test.fixtures.time.FakeTime;
import com.swirlds.config.api.Configuration;
import com.swirlds.config.extensions.test.fixtures.TestConfigBuilder;
import java.time.Duration;
import java.util.List;
import java.util.Random;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Supplier;
import org.hiero.consensus.event.IntakeEventCounter;
import org.hiero.consensus.gossip.config.BroadcastConfig;
import org.hiero.consensus.gossip.config.GossipConfig;
import org.hiero.consensus.gossip.config.SyncConfig;
import org.hiero.consensus.gossip.impl.gossip.permits.SyncGuard;
import org.hiero.consensus.gossip.impl.gossip.rpc.GossipRpcSender;
import org.hiero.consensus.gossip.impl.gossip.rpc.SyncData;
import org.hiero.consensus.gossip.impl.gossip.sync.SyncMetrics;
import org.hiero.consensus.model.event.PlatformEvent;
import org.hiero.consensus.model.hashgraph.EventWindow;
import org.hiero.consensus.model.node.NodeId;
import org.hiero.consensus.model.test.fixtures.event.TestingEventBuilder;
import org.hiero.consensus.monitoring.FallenBehindMonitor;
import org.hiero.consensus.monitoring.FallenBehindStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * Tests far future event filtering in {@link RpcPeerHandler}:
 * <ul>
 *     <li>events received from the peer, by sync or by broadcast, are dropped if they are far future events for this
 *     node, based on this node's pending round</li>
 *     <li>self events are not broadcast to the peer if they are far future events for the peer, based on an estimate
 *     of the peer's pending round: the highest of the pending round in the event windows the peer sent in syncs and
 *     the birth rounds of the peer's own events received by broadcast</li>
 * </ul>
 * An event is a far future event if its birth round is greater than the pending round plus
 * {@code gossip.farFutureEventThreshold}.
 */
class RpcPeerHandlerFarFutureTest {

    private static final NodeId SELF = NodeId.of(0L);
    private static final NodeId PEER = NodeId.of(1L);
    private static final int THRESHOLD = 5;

    /** This node's event window: the pending round is 11, so the maximum acceptable birth round is 16. */
    private static final EventWindow MY_WINDOW = new EventWindow(10, 11, 1, 1);

    private static final long MAX_ACCEPTABLE_BIRTH_ROUND = MY_WINDOW.getPendingConsensusRound() + THRESHOLD;

    /**
     * This node's event window for the broadcast tests. It is well ahead of every peer window used, so none of the self
     * events broadcast by the tests is a far future event for this node; only the peer's window can hold them back.
     */
    private static final EventWindow MY_WINDOW_AHEAD_OF_PEER = new EventWindow(30, 31, 1, 1);

    /** A peer event window with pending round 11, so the maximum birth round the peer accepts is 16. */
    private static final EventWindow PEER_WINDOW = new EventWindow(10, 11, 1, 1);

    private static final long PEER_MAX_ACCEPTABLE_BIRTH_ROUND = PEER_WINDOW.getPendingConsensusRound() + THRESHOLD;

    private Random random;
    private FakeTime time;
    private SyncMetrics syncMetrics;
    private IntakeEventCounter intakeEventCounter;
    private Consumer<PlatformEvent> eventHandler;
    private GossipRpcSender sender;
    private ShadowgraphSynchronizer synchronizer;
    private SyncGuard syncGuard;
    private FallenBehindMonitor fallenBehindMonitor;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        random = getRandomPrintSeed();
        time = new FakeTime();
        syncMetrics = mock(SyncMetrics.class);
        intakeEventCounter = mock(IntakeEventCounter.class);
        eventHandler = mock(Consumer.class);
        sender = mock(GossipRpcSender.class);

        // enough of the synchronizer for the handler to complete an empty sync
        synchronizer = mock(ShadowgraphSynchronizer.class);
        final ReservedEventWindow reservedWindow = mock(ReservedEventWindow.class);
        when(reservedWindow.getEventWindow()).thenReturn(MY_WINDOW_AHEAD_OF_PEER);
        when(synchronizer.reserveEventWindow()).thenReturn(reservedWindow);
        when(synchronizer.getTips()).thenReturn(List.of());

        syncGuard = mock(SyncGuard.class);
        when(syncGuard.isSyncAllowed(any())).thenReturn(true);

        // neither side is behind, so the sync runs to completion
        fallenBehindMonitor = mock(FallenBehindMonitor.class);
        when(fallenBehindMonitor.check(any(), any(), any())).thenReturn(FallenBehindStatus.NONE_FALLEN_BEHIND);
    }

    private RpcPeerHandler createPeerHandler(final Supplier<EventWindow> selfEventWindowSupplier) {
        final Configuration configuration = new TestConfigBuilder()
                .withValue("gossip.farFutureEventThreshold", THRESHOLD)
                .getOrCreateConfig();
        return new RpcPeerHandler(
                synchronizer,
                sender,
                SELF,
                PEER,
                syncMetrics,
                time,
                intakeEventCounter,
                eventHandler,
                syncGuard,
                fallenBehindMonitor,
                configuration.getConfigData(SyncConfig.class),
                configuration.getConfigData(GossipConfig.class),
                configuration.getConfigData(BroadcastConfig.class),
                selfEventWindowSupplier);
    }

    private GossipEvent gossipEvent(final NodeId creator, final long birthRound) {
        return new TestingEventBuilder(random)
                .setCreatorId(creator)
                .setBirthRound(birthRound)
                .build()
                .getGossipEvent();
    }

    private GossipEvent gossipEventWithBirthRound(final long birthRound) {
        return gossipEvent(PEER, birthRound);
    }

    /** Returns the events passed on to intake, verifying that exactly {@code expectedCount} were passed. */
    private List<GossipEvent> eventsPassedToIntake(final int expectedCount) {
        final ArgumentCaptor<PlatformEvent> captor = ArgumentCaptor.forClass(PlatformEvent.class);
        verify(eventHandler, times(expectedCount)).accept(captor.capture());
        return captor.getAllValues().stream().map(PlatformEvent::getGossipEvent).toList();
    }

    /**
     * Runs a complete, empty sync with the peer in which the peer reports {@code peerWindow}. Once a sync has finished,
     * broadcast is running for this peer. Clears the recorded interactions with the sender afterwards.
     */
    private void completeSync(final RpcPeerHandler handler, final EventWindow peerWindow) {
        // move past the cooldown that follows a previous sync
        time.tick(Duration.ofSeconds(1));

        handler.checkForPeriodicActions(false, false);
        verify(sender).sendSyncData(any());
        handler.receiveSyncData(new SyncData(peerWindow, List.of(), false));
        verify(sender).sendTips(any());
        handler.receiveTips(List.of());
        verify(sender).sendEndOfEvents();
        handler.receiveEventsFinished();

        clearInvocations(sender);
    }

    // RECEIVING

    @Test
    void broadcastFarFutureEventIsDropped() {
        final RpcPeerHandler handler = createPeerHandler(() -> MY_WINDOW);

        handler.receiveBroadcastEvent(gossipEventWithBirthRound(MAX_ACCEPTABLE_BIRTH_ROUND + 1));
        verifyNoInteractions(eventHandler);
        verify(syncMetrics, never()).broadcastEventReceived();

        final GossipEvent atLimit = gossipEventWithBirthRound(MAX_ACCEPTABLE_BIRTH_ROUND);
        handler.receiveBroadcastEvent(atLimit);
        assertThat(eventsPassedToIntake(1)).containsExactly(atLimit);
        verify(syncMetrics).broadcastEventReceived();
    }

    @Test
    void syncFarFutureEventIsDropped() {
        final RpcPeerHandler handler = createPeerHandler(() -> MY_WINDOW);

        final GossipEvent atLimit = gossipEventWithBirthRound(MAX_ACCEPTABLE_BIRTH_ROUND);
        final GossipEvent farFuture = gossipEventWithBirthRound(MAX_ACCEPTABLE_BIRTH_ROUND + 1);
        final GossipEvent old = gossipEventWithBirthRound(1);
        handler.receiveEvents(List.of(atLimit, farFuture, old));

        assertThat(eventsPassedToIntake(2)).containsExactly(atLimit, old);
        // dropped events must not be counted as entering intake, or the counter would never be decremented for them
        verify(intakeEventCounter, times(2)).eventEnteredIntakePipeline(PEER);
    }

    @Test
    void farFutureCheckUsesLatestEventWindow() {
        final AtomicReference<EventWindow> eventWindow = new AtomicReference<>(MY_WINDOW);
        final RpcPeerHandler handler = createPeerHandler(eventWindow::get);

        final GossipEvent event = gossipEventWithBirthRound(MAX_ACCEPTABLE_BIRTH_ROUND + 1);
        handler.receiveBroadcastEvent(event);
        verifyNoInteractions(eventHandler);

        // consensus advances by one round, so the same event is no longer far future
        eventWindow.set(new EventWindow(11, 12, 1, 1));
        handler.receiveBroadcastEvent(event);
        assertThat(eventsPassedToIntake(1)).containsExactly(event);
    }

    // SENDING BY BROADCAST

    /**
     * A self event one round beyond the peer's limit is held back; one at the limit is broadcast. Neither is a far
     * future event for this node, so the boundary comes from the peer's window.
     */
    @Test
    void broadcastOfFarFutureEventForPeerIsHeldBack() {
        final RpcPeerHandler handler = createPeerHandler(() -> MY_WINDOW_AHEAD_OF_PEER);
        completeSync(handler, PEER_WINDOW);

        handler.broadcastEvent(gossipEvent(SELF, PEER_MAX_ACCEPTABLE_BIRTH_ROUND + 1));
        verify(sender, never()).sendBroadcastEvent(any());
        verify(syncMetrics).broadcastEventHeldBack();

        final GossipEvent atLimit = gossipEvent(SELF, PEER_MAX_ACCEPTABLE_BIRTH_ROUND);
        handler.broadcastEvent(atLimit);
        verify(sender).sendBroadcastEvent(atLimit);
        // only the held back event is counted
        verify(syncMetrics).broadcastEventHeldBack();
    }

    /** The peer's window is refreshed by every sync, so an event held back earlier is broadcast once the peer catches up. */
    @Test
    void broadcastUsesPeerWindowFromLatestSync() {
        final RpcPeerHandler handler = createPeerHandler(() -> MY_WINDOW_AHEAD_OF_PEER);
        final GossipEvent event = gossipEvent(SELF, PEER_MAX_ACCEPTABLE_BIRTH_ROUND + 1);

        completeSync(handler, PEER_WINDOW);
        handler.broadcastEvent(event);
        verify(sender, never()).sendBroadcastEvent(any());

        // the peer's pending round advances by one, so the event is no longer far future for it
        completeSync(handler, new EventWindow(11, 12, 1, 1));
        handler.broadcastEvent(event);
        verify(sender).sendBroadcastEvent(event);
    }

    /**
     * After a disconnect, the window from the old connection is not used. A peer that reconnects may have restarted
     * from an older state, with a lower pending round than before.
     */
    @Test
    void broadcastAfterReconnectUsesNewPeerWindow() {
        final RpcPeerHandler handler = createPeerHandler(() -> MY_WINDOW_AHEAD_OF_PEER);
        final GossipEvent event = gossipEvent(SELF, PEER_MAX_ACCEPTABLE_BIRTH_ROUND + 1);

        // the peer is close to this node before the disconnect, so the event is broadcast. The peer's estimated
        // pending round is raised by both its sync window and one of its own events.
        completeSync(handler, new EventWindow(20, 21, 1, 1));
        handler.receiveBroadcastEvent(gossipEvent(PEER, 22));
        handler.broadcastEvent(event);
        verify(sender).sendBroadcastEvent(event);
        clearInvocations(sender);

        // disconnect: nothing is broadcast until a sync completes on the new connection
        handler.cleanup();
        handler.broadcastEvent(event);
        verify(sender, never()).sendBroadcastEvent(any());

        // the peer comes back with a lower pending round, and the event is held back. This only holds if the estimate
        // from the old connection was discarded, since the estimate otherwise never decreases.
        completeSync(handler, PEER_WINDOW);
        handler.broadcastEvent(event);
        verify(sender, never()).sendBroadcastEvent(any());
    }

    /**
     * The birth round of an event created by the peer is a lower bound on the peer's pending round, so a broadcast event
     * from the peer raises the estimate between syncs.
     */
    @Test
    void peerBroadcastEventRaisesPeerPendingRoundEstimate() {
        final RpcPeerHandler handler = createPeerHandler(() -> MY_WINDOW_AHEAD_OF_PEER);
        completeSync(handler, PEER_WINDOW);

        final GossipEvent event = gossipEvent(SELF, PEER_MAX_ACCEPTABLE_BIRTH_ROUND + 1);
        handler.broadcastEvent(event);
        verify(sender, never()).sendBroadcastEvent(any());

        // the peer's event shows its pending round is at least 13, so the maximum it accepts is at least 18
        final long peerBirthRound = PEER_WINDOW.getPendingConsensusRound() + 2;
        handler.receiveBroadcastEvent(gossipEvent(PEER, peerBirthRound));

        handler.broadcastEvent(event);
        verify(sender).sendBroadcastEvent(event);

        final GossipEvent atNewLimit = gossipEvent(SELF, peerBirthRound + THRESHOLD);
        handler.broadcastEvent(atNewLimit);
        verify(sender).sendBroadcastEvent(atNewLimit);

        handler.broadcastEvent(gossipEvent(SELF, peerBirthRound + THRESHOLD + 1));
        verify(sender, times(2)).sendBroadcastEvent(any());
    }

    /**
     * The estimate is the highest value seen on the connection. A later sync window or peer event with a lower round
     * does not lower it, since the sync window can lag behind the birth rounds of the peer's newest events.
     */
    @Test
    void peerPendingRoundEstimateNeverDecreases() {
        final RpcPeerHandler handler = createPeerHandler(() -> MY_WINDOW_AHEAD_OF_PEER);
        completeSync(handler, PEER_WINDOW);

        final long peerBirthRound = PEER_WINDOW.getPendingConsensusRound() + 2;
        handler.receiveBroadcastEvent(gossipEvent(PEER, peerBirthRound));

        // lower values from a later peer event and a later sync
        handler.receiveBroadcastEvent(gossipEvent(PEER, peerBirthRound - 1));
        completeSync(handler, PEER_WINDOW);

        final GossipEvent atLimit = gossipEvent(SELF, peerBirthRound + THRESHOLD);
        handler.broadcastEvent(atLimit);
        verify(sender).sendBroadcastEvent(atLimit);
    }

    /** Only events created by the peer say anything about the peer's pending round. */
    @Test
    void eventsCreatedByOtherNodesDoNotRaisePeerPendingRoundEstimate() {
        final RpcPeerHandler handler = createPeerHandler(() -> MY_WINDOW_AHEAD_OF_PEER);
        completeSync(handler, PEER_WINDOW);

        handler.receiveBroadcastEvent(gossipEvent(NodeId.of(2L), PEER_MAX_ACCEPTABLE_BIRTH_ROUND + 10));

        handler.broadcastEvent(gossipEvent(SELF, PEER_MAX_ACCEPTABLE_BIRTH_ROUND + 1));
        verify(sender, never()).sendBroadcastEvent(any());
    }

    /**
     * While this node is unhealthy it ignores incoming broadcast events, but its own broadcasts keep running, so the
     * peer's events must still update the estimate.
     */
    @Test
    void peerEventIgnoredWhileUnhealthyStillRaisesPeerPendingRoundEstimate() {
        final RpcPeerHandler handler = createPeerHandler(() -> MY_WINDOW_AHEAD_OF_PEER);
        completeSync(handler, PEER_WINDOW);

        // start ignoring incoming events; the post-sync cooldown has not elapsed, so no new sync starts
        handler.checkForPeriodicActions(false, true);
        verify(sender, never()).sendSyncData(any());

        final long peerBirthRound = PEER_WINDOW.getPendingConsensusRound() + 2;
        handler.receiveBroadcastEvent(gossipEvent(PEER, peerBirthRound));
        verifyNoInteractions(eventHandler);

        final GossipEvent event = gossipEvent(SELF, PEER_MAX_ACCEPTABLE_BIRTH_ROUND + 1);
        handler.broadcastEvent(event);
        verify(sender).sendBroadcastEvent(event);
    }
}
