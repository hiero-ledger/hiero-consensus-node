// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.quiescence;

import static org.hiero.consensus.model.quiescence.QuiescenceCommand.BREAK_QUIESCENCE;
import static org.hiero.consensus.model.quiescence.QuiescenceCommand.DONT_QUIESCE;
import static org.hiero.consensus.model.quiescence.QuiescenceCommand.QUIESCE;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.swirlds.metrics.api.Counter;
import com.swirlds.metrics.api.Metric;
import com.swirlds.metrics.api.Metrics;
import com.swirlds.state.State;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import org.hiero.consensus.metrics.noop.NoOpMetrics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
@SuppressWarnings("unchecked")
class QuiescedHeartbeatTest {

    @Mock
    private QuiescenceCommands quiescenceCommands;

    @Mock
    private QuiescenceController controller;

    @Mock
    private ScheduledExecutorService scheduler;

    @SuppressWarnings("rawtypes")
    @Mock
    private ScheduledFuture scheduledFuture;

    @Mock
    private TctProbe probe;

    private QuiescedHeartbeat subject;

    @BeforeEach
    void setUp() {
        subject = new QuiescedHeartbeat(quiescenceCommands, controller, scheduler, new NoOpMetrics());
    }

    @Test
    void publicConstructorCreatesInstanceSuccessfully() {
        // When/Then
        assertDoesNotThrow(() -> new QuiescedHeartbeat(controller, quiescenceCommands, new NoOpMetrics()));
    }

    @Test
    void startSchedulesHeartbeatAtFixedRate() {
        // Given
        final var interval = Duration.ofSeconds(5);
        given(scheduler.scheduleAtFixedRate(any(Runnable.class), anyLong(), anyLong(), any(TimeUnit.class)))
                .willReturn(scheduledFuture);

        // When
        subject.start(interval, probe);

        // Then
        verify(scheduler).scheduleAtFixedRate(any(Runnable.class), eq(0L), eq(5000L), eq(TimeUnit.MILLISECONDS));
    }

    @Test
    void startCancelsExistingHeartbeatBeforeSchedulingNew() {
        // Given
        final var interval = Duration.ofSeconds(3);
        given(scheduler.scheduleAtFixedRate(any(Runnable.class), anyLong(), anyLong(), any(TimeUnit.class)))
                .willReturn(scheduledFuture);

        // Start first heartbeat
        subject.start(interval, probe);

        // When - start second heartbeat
        subject.start(interval, probe);

        // Then - should have cancelled the first one
        verify(scheduledFuture).cancel(false);
        verify(scheduler, times(2)).scheduleAtFixedRate(any(Runnable.class), anyLong(), anyLong(), any(TimeUnit.class));
    }

    @Test
    void stopCancelsHeartbeatWhenRunning() {
        // Given
        final var interval = Duration.ofSeconds(2);
        given(scheduler.scheduleAtFixedRate(any(Runnable.class), anyLong(), anyLong(), any(TimeUnit.class)))
                .willReturn(scheduledFuture);
        subject.start(interval, probe);

        // When
        subject.stop();

        // Then
        verify(scheduledFuture).cancel(false);
    }

    @Test
    void stopDoesNothingWhenNoHeartbeatRunning() {
        // When/Then - should not throw
        assertDoesNotThrow(() -> subject.stop());
    }

    @Test
    void stopCanBeCalledMultipleTimes() {
        // Given
        final var interval = Duration.ofSeconds(2);
        given(scheduler.scheduleAtFixedRate(any(Runnable.class), anyLong(), anyLong(), any(TimeUnit.class)))
                .willReturn(scheduledFuture);
        subject.start(interval, probe);

        // When
        subject.stop();
        subject.stop();

        // Then - cancel should only be called once
        verify(scheduledFuture, times(1)).cancel(false);
    }

