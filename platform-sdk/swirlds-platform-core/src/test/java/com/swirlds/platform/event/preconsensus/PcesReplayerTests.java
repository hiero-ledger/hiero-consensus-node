// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.event.preconsensus;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.swirlds.base.test.fixtures.time.FakeTime;
import com.swirlds.common.context.PlatformContext;
import com.swirlds.common.test.fixtures.Randotron;
import com.swirlds.common.test.fixtures.platform.TestPlatformContextBuilder;
import com.swirlds.component.framework.wires.output.StandardOutputWire;
import com.swirlds.config.extensions.test.fixtures.TestConfigBuilder;
import com.swirlds.platform.event.preconsensus.PcesReplayer.PcesReplayerInput;
import com.swirlds.platform.state.signed.ReservedSignedState;
import com.swirlds.platform.state.signed.SignedState;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.hiero.consensus.model.event.PlatformEvent;
import org.hiero.consensus.model.test.fixtures.event.TestingEventBuilder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;
import org.mockito.stubbing.Answer;

/**
 * Tests for the {@link PcesReplayer} class
 */
@DisplayName("PcesReplayer Tests")
class PcesReplayerTests {
    private FakeTime time;
    private StandardOutputWire<PlatformEvent> eventOutputWire;
    private AtomicInteger eventOutputCount;
    private AtomicBoolean flushIntakeCalled;
    private Runnable flushIntake;
    private AtomicBoolean flushTransactionHandlingCalled;
    private Runnable flushTransactionHandling;
    private Supplier<ReservedSignedState> latestImmutableStateSupplier;
    private final int eventCount = 100;

    @TempDir
    private Path tmpDir;

    @BeforeEach
    void setUp() throws IOException {
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
        final var writer = new PcesFileManager(
                TestPlatformContextBuilder.create().withTime(time).build(), 0, tmpDir);
        writer.beginStreamingNewEvents();

        for (int i = 0; i < eventCount; i++) {
            final PlatformEvent event = new TestingEventBuilder(Randotron.create())
                    .setAppTransactionCount(0)
                    .setSystemTransactionCount(0)
                    .build();
            writer.prepareOutputStream(event);
            writer.writeEvent(event);
            events.add(event);
        }
        writer.closeCurrentMutableFile();
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
                tmpDir,
                platformContext,
                eventOutputWire,
                flushIntake,
                flushTransactionHandling,
                latestImmutableStateSupplier,
                () -> true);

        replayer.replayPces(new PcesReplayerInput(0, 0));

        assertEquals(eventCount, eventOutputCount.get());
        assertTrue(flushIntakeCalled.get());
        assertTrue(flushTransactionHandlingCalled.get());
    }

    @Test
    @DisplayName("Test rate limited operation")
    void testRateLimitedOperation() throws InterruptedException {
        final TestConfigBuilder configBuilder = new TestConfigBuilder()
                .withValue(PcesConfig_.LIMIT_REPLAY_FREQUENCY, true)
                .withValue(PcesConfig_.MAX_EVENT_REPLAY_FREQUENCY, 1);

        final PlatformContext platformContext = TestPlatformContextBuilder.create()
                .withTime(time)
                .withConfiguration(configBuilder.getOrCreateConfig())
                .build();

        final PcesReplayer replayer = new PcesReplayer(
                tmpDir,
                platformContext,
                eventOutputWire,
                flushIntake,
                flushTransactionHandling,
                latestImmutableStateSupplier,
                () -> true);

        Mockito.doAnswer((Answer<PlatformEvent>) invocation -> {
                    eventOutputCount.incrementAndGet();
                    time.tick(Duration.ofSeconds(1));
                    return invocation.getArgument(0);
                })
                .when(eventOutputWire)
                .forward(any());

        replayer.replayPces(new PcesReplayerInput(0, 0));

        Thread.sleep(Duration.ofSeconds(1));
        assertEquals(eventCount, eventOutputCount.get());
        assertTrue(flushIntakeCalled.get());
        assertTrue(flushTransactionHandlingCalled.get());
    }
}
