// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.event.preconsensus;

import static com.swirlds.common.test.fixtures.AssertionUtils.assertEventuallyEquals;
import static com.swirlds.common.test.fixtures.AssertionUtils.assertEventuallyTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.swirlds.base.test.fixtures.time.FakeTime;
import com.swirlds.common.context.PlatformContext;
import com.swirlds.common.io.IOIterator;
import com.swirlds.common.test.fixtures.Randotron;
import com.swirlds.common.test.fixtures.platform.TestPlatformContextBuilder;
import com.swirlds.component.framework.wires.output.StandardOutputWire;
import com.swirlds.config.extensions.test.fixtures.TestConfigBuilder;
import com.swirlds.platform.state.signed.ReservedSignedState;
import com.swirlds.platform.state.signed.SignedState;
import com.swirlds.platform.test.fixtures.event.TestingEventBuilder;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.hiero.consensus.model.event.PlatformEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tests for the {@link PcesReplayer} class
 */
@DisplayName("PcesReplayer Tests")
class PcesReplayerTests {
    private PlatformContext noRateLimitContext;
    private PlatformContext rateLimitedContext;
    private FakeTime time;
    private StandardOutputWire<PlatformEvent> eventOutputWire;
    private AtomicInteger eventOutputCount;
    private AtomicBoolean flushIntakeCalled;
    private Runnable flushIntake;
    private AtomicBoolean flushTransactionHandlingCalled;
    private Runnable flushTransactionHandling;
    private Supplier<ReservedSignedState> latestImmutableStateSupplier;
    private IOIterator<PlatformEvent> ioIterator;

    private final int eventCount = 100;

    @BeforeEach
    void setUp() {
        time = new FakeTime();

        eventOutputWire = mock(StandardOutputWire.class);
        eventOutputCount = new AtomicInteger(0);

        // whenever an event is forwarded to the output wire, increment the count
        doAnswer(invocation -> {
                    eventOutputCount.incrementAndGet();
                    return null;
                })
                .when(eventOutputWire)
                .forward(any());

        flushIntakeCalled = new AtomicBoolean(false);
        flushIntake = () -> flushIntakeCalled.set(true);

        flushTransactionHandlingCalled = new AtomicBoolean(false);
        flushTransactionHandling = () -> flushTransactionHandlingCalled.set(true);

        final ReservedSignedState latestImmutableState = mock(ReservedSignedState.class);
        final SignedState signedState = mock(SignedState.class);
        when(latestImmutableState.get()).thenReturn(signedState);

        latestImmutableStateSupplier = () -> latestImmutableState;

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
        final TestConfigBuilder configBuilder =
                new TestConfigBuilder().withValue(PcesConfig_.LIMIT_REPLAY_FREQUENCY, false);

        final PlatformContext platformContext = TestPlatformContextBuilder.create()
                .withTime(time)
                .withConfiguration(configBuilder.getOrCreateConfig())
                .build();

        final PcesReplayer replayer = new PcesReplayer(
                platformContext,
                eventOutputWire,
                flushIntake,
                flushTransactionHandling,
                latestImmutableStateSupplier,
                () -> true);

        replayer.replayPces(ioIterator);

        assertEquals(eventCount, eventOutputCount.get());
        assertTrue(flushIntakeCalled.get());
        assertTrue(flushTransactionHandlingCalled.get());
    }

    @Test
    @DisplayName("Test rate limited operation")
    void testRateLimitedOperation() {
        final TestConfigBuilder configBuilder = new TestConfigBuilder()
                .withValue(PcesConfig_.LIMIT_REPLAY_FREQUENCY, true)
                .withValue(PcesConfig_.MAX_EVENT_REPLAY_FREQUENCY, 10);

        final PlatformContext platformContext = TestPlatformContextBuilder.create()
                .withTime(time)
                .withConfiguration(configBuilder.getOrCreateConfig())
                .build();

        final PcesReplayer replayer = new PcesReplayer(
                platformContext,
                eventOutputWire,
                flushIntake,
                flushTransactionHandling,
                latestImmutableStateSupplier,
                () -> true);

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

        assertEventuallyTrue(
                () -> flushIntakeCalled.get(), Duration.ofSeconds(1), "Flush intake should have been called");
        assertEventuallyTrue(
                () -> flushTransactionHandlingCalled.get(),
                Duration.ofSeconds(1),
                "Flush transaction handling should have been called");
    }
}