    @Test
    void shutdownStopsHeartbeatAndShutsDownScheduler() {
        // Given
        final var interval = Duration.ofSeconds(2);
        given(scheduler.scheduleAtFixedRate(any(Runnable.class), anyLong(), anyLong(), any(TimeUnit.class)))
                .willReturn(scheduledFuture);
        subject.start(interval, probe);

        // When
        subject.shutdown();

        // Then
        verify(scheduledFuture).cancel(false);
        verify(scheduler).shutdown();
    }

    @Test
    void shutdownShutsDownSchedulerEvenWhenNoHeartbeatRunning() {
        // When
        subject.shutdown();

        // Then
        verify(scheduler).shutdown();
    }

    @Test
    void heartbeatProbesForTctAndSetsItOnController() {
        // Given
        final var tct = Instant.ofEpochSecond(1_000_000L);
        given(probe.findTct()).willReturn(tct);
        given(controller.getQuiescenceStatus()).willReturn(QUIESCE);
        given(scheduler.scheduleAtFixedRate(any(Runnable.class), anyLong(), anyLong(), any(TimeUnit.class)))
                .willReturn(scheduledFuture);

        // When
        subject.start(Duration.ofSeconds(1), probe);

        // Capture and execute the heartbeat runnable
        final var runnableCaptor = ArgumentCaptor.forClass(Runnable.class);
        verify(scheduler).scheduleAtFixedRate(runnableCaptor.capture(), anyLong(), anyLong(), any(TimeUnit.class));
        runnableCaptor.getValue().run();

        // Then
        verify(probe).findTct();
        verify(controller).setNextTargetConsensusTime(tct);
        verify(controller).getQuiescenceStatus();
        verifyNoInteractions(quiescenceCommands);
    }

    @Test
    void heartbeatDoesNotSetTctWhenProbeReturnsNull() {
        // Given
        given(probe.findTct()).willReturn(null);
        given(controller.getQuiescenceStatus()).willReturn(QUIESCE);
        given(scheduler.scheduleAtFixedRate(any(Runnable.class), anyLong(), anyLong(), any(TimeUnit.class)))
                .willReturn(scheduledFuture);

        // When
        subject.start(Duration.ofSeconds(1), probe);

        // Capture and execute the heartbeat runnable
        final var runnableCaptor = ArgumentCaptor.forClass(Runnable.class);
        verify(scheduler).scheduleAtFixedRate(runnableCaptor.capture(), anyLong(), anyLong(), any(TimeUnit.class));
        runnableCaptor.getValue().run();

        // Then
        verify(probe).findTct();
        verify(controller, never()).setNextTargetConsensusTime(any());
        verify(controller).getQuiescenceStatus();
        verifyNoInteractions(quiescenceCommands);
    }

    @Test
    void heartbeatStopsWhenQuiescenceStatusChangesToDontQuiesce() {
        // Given
        final var tct = Instant.ofEpochSecond(1_000_000L);
        given(probe.findTct()).willReturn(tct);
        given(controller.getQuiescenceStatus()).willReturn(DONT_QUIESCE);
        given(scheduler.scheduleAtFixedRate(any(Runnable.class), anyLong(), anyLong(), any(TimeUnit.class)))
                .willReturn(scheduledFuture);

        // When
        subject.start(Duration.ofSeconds(1), probe);

        // Capture and execute the heartbeat runnable
        final var runnableCaptor = ArgumentCaptor.forClass(Runnable.class);
        verify(scheduler).scheduleAtFixedRate(runnableCaptor.capture(), anyLong(), anyLong(), any(TimeUnit.class));
        runnableCaptor.getValue().run();

        // Then
        verify(probe).findTct();
        verify(controller).setNextTargetConsensusTime(tct);
        verify(controller).getQuiescenceStatus();
        verify(quiescenceCommands).update(DONT_QUIESCE);
        verify(scheduledFuture).cancel(false);
    }

