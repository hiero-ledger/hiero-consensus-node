// SPDX-License-Identifier: Apache-2.0
package com.swirlds.common.metrics.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mock.Strictness.LENIENT;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.swirlds.base.test.fixtures.time.FakeTime;
import com.swirlds.base.time.Time;
import com.swirlds.common.metrics.config.MetricsConfig;
import com.swirlds.config.api.Configuration;
import com.swirlds.config.extensions.test.fixtures.TestConfigBuilder;
import com.swirlds.metrics.api.snapshot.Snapshot;
import com.swirlds.metrics.api.snapshot.SnapshotableMetric;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import org.hiero.consensus.model.node.NodeId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.stubbing.Answer;

@ExtendWith(MockitoExtension.class)
class SnapshotServiceTest {

    private static final NodeId NODE_ID_1 = NodeId.of(1L);
    private static final NodeId NODE_ID_2 = NodeId.of(2L);

    @Mock
    private SnapshotableMetric globalMetric;

    @Mock
    private DefaultPlatformMetrics globalMetrics;

    @Mock(strictness = LENIENT)
    private SnapshotableMetric platform1Metric;

    @Mock(strictness = LENIENT)
    private DefaultPlatformMetrics platform1Metrics;

    @Mock(strictness = LENIENT)
    private SnapshotableMetric platform2Metric;

    @Mock(strictness = LENIENT)
    private DefaultPlatformMetrics platform2Metrics;

    @Mock
    private Consumer<SnapshotEvent> subscriber;

    private SnapshotService service;

    private MetricsConfig metricsConfig;

    @BeforeEach
    void setup(@Mock(strictness = LENIENT) final ScheduledExecutorService executorService) {
        final Configuration configuration = new TestConfigBuilder().getOrCreateConfig();
        metricsConfig = configuration.getConfigData(MetricsConfig.class);

        when(globalMetrics.isGlobalMetrics()).thenReturn(true);
        when(globalMetrics.getAll()).thenReturn(List.of(globalMetric));

        when(platform1Metric.getName()).thenReturn("platform");

        when(platform1Metrics.isPlatformMetrics()).thenReturn(true);
        when(platform1Metrics.getNodeId()).thenReturn(NODE_ID_1);
        when(platform1Metrics.getAll()).thenReturn(List.of(globalMetric, platform1Metric));

        when(platform2Metric.getName()).thenReturn("platform");

        when(platform2Metrics.isPlatformMetrics()).thenReturn(true);
        when(platform2Metrics.getNodeId()).thenReturn(NODE_ID_2);
        when(platform2Metrics.getAll()).thenReturn(List.of(globalMetric, platform2Metric));

        when(executorService.schedule(any(Runnable.class), anyLong(), any()))
                .thenAnswer((Answer<Future<?>>) invocation -> {
                    invocation.getArgument(0, Runnable.class).run();
                    return mock(ScheduledFuture.class);
                })
                .thenAnswer(invocation -> mock(ScheduledFuture.class));

        service = new SnapshotService(globalMetrics, executorService, Duration.ZERO);
        service.subscribe(subscriber);
    }

    @Test
    void testRunState() {
        // initial state
        assertFalse(service.isRunning(), "Service should not run right after initialization");

        // when
        service.start();

        // then
        assertTrue(service.isRunning(), "Service should run");
        assertThrows(
                IllegalStateException.class,
                service::start,
                "Trying to start the service again should throw an IllegalStateException");

        // when
        service.shutdown();

        // then
        assertFalse(service.isRunning(), "Service should not run");
        assertDoesNotThrow(service::shutdown, "Trying to shutdown the service again should be a no-op");
    }

    @Test
    void testNoPlatforms() {
        // when
        service.start();

        // then
        final ArgumentCaptor<SnapshotEvent> notification = ArgumentCaptor.forClass(SnapshotEvent.class);
        verify(subscriber).accept(notification.capture());
        assertThat(notification.getValue().nodeId()).isNull();
        assertThat(notification.getValue().snapshots()).containsExactly(Snapshot.of(globalMetric));
    }

