// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.status.monitor.internal;

import static org.hiero.base.utility.test.fixtures.RandomUtils.getRandomPrintSeed;
import static org.hiero.consensus.model.PbjConverters.toPbjTimestamp;
import static org.hiero.consensus.model.status.PlatformStatus.ACTIVE;
import static org.hiero.consensus.model.status.PlatformStatus.CHECKING;
import static org.hiero.consensus.model.status.PlatformStatus.OBSERVING;
import static org.hiero.consensus.model.status.PlatformStatus.REPLAYING_EVENTS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.hedera.hapi.platform.state.ConsensusSnapshot;
import com.swirlds.base.test.fixtures.time.FakeTime;
import com.swirlds.config.api.Configuration;
import com.swirlds.config.extensions.test.fixtures.TestConfigBuilder;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Random;
import org.hiero.consensus.fakes.noop.NoOpMetrics;
import org.hiero.consensus.main.model.NodeId;
import org.hiero.consensus.model.event.EventOrigin;
import org.hiero.consensus.model.event.PlatformEvent;
import org.hiero.consensus.model.hashgraph.ConsensusRound;
import org.hiero.consensus.model.hashgraph.EventWindow;
import org.hiero.consensus.model.roster.RosterWrapper;
import org.hiero.consensus.model.test.fixtures.event.TestingEventBuilder;
import org.hiero.consensus.model.test.fixtures.roster.RosterWrapperFactory;
import org.hiero.consensus.status.monitor.actions.DoneReplayingEventsAction;
import org.hiero.consensus.status.monitor.actions.StartedReplayingEventsAction;
import org.hiero.consensus.status.monitor.actions.TimeElapsedAction;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tests {@link DefaultPlatformMonitor#consensusRound(ConsensusRound)} — specifically the rule
 * that self events reaching consensus only via replay from local storage or via gossip must not
 * drive the {@code CHECKING} &rarr; {@code ACTIVE} transition.
 */
class DefaultPlatformMonitorTests {

    private FakeTime time;
    private Random random;
    private RosterWrapper roster;
    private NodeId selfId;
    private DefaultPlatformMonitor monitor;

    @BeforeEach
    void setup() {
        random = getRandomPrintSeed();
        time = new FakeTime();
        final Configuration configuration = new TestConfigBuilder().getOrCreateConfig();
        roster = RosterWrapperFactory.randomRoster(random, 4);
        selfId = roster.nodeIdAtIndex(0);
        monitor = new DefaultPlatformMonitor(configuration, new NoOpMetrics(), time, selfId);
    }

    @Test
    @DisplayName("consensusRound stays in CHECKING when only replayed self events reach consensus")
    void replayedSelfEventsDoNotActivateFromChecking() {
        driveToChecking();

        final ConsensusRound replayedRound = round(1, EventOrigin.STORAGE);

        // No status change — the sole self event was replayed from storage, not created now.
        assertNull(monitor.consensusRound(replayedRound));
    }

    @Test
    @DisplayName("consensusRound transitions CHECKING -> ACTIVE on a RUNTIME-origin self event")
    void runtimeSelfEventActivatesFromChecking() {
        driveToChecking();

        final ConsensusRound runtimeRound = round(1, EventOrigin.RUNTIME);

        assertEquals(ACTIVE, monitor.consensusRound(runtimeRound));
    }

    @Test
    @DisplayName("A round of replayed self events followed by a round with a RUNTIME self event activates")
    void replayedThenRuntimeActivates() {
        driveToChecking();

        assertNull(monitor.consensusRound(round(1, EventOrigin.STORAGE)));
        assertEquals(ACTIVE, monitor.consensusRound(round(2, EventOrigin.RUNTIME)));
    }

    /** Drives the monitor through the state machine into {@link #CHECKING}. */
    private void driveToChecking() {
        assertEquals(REPLAYING_EVENTS, monitor.submitStatusAction(new StartedReplayingEventsAction()));
        assertEquals(OBSERVING, monitor.submitStatusAction(new DoneReplayingEventsAction(time.now())));
        // Wait past the default observing-status delay (10s) so the next TimeElapsedAction flips to CHECKING.
        time.tick(Duration.ofSeconds(11));
        assertEquals(
                CHECKING,
                monitor.submitStatusAction(new TimeElapsedAction(
                        time.now(), new TimeElapsedAction.QuiescingStatus(false, time.now()))));
    }

    /**
     * Build a {@link ConsensusRound} containing one self event with the given origin plus some
     * other-node events for shape; consensus timestamps are strictly increasing across rounds.
     */
    private ConsensusRound round(final long roundNum, @NonNull final EventOrigin selfEventOrigin) {
        // Tick time forward so each round's events get strictly later consensus timestamps.
        time.tick(Duration.ofMillis(100));
        final PlatformEvent selfEvent = new TestingEventBuilder(random)
                .setCreatorId(selfId)
                .setConsensusTimestamp(time.now())
                .setOrigin(selfEventOrigin)
                .build();
        time.tick(Duration.ofMillis(10));
        final PlatformEvent otherEvent = new TestingEventBuilder(random)
                .setCreatorId(roster.nodeIdAtIndex(1))
                .setConsensusTimestamp(time.now())
                .setOrigin(EventOrigin.GOSSIP)
                .build();
        final List<PlatformEvent> events = List.of(selfEvent, otherEvent);

        final ConsensusSnapshot snapshot = mock(ConsensusSnapshot.class);
        final ConsensusRound consensusRound =
                new ConsensusRound(roster, events, mock(EventWindow.class), snapshot, false, Instant.now());
        final Instant consensusTimestamp = events.get(events.size() - 1).getConsensusTimestamp();
        when(snapshot.consensusTimestamp()).thenReturn(toPbjTimestamp(consensusTimestamp));
        when(snapshot.round()).thenReturn(roundNum);
        when(consensusRound.getRoundNum()).thenReturn(roundNum);
        return consensusRound;
    }
}