    @Test
    void heartbeatStopsWhenQuiescenceStatusChangesToBreakQuiescence() {
        // Given
        final var tct = Instant.ofEpochSecond(1_000_000L);
        given(probe.findTct()).willReturn(tct);
        given(controller.getQuiescenceStatus()).willReturn(BREAK_QUIESCENCE);
        given(scheduler.scheduleAtFixedRate(any(Runnable.class), anyLong(), anyLong(), any(TimeUnit.class)))
                .willReturn(scheduledFuture);

        // When
        subject.start(Duration.ofSeconds(1), probe);

        // Capture and execute the heartbeat runnable
        final var runnableCaptor = ArgumentCaptor.forClass(Runnable.class);
        verify(scheduler).scheduleAtFixedRate(runnableCaptor.capture(), anyLong(), anyLong(), any(TimeUnit.class));
        runnableCaptor.getValue().run();

        // Then
        verify(probe).findTct();
        verify(controller).setNextTargetConsensusTime(tct);
        verify(controller).getQuiescenceStatus();
        verify(quiescenceCommands).update(BREAK_QUIESCENCE);
        verify(scheduledFuture).cancel(false);
    }

    @Test
    void heartbeatContinuesWhenQuiescenceStatusRemainsQuiesce() {
        // Given
        final var tct = Instant.ofEpochSecond(1_000_000L);
        given(probe.findTct()).willReturn(tct);
        given(controller.getQuiescenceStatus()).willReturn(QUIESCE);
        given(scheduler.scheduleAtFixedRate(any(Runnable.class), anyLong(), anyLong(), any(TimeUnit.class)))
                .willReturn(scheduledFuture);

        // When
        subject.start(Duration.ofSeconds(1), probe);

        // Capture and execute the heartbeat runnable
        final var runnableCaptor = ArgumentCaptor.forClass(Runnable.class);
        verify(scheduler).scheduleAtFixedRate(runnableCaptor.capture(), anyLong(), anyLong(), any(TimeUnit.class));
        runnableCaptor.getValue().run();

        // Then
        verify(probe).findTct();
        verify(controller).setNextTargetConsensusTime(tct);
        verify(controller).getQuiescenceStatus();
        verifyNoInteractions(quiescenceCommands);
        verify(scheduledFuture, never()).cancel(false);
    }

    @Test
    void heartbeatSendsCommandToPlatformBeforeStopping() {
        // Given
        given(probe.findTct()).willReturn(null);
        given(controller.getQuiescenceStatus()).willReturn(DONT_QUIESCE);
        given(scheduler.scheduleAtFixedRate(any(Runnable.class), anyLong(), anyLong(), any(TimeUnit.class)))
                .willReturn(scheduledFuture);

        // When
        subject.start(Duration.ofSeconds(1), probe);

        // Capture and execute the heartbeat runnable
        final var runnableCaptor = ArgumentCaptor.forClass(Runnable.class);
        verify(scheduler).scheduleAtFixedRate(runnableCaptor.capture(), anyLong(), anyLong(), any(TimeUnit.class));
        runnableCaptor.getValue().run();

        // Then - verify order: command dispatch before stop
        final var inOrder = org.mockito.Mockito.inOrder(quiescenceCommands, scheduledFuture);
        inOrder.verify(quiescenceCommands).update(DONT_QUIESCE);
        inOrder.verify(scheduledFuture).cancel(false);
    }

    @Test
    void heartbeatHandlesExceptionFromProbeByEndingQuiescenceAndStopping() {
        // Given
        final var exception = new RuntimeException("Probe failed");
        given(probe.findTct()).willThrow(exception);
        given(scheduler.scheduleAtFixedRate(any(Runnable.class), anyLong(), anyLong(), any(TimeUnit.class)))
                .willReturn(scheduledFuture);

        // When
        subject.start(Duration.ofSeconds(1), probe);

        // Capture and execute the heartbeat runnable
        final var runnableCaptor = ArgumentCaptor.forClass(Runnable.class);
        verify(scheduler).scheduleAtFixedRate(runnableCaptor.capture(), anyLong(), anyLong(), any(TimeUnit.class));

        // Don't propagate the exception to the scheduler
        assertDoesNotThrow(() -> runnableCaptor.getValue().run());

        // Verify that quiescence was ended and heartbeat stopped
        verify(quiescenceCommands).update(DONT_QUIESCE);
        verify(scheduledFuture).cancel(false);
        verify(controller, never()).setNextTargetConsensusTime(any());
        verify(controller, never()).getQuiescenceStatus();
    }

