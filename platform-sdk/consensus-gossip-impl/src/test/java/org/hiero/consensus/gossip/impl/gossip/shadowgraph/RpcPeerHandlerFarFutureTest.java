// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.gossip.impl.gossip.shadowgraph;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hiero.base.utility.test.fixtures.RandomUtils.getRandomPrintSeed;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.hedera.hapi.platform.event.GossipEvent;
import com.swirlds.base.test.fixtures.time.FakeTime;
import com.swirlds.config.api.Configuration;
import com.swirlds.config.extensions.test.fixtures.TestConfigBuilder;
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
import org.hiero.consensus.gossip.impl.gossip.sync.SyncMetrics;
import org.hiero.consensus.model.event.PlatformEvent;
import org.hiero.consensus.model.hashgraph.EventWindow;
import org.hiero.consensus.model.node.NodeId;
import org.hiero.consensus.model.test.fixtures.event.TestingEventBuilder;
import org.hiero.consensus.monitoring.FallenBehindMonitor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * Tests that {@link RpcPeerHandler} drops events received from a peer, by sync or by broadcast, that are far future
 * events for this node, i.e. events whose birth round is greater than this node's pending round plus
 * {@code gossip.farFutureEventThreshold}.
 */
class RpcPeerHandlerFarFutureTest {

    private static final NodeId SELF = NodeId.of(0L);
    private static final NodeId PEER = NodeId.of(1L);
    private static final int THRESHOLD = 5;

    /** This node's event window: the pending round is 11, so the maximum acceptable birth round is 16. */
    private static final EventWindow MY_WINDOW = new EventWindow(10, 11, 1, 1);

    private static final long MAX_ACCEPTABLE_BIRTH_ROUND = MY_WINDOW.getPendingConsensusRound() + THRESHOLD;

    private Random random;
    private SyncMetrics syncMetrics;
    private IntakeEventCounter intakeEventCounter;
    private Consumer<PlatformEvent> eventHandler;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        random = getRandomPrintSeed();
        syncMetrics = mock(SyncMetrics.class);
        intakeEventCounter = mock(IntakeEventCounter.class);
        eventHandler = mock(Consumer.class);
    }

    private RpcPeerHandler createPeerHandler(final Supplier<EventWindow> eventWindowSupplier) {
        final Configuration configuration = new TestConfigBuilder()
                .withValue("gossip.farFutureEventThreshold", THRESHOLD)
                .getOrCreateConfig();
        return new RpcPeerHandler(
                mock(ShadowgraphSynchronizer.class),
                mock(GossipRpcSender.class),
                SELF,
                PEER,
                syncMetrics,
                new FakeTime(),
                intakeEventCounter,
                eventHandler,
                mock(SyncGuard.class),
                mock(FallenBehindMonitor.class),
                configuration.getConfigData(SyncConfig.class),
                configuration.getConfigData(GossipConfig.class),
                configuration.getConfigData(BroadcastConfig.class),
                eventWindowSupplier);
    }

    private GossipEvent gossipEventWithBirthRound(final long birthRound) {
        return new TestingEventBuilder(random)
                .setCreatorId(PEER)
                .setBirthRound(birthRound)
                .build()
                .getGossipEvent();
    }

    /** Returns the events passed on to intake, verifying that exactly {@code expectedCount} were passed. */
    private List<GossipEvent> eventsPassedToIntake(final int expectedCount) {
        final ArgumentCaptor<PlatformEvent> captor = ArgumentCaptor.forClass(PlatformEvent.class);
        verify(eventHandler, times(expectedCount)).accept(captor.capture());
        return captor.getAllValues().stream().map(PlatformEvent::getGossipEvent).toList();
    }

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
}
