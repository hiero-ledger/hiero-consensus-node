// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.event.creator.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.hedera.hapi.node.state.roster.Roster;
import com.hedera.hapi.node.state.roster.RosterEntry;
import com.swirlds.base.test.fixtures.time.FakeTime;
import com.swirlds.config.api.Configuration;
import com.swirlds.config.extensions.test.fixtures.TestConfigBuilder;
import com.swirlds.metrics.api.Metrics;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.hiero.consensus.metrics.noop.NoOpMetrics;
import org.hiero.consensus.model.event.PlatformEvent;
import org.hiero.consensus.model.gossip.SyncProgress;
import org.hiero.consensus.model.hashgraph.EventWindow;
import org.hiero.consensus.model.node.NodeId;
import org.hiero.consensus.model.quiescence.QuiescenceCommand;
import org.hiero.consensus.model.status.PlatformStatus;
import org.hiero.consensus.model.test.fixtures.hashgraph.EventWindowBuilder;
import org.hiero.consensus.roster.test.fixtures.RandomRosterEntryBuilder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class EventCreatorTests {
    private EventCreator creator;
    private List<PlatformEvent> eventsToCreate;
    private FakeTime time;
    private DefaultEventCreationManager manager;

    @BeforeEach
    void setUp() {
        creator = mock(EventCreator.class);
        eventsToCreate = List.of(mock(PlatformEvent.class), mock(PlatformEvent.class), mock(PlatformEvent.class));
        when(creator.maybeCreateEvent())
                .thenReturn(eventsToCreate.get(0), eventsToCreate.get(1), eventsToCreate.get(2));

        time = new FakeTime();
        final Configuration configuration = new TestConfigBuilder()
                .withValue("event.creation.eventIntakeThrottle", 10)
                .withValue("event.creation.eventCreationRate", 1)
                .getOrCreateConfig();
        final Metrics metrics = new NoOpMetrics();

        Random random = new Random();
        List<RosterEntry> rosterEntries = new ArrayList<>(5);
        for (int i = 1; i <= 5; i++) {
            rosterEntries.add(RandomRosterEntryBuilder.create(random)
                    .withNodeId(i)
                    .withWeight(10)
                    .build());
        }

        final Roster roster = new Roster(rosterEntries);

        manager = new DefaultEventCreationManager(
                configuration, metrics, time, () -> false, creator, roster, NodeId.of(1));

        manager.updatePlatformStatus(PlatformStatus.ACTIVE);
    }

    @Test
    void basicBehaviorTest() {
        final PlatformEvent e0 = manager.maybeCreateEvent();
        verify(creator, times(1)).maybeCreateEvent();
        assertNotNull(e0);
        assertSame(eventsToCreate.getFirst(), e0);

        time.tick(Duration.ofSeconds(1));

        final PlatformEvent e1 = manager.maybeCreateEvent();
        verify(creator, times(2)).maybeCreateEvent();
        assertNotNull(e1);
        assertSame(eventsToCreate.get(1), e1);

        time.tick(Duration.ofSeconds(1));

        final PlatformEvent e2 = manager.maybeCreateEvent();
        verify(creator, times(3)).maybeCreateEvent();
        assertNotNull(e2);
        assertSame(eventsToCreate.get(2), e2);
    }

    @Test
    void statusPreventsCreation() {
        final PlatformEvent e0 = manager.maybeCreateEvent();
        verify(creator, times(1)).maybeCreateEvent();
        assertNotNull(e0);
        assertSame(eventsToCreate.getFirst(), e0);

        time.tick(Duration.ofSeconds(1));

        manager.updatePlatformStatus(PlatformStatus.BEHIND);
        assertNull(manager.maybeCreateEvent());
        verify(creator, times(1)).maybeCreateEvent();

        time.tick(Duration.ofSeconds(1));

        manager.updatePlatformStatus(PlatformStatus.ACTIVE);
        final PlatformEvent e1 = manager.maybeCreateEvent();
        assertNotNull(e1);
        verify(creator, times(2)).maybeCreateEvent();
        assertSame(eventsToCreate.get(1), e1);
    }

    @Test
    void quiescencePreventsCreation() {
        final PlatformEvent e0 = manager.maybeCreateEvent();
        verify(creator, times(1)).maybeCreateEvent();
        assertNotNull(e0);
        assertSame(eventsToCreate.getFirst(), e0);

        time.tick(Duration.ofSeconds(1));

        manager.quiescenceCommand(QuiescenceCommand.QUIESCE);
        assertNull(manager.maybeCreateEvent());
        verify(creator, times(1)).maybeCreateEvent();

        time.tick(Duration.ofSeconds(1));

        manager.quiescenceCommand(QuiescenceCommand.DONT_QUIESCE);
        final PlatformEvent e1 = manager.maybeCreateEvent();
        assertNotNull(e1);
        verify(creator, times(2)).maybeCreateEvent();
        assertSame(eventsToCreate.get(1), e1);
    }

    @Test
    void breakQuiescenceAllowsCreation() {
        final PlatformEvent e0 = manager.maybeCreateEvent();
        verify(creator, times(1)).maybeCreateEvent();
        assertNotNull(e0);
        assertSame(eventsToCreate.getFirst(), e0);

        time.tick(Duration.ofSeconds(1));

        manager.quiescenceCommand(QuiescenceCommand.QUIESCE);
        assertNull(manager.maybeCreateEvent());
        verify(creator, times(1)).maybeCreateEvent();

        time.tick(Duration.ofSeconds(1));

        manager.quiescenceCommand(QuiescenceCommand.BREAK_QUIESCENCE);
        final PlatformEvent e1 = manager.maybeCreateEvent();
        assertNotNull(e1);
        verify(creator, times(2)).maybeCreateEvent();
        assertSame(eventsToCreate.get(1), e1);
    }

    @Test
    void ratePreventsCreation() {
        final PlatformEvent e0 = manager.maybeCreateEvent();
        verify(creator, times(1)).maybeCreateEvent();
        assertNotNull(e0);
        assertSame(eventsToCreate.getFirst(), e0);

        // no tick

        assertNull(manager.maybeCreateEvent());
        assertNull(manager.maybeCreateEvent());
        verify(creator, times(1)).maybeCreateEvent();

        time.tick(Duration.ofSeconds(1));

        final PlatformEvent e1 = manager.maybeCreateEvent();
        verify(creator, times(2)).maybeCreateEvent();
        assertNotNull(e1);
        assertSame(eventsToCreate.get(1), e1);
    }

    @Test
    void unhealthyNodePreventsCreation() {
        final PlatformEvent e0 = manager.maybeCreateEvent();
        verify(creator, times(1)).maybeCreateEvent();
        assertNotNull(e0);
        assertSame(eventsToCreate.getFirst(), e0);

        time.tick(Duration.ofSeconds(1));

        manager.reportUnhealthyDuration(Duration.ofSeconds(10));

        assertNull(manager.maybeCreateEvent());
        verify(creator, times(1)).maybeCreateEvent();

        time.tick(Duration.ofSeconds(1));

        manager.reportUnhealthyDuration(Duration.ZERO);

        final PlatformEvent e1 = manager.maybeCreateEvent();
        assertNotNull(e1);
        verify(creator, times(2)).maybeCreateEvent();
        assertSame(eventsToCreate.get(1), e1);
    }

    @Test
    void nonFutureEventsAreNotBuffered() {
        manager.setEventWindow(
                EventWindowBuilder.builder().setNewEventBirthRound(2).build());
        final PlatformEvent e2 = eventWithBirthRound(2);
        manager.registerEvent(e2);
        verify(creator, times(1)).registerEvent(e2);

        manager.setEventWindow(
                EventWindowBuilder.builder().setNewEventBirthRound(3).build());
        final PlatformEvent e1 = eventWithBirthRound(1);
        final PlatformEvent e3 = eventWithBirthRound(3);
        manager.registerEvent(e1);
        manager.registerEvent(e3);
        verify(creator, times(1)).registerEvent(e1);
        verify(creator, times(1)).registerEvent(e3);
    }

    @Test
    void futureEventsAreBuffered() {
        manager.setEventWindow(
                EventWindowBuilder.builder().setNewEventBirthRound(2).build());

        final PlatformEvent e3 = eventWithBirthRound(3);
        final PlatformEvent e4 = eventWithBirthRound(4);
        final PlatformEvent e5 = eventWithBirthRound(5);

        // Future events should be buffered
        manager.registerEvent(e3);
        manager.registerEvent(e4);
        manager.registerEvent(e5);
        verify(creator, times(0)).registerEvent(any(PlatformEvent.class));

        manager.setEventWindow(
                EventWindowBuilder.builder().setNewEventBirthRound(3).build());
        verify(creator, times(1)).registerEvent(e3);

        manager.setEventWindow(
                EventWindowBuilder.builder().setNewEventBirthRound(4).build());
        verify(creator, times(1)).registerEvent(e4);

        manager.setEventWindow(
                EventWindowBuilder.builder().setNewEventBirthRound(5).build());
        verify(creator, times(1)).registerEvent(e5);
    }

    @Test
    void ancientEventsAreIgnored() {
        manager.setEventWindow(EventWindowBuilder.builder()
                .setLatestConsensusRound(20)
                .setNewEventBirthRound(21)
                .setAncientThreshold(10)
                .build());
        manager.registerEvent(eventWithBirthRound(9));
        verify(creator, times(0)).registerEvent(any(PlatformEvent.class));
    }

    @Test
    void testClear() {
        manager.setEventWindow(
                EventWindowBuilder.builder().setNewEventBirthRound(2).build());

        final PlatformEvent e3 = eventWithBirthRound(3);
        final PlatformEvent e4 = eventWithBirthRound(4);
        final PlatformEvent e5 = eventWithBirthRound(5);

        // Future events should be buffered
        manager.registerEvent(e3);
        manager.registerEvent(e4);
        manager.registerEvent(e5);

        manager.clear();
        manager.setEventWindow(
                EventWindowBuilder.builder().setNewEventBirthRound(5).build());
        verify(creator, times(0)).registerEvent(any(PlatformEvent.class));
    }

    @Test
    void lagComputeAndBlockEventCreation() {
        var genesisWindow = new EventWindow(100, 101, 1, 1);
        for (int i = 5; i >= 2; i--) {
            var otherNodeId = NodeId.of(i);
            manager.reportSyncProgress(
                    new SyncProgress(otherNodeId, genesisWindow, new EventWindow(120 + i, 120 + i, 100, 95)));
        }
        assertEquals(23.5, manager.getSyncRoundLag());
        assertNull(manager.maybeCreateEvent());
    }

    private PlatformEvent eventWithBirthRound(final long birthRound) {
        final PlatformEvent mockEvent = mock(PlatformEvent.class);
        when(mockEvent.getBirthRound()).thenReturn(birthRound);
        return mockEvent;
    }
}