    @Test
    void heartbeatHandlesExceptionFromControllerSetTctByEndingQuiescenceAndStopping() {
        // Given
        final var tct = Instant.ofEpochSecond(1_000_000L);
        final var exception = new RuntimeException("Controller failed");
        given(probe.findTct()).willReturn(tct);
        doThrow(exception).when(controller).setNextTargetConsensusTime(tct);
        given(scheduler.scheduleAtFixedRate(any(Runnable.class), anyLong(), anyLong(), any(TimeUnit.class)))
                .willReturn(scheduledFuture);

        // When
        subject.start(Duration.ofSeconds(1), probe);

        // Capture and execute the heartbeat runnable
        final var runnableCaptor = ArgumentCaptor.forClass(Runnable.class);
        verify(scheduler).scheduleAtFixedRate(runnableCaptor.capture(), anyLong(), anyLong(), any(TimeUnit.class));

        // Don't propagate the exception to the scheduler
        assertDoesNotThrow(() -> runnableCaptor.getValue().run());

        // Verify that quiescence was ended and heartbeat stopped
        verify(quiescenceCommands).update(DONT_QUIESCE);
        verify(scheduledFuture).cancel(false);
        verify(controller, never()).getQuiescenceStatus();
    }

    @Test
    void heartbeatHandlesExceptionFromControllerGetStatusByEndingQuiescenceAndStopping() {
        // Given
        final var tct = Instant.ofEpochSecond(1_000_000L);
        final var exception = new RuntimeException("Controller failed");
        given(probe.findTct()).willReturn(tct);
        given(controller.getQuiescenceStatus()).willThrow(exception);
        given(scheduler.scheduleAtFixedRate(any(Runnable.class), anyLong(), anyLong(), any(TimeUnit.class)))
                .willReturn(scheduledFuture);

        // When
        subject.start(Duration.ofSeconds(1), probe);

        // Capture and execute the heartbeat runnable
        final var runnableCaptor = ArgumentCaptor.forClass(Runnable.class);
        verify(scheduler).scheduleAtFixedRate(runnableCaptor.capture(), anyLong(), anyLong(), any(TimeUnit.class));

        // Don't propagate the exception to the scheduler
        assertDoesNotThrow(() -> runnableCaptor.getValue().run());

        // Verify that quiescence was ended and heartbeat stopped
        verify(quiescenceCommands).update(DONT_QUIESCE);
        verify(scheduledFuture).cancel(false);
    }

    @Test
    void heartbeatHandlesExceptionFromPlatformQuiescenceCommandByEndingQuiescenceAndStopping() {
        // Given
        final var exception = new RuntimeException("Platform failed");
        given(probe.findTct()).willReturn(null);
        given(controller.getQuiescenceStatus()).willReturn(DONT_QUIESCE);
        // First call throws, second call in catch block returns false (no transition because already DONT_QUIESCE)
        doThrow(exception).doReturn(false).when(quiescenceCommands).update(DONT_QUIESCE);
        given(scheduler.scheduleAtFixedRate(any(Runnable.class), anyLong(), anyLong(), any(TimeUnit.class)))
                .willReturn(scheduledFuture);

        // When
        subject.start(Duration.ofSeconds(1), probe);

        // Capture and execute the heartbeat runnable
        final var runnableCaptor = ArgumentCaptor.forClass(Runnable.class);
        verify(scheduler).scheduleAtFixedRate(runnableCaptor.capture(), anyLong(), anyLong(), any(TimeUnit.class));

        // Don't propagate the exception to the scheduler
        assertDoesNotThrow(() -> runnableCaptor.getValue().run());

        // Verify that quiescenceCommands.update was called twice (once in try, once in catch)
        // and heartbeat was stopped
        verify(quiescenceCommands, times(2)).update(DONT_QUIESCE);
        verify(scheduledFuture).cancel(false);
    }

