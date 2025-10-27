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
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.swirlds.platform.system.Platform;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
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
    private Platform platform;

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
        subject = new QuiescedHeartbeat(platform, controller, scheduler);
    }

    @Test
    void publicConstructorCreatesInstanceSuccessfully() {
        // When/Then
        assertDoesNotThrow(() -> new QuiescedHeartbeat(controller, platform));
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
        verifyNoInteractions(platform);
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
        verifyNoInteractions(platform);
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
        verify(platform).quiescenceCommand(DONT_QUIESCE);
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
        verify(platform).quiescenceCommand(BREAK_QUIESCENCE);
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
        verifyNoInteractions(platform);
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

        // Then - verify order: platform command before stop
        final var inOrder = org.mockito.Mockito.inOrder(platform, scheduledFuture);
        inOrder.verify(platform).quiescenceCommand(DONT_QUIESCE);
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
        verify(platform).quiescenceCommand(DONT_QUIESCE);
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
        verify(platform).quiescenceCommand(DONT_QUIESCE);
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
        verify(platform).quiescenceCommand(DONT_QUIESCE);
        verify(scheduledFuture).cancel(false);
    }

    @Test
    void heartbeatHandlesExceptionFromPlatformQuiescenceCommandByEndingQuiescenceAndStopping() {
        // Given
        final var exception = new RuntimeException("Platform failed");
        given(probe.findTct()).willReturn(null);
        given(controller.getQuiescenceStatus()).willReturn(DONT_QUIESCE);
        // First call throws, second call in catch block succeeds
        doThrow(exception).doNothing().when(platform).quiescenceCommand(DONT_QUIESCE);
        given(scheduler.scheduleAtFixedRate(any(Runnable.class), anyLong(), anyLong(), any(TimeUnit.class)))
                .willReturn(scheduledFuture);

        // When
        subject.start(Duration.ofSeconds(1), probe);

        // Capture and execute the heartbeat runnable
        final var runnableCaptor = ArgumentCaptor.forClass(Runnable.class);
        verify(scheduler).scheduleAtFixedRate(runnableCaptor.capture(), anyLong(), anyLong(), any(TimeUnit.class));

        // Don't propagate the exception to the scheduler
        assertDoesNotThrow(() -> runnableCaptor.getValue().run());

        // Verify that platform.quiescenceCommand was called twice (once in try, once in catch)
        // and heartbeat was stopped
        verify(platform, times(2)).quiescenceCommand(DONT_QUIESCE);
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
        verifyNoInteractions(platform);
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
}
