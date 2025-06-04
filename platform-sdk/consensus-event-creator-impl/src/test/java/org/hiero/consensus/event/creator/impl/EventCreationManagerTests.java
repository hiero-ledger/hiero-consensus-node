// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.event.creator.impl;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.swirlds.base.test.fixtures.time.FakeTime;
import com.swirlds.common.context.PlatformContext;
import com.swirlds.common.test.fixtures.platform.TestPlatformContextBuilder;
import com.swirlds.config.extensions.test.fixtures.TestConfigBuilder;
import java.time.Duration;
import java.util.List;
import org.hiero.consensus.event.creator.impl.pool.TransactionPoolNexus;
import org.hiero.consensus.model.event.PlatformEvent;
import org.hiero.consensus.model.status.PlatformStatus;
import org.hiero.consensus.model.test.fixtures.hashgraph.EventWindowBuilder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class EventCreationManagerTests {
    private EventCreator creator;
    private List<PlatformEvent> eventsToCreate;
    private FakeTime time;
    private EventCreationManager manager;

    @BeforeEach
    void setUp() {
        creator = mock(EventCreator.class);
        eventsToCreate = List.of(mock(PlatformEvent.class), mock(PlatformEvent.class), mock(PlatformEvent.class));
        when(creator.maybeCreateEvent())
                .thenReturn(eventsToCreate.get(0), eventsToCreate.get(1), eventsToCreate.get(2));

        time = new FakeTime();
        final PlatformContext platformContext = TestPlatformContextBuilder.create()
                .withConfiguration(new TestConfigBuilder()
                        .withValue("event.creation.eventIntakeThrottle", 10)
                        .withValue("event.creation.eventCreationRate", 1)
                        .getOrCreateConfig())
                .withTime(time)
                .build();

        manager = new DefaultEventCreationManager(platformContext, mock(TransactionPoolNexus.class), creator);

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

    private PlatformEvent eventWithBirthRound(final long birthRound) {
        final PlatformEvent mockEvent = mock(PlatformEvent.class);
        when(mockEvent.getBirthRound()).thenReturn(birthRound);
        return mockEvent;
    }
}