    @Test
    void multipleHeartbeatExecutionsWorkCorrectly() {
        // Given
        final var tct1 = Instant.ofEpochSecond(1_000_000L);
        final var tct2 = Instant.ofEpochSecond(2_000_000L);
        given(probe.findTct()).willReturn(tct1, tct2);
        given(controller.getQuiescenceStatus()).willReturn(QUIESCE, QUIESCE);
        given(scheduler.scheduleAtFixedRate(any(Runnable.class), anyLong(), anyLong(), any(TimeUnit.class)))
                .willReturn(scheduledFuture);

        // When
        subject.start(Duration.ofSeconds(1), probe);

        // Capture and execute the heartbeat runnable twice
        final var runnableCaptor = ArgumentCaptor.forClass(Runnable.class);
        verify(scheduler).scheduleAtFixedRate(runnableCaptor.capture(), anyLong(), anyLong(), any(TimeUnit.class));
        runnableCaptor.getValue().run();
        runnableCaptor.getValue().run();

        // Then
        verify(probe, times(2)).findTct();
        verify(controller).setNextTargetConsensusTime(tct1);
        verify(controller).setNextTargetConsensusTime(tct2);
        verify(controller, times(2)).getQuiescenceStatus();
        verifyNoInteractions(quiescenceCommands);
        verify(scheduledFuture, never()).cancel(false);
    }

    @Test
    void heartbeatWithDifferentIntervals() {
        // Given
        final var shortInterval = Duration.ofMillis(100);
        final var longInterval = Duration.ofSeconds(60);
        given(scheduler.scheduleAtFixedRate(any(Runnable.class), anyLong(), anyLong(), any(TimeUnit.class)))
                .willReturn(scheduledFuture);

        // When - start with short interval
        subject.start(shortInterval, probe);

        // Then
        verify(scheduler).scheduleAtFixedRate(any(Runnable.class), eq(0L), eq(100L), eq(TimeUnit.MILLISECONDS));

        // When - start with long interval
        subject.start(longInterval, probe);

        // Then
        verify(scheduler).scheduleAtFixedRate(any(Runnable.class), eq(0L), eq(60000L), eq(TimeUnit.MILLISECONDS));
    }

    @Test
    void startWithZeroDurationInterval() {
        // Given
        final var zeroDuration = Duration.ZERO;
        given(scheduler.scheduleAtFixedRate(any(Runnable.class), anyLong(), anyLong(), any(TimeUnit.class)))
                .willReturn(scheduledFuture);

        // When
        subject.start(zeroDuration, probe);

        // Then
        verify(scheduler).scheduleAtFixedRate(any(Runnable.class), eq(0L), eq(0L), eq(TimeUnit.MILLISECONDS));
    }

