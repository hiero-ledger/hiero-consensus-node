// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.pces.impl.replayer;

import static org.hiero.base.utility.test.fixtures.assertions.AssertionUtils.assertEventuallyDoesNotThrow;
import static org.hiero.base.utility.test.fixtures.assertions.AssertionUtils.assertEventuallyEquals;
import static org.hiero.base.utility.test.fixtures.assertions.AssertionUtils.assertEventuallyTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.swirlds.base.test.fixtures.time.FakeTime;
import com.swirlds.component.framework.wires.output.StandardOutputWire;
import com.swirlds.config.api.Configuration;
import com.swirlds.config.extensions.test.fixtures.TestConfigBuilder;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.hiero.consensus.event.creator.EventCreatorModule;
import org.hiero.consensus.event.intake.EventIntakeModule;
import org.hiero.consensus.gossip.GossipModule;
import org.hiero.consensus.hashgraph.HashgraphModule;
import org.hiero.consensus.io.IOIterator;
import org.hiero.consensus.model.event.PlatformEvent;
import org.hiero.consensus.model.test.fixtures.event.TestingEventBuilder;
import org.hiero.consensus.pces.PcesModule;
import org.hiero.consensus.pces.config.PcesConfig_;
import org.hiero.consensus.test.fixtures.Randotron;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tests for the {@link PcesReplayer} class
 */
@DisplayName("PcesReplayer Tests")
class PcesReplayerTests {
    private FakeTime time;
    private StandardOutputWire<PlatformEvent> eventOutputWire;
    private AtomicInteger eventOutputCount;
    private PcesModule pcesModule;
    private Runnable flushGossipModule;
    private AtomicBoolean gossipFlushCalled;
    private EventCreatorModule eventCreatorModule;
    private EventIntakeModule eventIntakeModule;
    private HashgraphModule hashgraphModule;
    private IOIterator<PlatformEvent> ioIterator;

    private final int eventCount = 100;

    @BeforeEach
    void setUp() {
        time = new FakeTime();

        eventOutputWire = mock(StandardOutputWire.class);
        eventOutputCount = new AtomicInteger(0);
        gossipFlushCalled = new AtomicBoolean(false);

        // whenever an event is forwarded to the output wire, increment the count
        doAnswer(invocation -> {
            eventOutputCount.incrementAndGet();
            return null;
        })
                .when(eventOutputWire)
                .forward(any());

        pcesModule = mock(PcesModule.class);
        flushGossipModule = () -> gossipFlushCalled.set(true);
        eventCreatorModule = mock(EventCreatorModule.class);
        eventIntakeModule = mock(EventIntakeModule.class);
        hashgraphModule = mock(HashgraphModule.class);

        final List<PlatformEvent> events = new ArrayList<>();
        for (int i = 0; i < eventCount; i++) {
            final PlatformEvent event = new TestingEventBuilder(Randotron.create())
                    .setAppTransactionCount(0)
                    .setSystemTransactionCount(0)
                    .build();

            events.add(event);
        }

        final Iterator<PlatformEvent> eventIterator = events.iterator();
        ioIterator = new IOIterator<>() {
            @Override
            public boolean hasNext() {
                return eventIterator.hasNext();
            }

            @Override
            public PlatformEvent next() {
                return eventIterator.next();
            }
        };
    }

    @Test
    @DisplayName("Test standard operation")
    void testStandardOperation() {
        final Configuration configuration = new TestConfigBuilder()
                .withValue(PcesConfig_.LIMIT_REPLAY_FREQUENCY, false)
                .getOrCreateConfig();

        final PcesReplayer replayer =
                new PcesReplayer(configuration, time, pcesModule, eventIntakeModule, eventCreatorModule,
                        hashgraphModule, flushGossipModule, eventOutputWire, () -> true);

        replayer.replayPces(ioIterator);

        verify(eventCreatorModule, times(1)).flush();
        verify(pcesModule, times(1)).flush();
        verify(eventIntakeModule, times(1)).flush();
        verify(hashgraphModule, times(1)).flush();
        assertTrue(gossipFlushCalled.get());
        assertEquals(eventCount, eventOutputCount.get());
    }

    @Test
    @DisplayName("Test rate limited operation")
    void testRateLimitedOperation() {
        final Configuration configuration = new TestConfigBuilder()
                .withValue(PcesConfig_.LIMIT_REPLAY_FREQUENCY, true)
                .withValue(PcesConfig_.MAX_EVENT_REPLAY_FREQUENCY, 10)
                .getOrCreateConfig();

        final PcesReplayer replayer =
                new PcesReplayer(configuration, time, pcesModule, eventIntakeModule, eventCreatorModule,
                        hashgraphModule, flushGossipModule, eventOutputWire, () -> true);

        final Thread thread = new Thread(() -> {
            replayer.replayPces(ioIterator);
        });

        thread.start();

        assertEventuallyEquals(
                1, eventOutputCount::get, Duration.ofSeconds(1), "First event should be replayed immediately");

        for (int i = 2; i <= eventCount; i++) {
            time.tick(Duration.ofMillis(100));
            assertEventuallyEquals(
                    i,
                    () -> eventOutputCount.get(),
                    Duration.ofSeconds(1),
                    "Event count should have increased from %s to %s".formatted(i - 1, i));
        }

        assertFlushEventuallyCalled(() -> verify(eventCreatorModule, times(1)).flush(), "Event Creator");
        assertFlushEventuallyCalled(() -> verify(pcesModule, times(1)).flush(), "Pces");
        assertFlushEventuallyCalled(() -> verify(eventIntakeModule, times(1)).flush(), "Event Intake");
        assertFlushEventuallyCalled(() -> verify(hashgraphModule, times(1)).flush(), "Hashgraph");
        assertEventuallyTrue(
                () -> gossipFlushCalled.get(),
                Duration.ofSeconds(1),
                "Flush gossip runnable should have been called");
    }

    private void assertFlushEventuallyCalled(@NonNull final Runnable flushRunnable,
            @NonNull final String runnableName) {
        assertEventuallyDoesNotThrow(
                flushRunnable,
                Duration.ofSeconds(1),
                runnableName + " module flush should have been called"
        );
    }
}