    @Test
    void testAddingOnePlatform() {
        // when
        service.addPlatformMetric(platform1Metrics);
        service.start();

        // then
        final ArgumentCaptor<SnapshotEvent> notification = ArgumentCaptor.forClass(SnapshotEvent.class);
        verify(subscriber, times(2)).accept(notification.capture());
        assertThat(notification.getValue().nodeId()).isEqualTo(NODE_ID_1);
        assertThat(notification.getValue().snapshots())
                .containsExactly(Snapshot.of(globalMetric), Snapshot.of(platform1Metric));
    }

    @Test
    void testAddingTwoPlatforms() {
        // when
        service.addPlatformMetric(platform1Metrics);
        service.addPlatformMetric(platform2Metrics);
        service.start();

        // then
        final ArgumentCaptor<SnapshotEvent> notification = ArgumentCaptor.forClass(SnapshotEvent.class);
        verify(subscriber, times(3)).accept(notification.capture());
        assertThat(notification.getValue().nodeId()).isEqualTo(NODE_ID_2);
        assertThat(notification.getValue().snapshots())
                .containsExactly(Snapshot.of(globalMetric), Snapshot.of(platform2Metric));
    }

    @Test
    void testRemovingPlatforms() {
        // when
        service.addPlatformMetric(platform1Metrics);
        service.addPlatformMetric(platform2Metrics);
        service.removePlatformMetric(platform2Metrics);
        service.start();

        // then
        final ArgumentCaptor<SnapshotEvent> notification = ArgumentCaptor.forClass(SnapshotEvent.class);
        verify(subscriber, times(2)).accept(notification.capture());
        assertThat(notification.getValue().nodeId()).isEqualTo(NODE_ID_1);
        assertThat(notification.getValue().snapshots())
                .containsExactly(Snapshot.of(globalMetric), Snapshot.of(platform1Metric));
    }

    @Test
    void testRegularLoopBehavior(@Mock final ScheduledExecutorService executorService) {
        // given
        final Duration loopDelay = metricsConfig.getMetricsSnapshotDuration();
        final Time time = new FakeTime(Duration.ofMillis(100));
        final SnapshotService service = new SnapshotService(globalMetrics, executorService, loopDelay, time);

        // when
        service.start();

        // then
        final ArgumentCaptor<Runnable> mainLoop = ArgumentCaptor.forClass(Runnable.class);
        final ArgumentCaptor<Long> delay = ArgumentCaptor.forClass(Long.class);
        final ArgumentCaptor<TimeUnit> timeUnit = ArgumentCaptor.forClass(TimeUnit.class);
        verify(executorService).schedule(mainLoop.capture(), delay.capture(), timeUnit.capture());
        final Duration firstDelay =
                Duration.of(delay.getValue(), timeUnit.getValue().toChronoUnit());
        assertThat(firstDelay).isCloseTo(loopDelay, Duration.ofMillis(1));
        reset(executorService);

        // when
        mainLoop.getValue().run();

        // then
        verify(executorService).schedule(any(Runnable.class), delay.capture(), timeUnit.capture());
        final Duration regularDelay =
                Duration.of(delay.getValue(), timeUnit.getValue().toChronoUnit());
        assertThat(regularDelay).isCloseTo(loopDelay.minusMillis(100), Duration.ofMillis(1));
    }

    @Test
    void testLongLoopBehavior(@Mock final ScheduledExecutorService executorService) {
        // given
        final Duration loopDelay = metricsConfig.getMetricsSnapshotDuration();
        final Time time = new FakeTime(Duration.ofSeconds(10 * metricsConfig.csvWriteFrequency()));
        final SnapshotService service = new SnapshotService(globalMetrics, executorService, loopDelay, time);

        final ArgumentCaptor<Runnable> mainLoop = ArgumentCaptor.forClass(Runnable.class);
        final ArgumentCaptor<Long> delay = ArgumentCaptor.forClass(Long.class);
        final ArgumentCaptor<TimeUnit> timeUnit = ArgumentCaptor.forClass(TimeUnit.class);
        service.start();
        verify(executorService, timeout(10)).schedule(mainLoop.capture(), anyLong(), any());
        reset(executorService);

        // when
        mainLoop.getValue().run();

        // then
        verify(executorService).schedule(any(Runnable.class), delay.capture(), timeUnit.capture());
        final Duration longLoopDelay =
                Duration.of(delay.getValue(), timeUnit.getValue().toChronoUnit());
        assertThat(longLoopDelay).isCloseTo(Duration.ofMillis(0), Duration.ofMillis(1));
    }
}