    /**
     * After an exception-driven stop, a subsequent {@code start()} call must schedule a fresh
     * runnable. The exception handler in {@code heartbeat(probe)} re-throws after stopping, and the outer
     * {@code start()} lambda swallows the throw — but if any state inside the heartbeat is left dirty, the
     * next start may fail or skip-tick. This test drives an exception-recovery cycle and then a clean start,
     * asserting the second start reaches the scheduler with a fresh runnable.
     *
     * <p>Note on the cancel-count: the first tick's exception path calls {@code stop()}, which cancels
     * the future and nulls the field. The second {@code start()}'s prologue calls {@code stop()} again,
     * but it's a no-op because the field is already null. So {@code cancel(false)} is invoked exactly
     * once across the recovery cycle, not twice.
     */
    @Test
    void heartbeatRecoversCleanlyAfterExceptionAndCanBeRestarted() {
        // Given - first start triggers an exception on tick
        given(scheduler.scheduleAtFixedRate(any(Runnable.class), anyLong(), anyLong(), any(TimeUnit.class)))
                .willReturn(scheduledFuture);
        given(probe.findTct()).willThrow(new RuntimeException("Probe failed"));
        subject.start(Duration.ofSeconds(1), probe);

        // Capture and execute the first runnable, causing the exception
        final var firstRunnableCaptor = ArgumentCaptor.forClass(Runnable.class);
        verify(scheduler).scheduleAtFixedRate(firstRunnableCaptor.capture(), anyLong(), anyLong(), any(TimeUnit.class));
        assertDoesNotThrow(() -> firstRunnableCaptor.getValue().run());

        // When - a subsequent start() is called with a fresh probe (we don't drive its runnable; we only
        // need to prove that start() successfully reaches the scheduler again after the exception).
        final var freshProbe = org.mockito.Mockito.mock(TctProbe.class);
        subject.start(Duration.ofSeconds(1), freshProbe);

        // Then - the scheduler is asked to schedule a new runnable (twice now: original + fresh)
        verify(scheduler, times(2)).scheduleAtFixedRate(any(Runnable.class), anyLong(), anyLong(), any(TimeUnit.class));
        // The first tick's exception path cancelled the original future. The second start()'s stop() is a
        // no-op because heartbeatFuture has been nulled by the exception path — so the cancel count is 1.
        verify(scheduledFuture, times(1)).cancel(false);
    }

    /**
     * When {@code probe.findTct()} throws on a heartbeat tick, the catch path calls
     * {@code quiescenceCommands.update(DONT_QUIESCE)}. Two important contract properties must hold:
     * <ol>
     *   <li>The exception is recorded in the {@code quiescence.heartbeatErrors} counter (or, equivalently,
     *       the call goes through the metric-incrementing code path before the throw is re-raised).</li>
     *   <li>Subsequent invocations of the captured runnable — which the scheduler may still call before
     *       the cancellation takes effect, since the cancel is non-interrupting — do not blow up; they
     *       observe a stopped heartbeat and act as no-ops or re-trigger the same handling.</li>
     * </ol>
     * Demonstrating (2) requires running the captured runnable a second time after the first one threw.
     */
    @Test
    void heartbeatTickRunsCleanlyAfterPriorExceptionTick() {
        given(scheduler.scheduleAtFixedRate(any(Runnable.class), anyLong(), anyLong(), any(TimeUnit.class)))
                .willReturn(scheduledFuture);
        // Throw on the first call to findTct, return null on the second.
        given(probe.findTct()).willThrow(new RuntimeException("Probe failed")).willReturn(null);
        // After the first tick stops the heartbeat, future invocations of the same captured runnable will
        // still go through heartbeat(probe) — the cancel is on the scheduler's pending tasks, not the
        // currently-captured Runnable object. The second call therefore proceeds to controller.getQuiescenceStatus()
        // (which we stub to return DONT_QUIESCE so the heartbeat re-stops cleanly).
        given(controller.getQuiescenceStatus()).willReturn(DONT_QUIESCE);

        subject.start(Duration.ofSeconds(1), probe);
        final var captor = ArgumentCaptor.forClass(Runnable.class);
        verify(scheduler).scheduleAtFixedRate(captor.capture(), anyLong(), anyLong(), any(TimeUnit.class));

        // First tick — throws; recovery path runs
        assertDoesNotThrow(() -> captor.getValue().run());
        // Second tick — must not re-throw; must not leave anything in a partially-initialized state
        assertDoesNotThrow(() -> captor.getValue().run());
    }

    /**
     * When a heartbeat tick throws, the {@code quiescence.heartbeatErrors} counter must increment so
     * operators have visibility into unhandled exceptions inside the tick. The other exception-path tests
     * use {@link NoOpMetrics} (whose counter is a no-op) and only verify the {@code update(DONT_QUIESCE)}
     * side effect; this test wires a mock {@link Counter} through a mock {@link Metrics} so the
     * counter-increment can be asserted directly.
     */
    @Test
    void heartbeatErrorsCounterIsIncrementedOnTickException() {
        final var counterMock = mock(Counter.class);
        final var metricsMock = mock(Metrics.class);
        when(metricsMock.getOrCreate(any())).thenReturn((Metric) counterMock);
        final var subjectWithMockMetrics =
                new QuiescedHeartbeat(quiescenceCommands, controller, scheduler, metricsMock);

        given(probe.findTct()).willThrow(new RuntimeException("Probe failed"));
        given(scheduler.scheduleAtFixedRate(any(Runnable.class), anyLong(), anyLong(), any(TimeUnit.class)))
                .willReturn(scheduledFuture);

        subjectWithMockMetrics.start(Duration.ofSeconds(1), probe);

        final var captor = ArgumentCaptor.forClass(Runnable.class);
        verify(scheduler).scheduleAtFixedRate(captor.capture(), anyLong(), anyLong(), any(TimeUnit.class));
        assertDoesNotThrow(() -> captor.getValue().run());

        verify(counterMock).increment();
        verify(quiescenceCommands).update(DONT_QUIESCE);
    }

    @Test
    void pollAndMaybeStartStartsHeartbeatWhenTransitioningIntoQuiesce() {
        given(controller.getQuiescenceStatus()).willReturn(QUIESCE);
        given(quiescenceCommands.update(QUIESCE)).willReturn(true);
        given(scheduler.scheduleAtFixedRate(any(Runnable.class), anyLong(), anyLong(), any(TimeUnit.class)))
                .willReturn(scheduledFuture);

        subject.pollAndMaybeStart(Duration.ofSeconds(1), 10, 1440L, mock(State.class));

        verify(quiescenceCommands).update(QUIESCE);
        verify(scheduler).scheduleAtFixedRate(any(Runnable.class), eq(0L), eq(1000L), eq(TimeUnit.MILLISECONDS));
    }

    @Test
    void pollAndMaybeStartDoesNotStartWhenUpdateReportsNoTransition() {
        given(controller.getQuiescenceStatus()).willReturn(QUIESCE);
        // update() returns false: the command was already QUIESCE, so this is not a real transition.
        given(quiescenceCommands.update(QUIESCE)).willReturn(false);

        subject.pollAndMaybeStart(Duration.ofSeconds(1), 10, 1440L, mock(State.class));

        verify(quiescenceCommands).update(QUIESCE);
        verifyNoInteractions(scheduler);
    }

    @Test
    void pollAndMaybeStartDoesNotStartWhenCommandIsNotQuiesce() {
        given(controller.getQuiescenceStatus()).willReturn(DONT_QUIESCE);
        // A real transition, but to DONT_QUIESCE — the heartbeat must not (re)start on a wake-up.
        given(quiescenceCommands.update(DONT_QUIESCE)).willReturn(true);

        subject.pollAndMaybeStart(Duration.ofSeconds(1), 10, 1440L, mock(State.class));

        verify(quiescenceCommands).update(DONT_QUIESCE);
        verifyNoInteractions(scheduler);
    }

    @Test
    void pollAndMaybeStartStartsHeartbeatOnlyOnTheRealQuiesceTransition() {
        given(controller.getQuiescenceStatus()).willReturn(QUIESCE);
        // First poll observes a real transition into QUIESCE; the second sees the command unchanged.
        given(quiescenceCommands.update(QUIESCE)).willReturn(true).willReturn(false);
        given(scheduler.scheduleAtFixedRate(any(Runnable.class), anyLong(), anyLong(), any(TimeUnit.class)))
                .willReturn(scheduledFuture);
        final var state = mock(State.class);

        subject.pollAndMaybeStart(Duration.ofSeconds(1), 10, 1440L, state);
        subject.pollAndMaybeStart(Duration.ofSeconds(1), 10, 1440L, state);

        verify(quiescenceCommands, times(2)).update(QUIESCE);
        verify(scheduler, times(1)).scheduleAtFixedRate(any(Runnable.class), anyLong(), anyLong(), any(TimeUnit.class));
    }
}
