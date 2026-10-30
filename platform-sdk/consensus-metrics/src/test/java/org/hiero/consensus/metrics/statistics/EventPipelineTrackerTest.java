// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.metrics.statistics;

import static com.swirlds.metrics.api.Metrics.PLATFORM_CATEGORY;
import static java.util.Objects.requireNonNull;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hiero.consensus.metrics.config.ConsensusLayerMetricsConfig_.AVERAGE_AND_MAX_ENABLED;
import static org.hiero.consensus.metrics.config.ConsensusLayerMetricsConfig_.LATENCY_ENABLED;
import static org.hiero.consensus.metrics.config.ConsensusLayerMetricsConfig_.LATENCY_PERCENTILES;
import static org.hiero.consensus.metrics.config.ConsensusLayerMetricsConfig_.LATENCY_WINDOW;
import static org.hiero.consensus.metrics.config.ConsensusLayerMetricsConfig_.SELF_EVENTS_LATENCY_PERCENTILES;
import static org.hiero.consensus.metrics.config.ConsensusLayerMetricsConfig_.SELF_EVENTS_LATENCY_WINDOW;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.swirlds.base.test.fixtures.time.FakeTime;
import com.swirlds.config.api.Configuration;
import com.swirlds.config.extensions.test.fixtures.TestConfigBuilder;
import com.swirlds.metrics.api.Metric;
import com.swirlds.metrics.api.Metric.ValueType;
import com.swirlds.metrics.api.Metrics;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicReference;
import org.hiero.consensus.metrics.config.MetricsConfig;
import org.hiero.consensus.metrics.platform.DefaultPlatformMetrics;
import org.hiero.consensus.metrics.platform.MetricKeyRegistry;
import org.hiero.consensus.metrics.platform.PlatformMetricsFactoryImpl;
import org.hiero.consensus.model.event.EventOrigin;
import org.hiero.consensus.model.event.PlatformEvent;
import org.hiero.consensus.model.node.NodeId;
import org.junit.jupiter.api.Test;

/**
 * Checks which metrics {@link EventPipelineTracker} registers and what one event records in them.
 */
class EventPipelineTrackerTest {

    private static final String STAGE = "hashing";
    /** Differs from the default, so the tests show the window comes from the configuration. */
    private static final String WINDOW = "2m";
    /** Differs from the default, so the tests show the window comes from the configuration. */
    private static final String SELF_EVENTS_WINDOW = "20m";
    /** The step of the gauges over {@link #WINDOW}: the window in 20 snapshots, at the default interval of 3 s. */
    private static final Duration STEP = Duration.ofSeconds(6);

    private final FakeTime time = new FakeTime();
    private final Configuration configuration = new TestConfigBuilder().getOrCreateConfig();
    private final Metrics metrics = newMetrics(configuration);

    @Test
    void noTrackerIfBothFamiliesAreDisabled() {
        assertThat(EventPipelineTracker.create(metrics, trackerConfiguration(false, false), time))
                .isNull();
    }

    @Test
    void defaultWindowsAreFiveAndTenMinutes() {
        final EventPipelineTracker tracker = requireNonNull(EventPipelineTracker.create(metrics, configuration, time));
        tracker.registerMetric("eventCreation", EventOrigin.RUNTIME);
        tracker.registerMetric(STAGE, EventOrigin.GOSSIP, EventOrigin.STORAGE);
        assertThat(descriptionOf("eventLatency_1_eventCreation_max")).endsWith("over the last 600 s");
        assertThat(descriptionOf("eventLatency_1_hashing_max")).endsWith("over the last 300 s");
    }

    @Test
    void bothFamiliesAreRegistered() {
        newTracker(true, true).registerMetric(STAGE, EventOrigin.GOSSIP);
        assertThat(metrics.getAll())
                .extracting(Metric::getName)
                .containsExactlyInAnyOrder(
                        "eventDelay_1_hashing_gossip",
                        "eventDelay_1_hashing_gossipMAX",
                        "eventLatency_1_hashing_p50",
                        "eventLatency_1_hashing_p90",
                        "eventLatency_1_hashing_p99",
                        "eventLatency_1_hashing_p99_9",
                        "eventLatency_1_hashing_max");
        assertThat(descriptionOf("eventLatency_1_hashing_max")).endsWith("over the last 120 s");
    }

    @Test
    void allOriginsShareOneSetOfLatencyGauges() {
        newTracker(false, true).registerMetric(STAGE);
        assertThat(metrics.getAll())
                .extracting(Metric::getName)
                .containsExactlyInAnyOrder(
                        "eventLatency_1_hashing_p50",
                        "eventLatency_1_hashing_p90",
                        "eventLatency_1_hashing_p99",
                        "eventLatency_1_hashing_p99_9",
                        "eventLatency_1_hashing_max");
    }

    @Test
    void aStageOnlyOwnEventsPassReportsUpToP99() {
        newTracker(false, true).registerMetric("eventCreation", EventOrigin.RUNTIME);
        assertThat(metrics.getAll())
                .extracting(Metric::getName)
                .containsExactlyInAnyOrder(
                        "eventLatency_1_eventCreation_p50",
                        "eventLatency_1_eventCreation_p90",
                        "eventLatency_1_eventCreation_p99",
                        "eventLatency_1_eventCreation_max");
        assertThat(descriptionOf("eventLatency_1_eventCreation_max")).endsWith("over the last 1200 s");
    }

    @Test
    void percentilesComeFromTheConfiguration() {
        final Configuration percentiles = new TestConfigBuilder()
                .withValue(AVERAGE_AND_MAX_ENABLED, false)
                .withValue(LATENCY_PERCENTILES, "75")
                .withValue(SELF_EVENTS_LATENCY_PERCENTILES, "25,99.99")
                .getOrCreateConfig();
        final EventPipelineTracker tracker = requireNonNull(EventPipelineTracker.create(metrics, percentiles, time));
        tracker.registerMetric("eventCreation", EventOrigin.RUNTIME);
        tracker.registerMetric(STAGE, EventOrigin.GOSSIP);
        assertThat(metrics.getAll())
                .extracting(Metric::getName)
                .containsExactlyInAnyOrder(
                        "eventLatency_1_eventCreation_p25",
                        "eventLatency_1_eventCreation_p99_99",
                        "eventLatency_1_eventCreation_max",
                        "eventLatency_1_hashing_p75",
                        "eventLatency_1_hashing_max");
    }

    @Test
    void aStageOnlyEventsFromStoragePassGetsNoLatencyGauges() {
        newTracker(true, true).registerMetric(STAGE, EventOrigin.STORAGE);
        assertThat(metrics.getAll())
                .extracting(Metric::getName)
                .containsExactlyInAnyOrder("eventDelay_1_hashing_storage", "eventDelay_1_hashing_storageMAX");
    }

    @Test
    void onlyTheAverageAndMaxAreRegistered() {
        final EventPipelineTracker tracker = newTracker(true, false);
        tracker.registerMetric(STAGE, EventOrigin.GOSSIP);
        assertThat(metrics.getAll())
                .extracting(Metric::getName)
                .containsExactlyInAnyOrder("eventDelay_1_hashing_gossip", "eventDelay_1_hashing_gossipMAX");
    }

    @Test
    void stepsAreNumberedPerOrigin() {
        final EventPipelineTracker tracker = newTracker(true, true);
        tracker.registerMetric("eventCreation", EventOrigin.RUNTIME);
        tracker.registerMetric(STAGE, EventOrigin.GOSSIP, EventOrigin.STORAGE);
        tracker.registerMetric("validation");
        assertThat(metrics.getAll())
                .extracting(Metric::getName)
                .contains(
                        "eventLatency_1_eventCreation_max",
                        "eventLatency_1_hashing_max",
                        "eventLatency_2_validation_max",
                        "eventDelay_2_validation_runtime",
                        "eventDelay_2_validation_gossip",
                        "eventDelay_2_validation_storage");
    }

    @Test
    void latencyIsTheDelaySinceThePreviousStage() {
        final EventPipelineTracker tracker = newTracker(true, true);
        tracker.registerMetric(STAGE, EventOrigin.GOSSIP);
        tracker.registerMetric("validation", EventOrigin.GOSSIP);
        final PlatformEvent event = newEvent(EventOrigin.GOSSIP, time.now());

        time.tick(Duration.of(200, ChronoUnit.MICROS));
        tracker.recordEvent(STAGE, event);
        assertThat(event.getLastStageTime()).isEqualTo(time.now());
        time.tick(Duration.of(300, ChronoUnit.MICROS));
        tracker.recordEvent("validation", event);
        time.tick(STEP);

        assertThat(valueOf("eventLatency_1_hashing_p50"))
                .as("at most 2^-7 above the exact value")
                .isBetween(200.0, 200.0 * (1 + 1.0 / 128));
        assertThat(valueOf("eventLatency_2_validation_p50")).isBetween(300.0, 300.0 * (1 + 1.0 / 128));
        assertThat(valueOf("eventLatency_2_validation_max")).isEqualTo(valueOf("eventLatency_2_validation_p50"));
        assertThat(valueOf("eventDelay_2_validation_gossip"))
                .as("the average still measures since the event was received")
                .isEqualTo(500.0);
    }

    @Test
    void eventsFromStorageOnlyCountInTheAverageAndMax() {
        final EventPipelineTracker tracker = newTracker(true, true);
        tracker.registerMetric(STAGE);
        final Instant received = time.now();
        final PlatformEvent event = newEvent(EventOrigin.STORAGE, received);

        time.tick(Duration.of(200, ChronoUnit.MICROS));
        tracker.recordEvent(STAGE, event);
        time.tick(STEP);

        assertThat(valueOf("eventDelay_1_hashing_storage")).isEqualTo(200.0);
        assertThat(valueOf("eventLatency_1_hashing_max"))
                .as("the window is empty")
                .isEqualTo(0.0);
        assertThat(event.getLastStageTime()).isEqualTo(received);
    }

    @Test
    void negativeDelaysCountAsZeroOnlyInTheLatencyGauges() {
        final EventPipelineTracker tracker = newTracker(true, true);
        tracker.registerMetric(STAGE, EventOrigin.GOSSIP);
        tracker.recordEvent(STAGE, newEvent(EventOrigin.GOSSIP, time.now().plusMillis(1)));
        tracker.recordEvent(STAGE, newEvent(EventOrigin.GOSSIP, time.now().minusMillis(1)));
        time.tick(STEP);

        assertThat(valueOf("eventLatency_1_hashing_p50"))
                .as("the negative delay is the lower of the two values")
                .isEqualTo(0.0);
        assertThat(valueOf("eventLatency_1_hashing_max")).isGreaterThanOrEqualTo(1_000.0);
        assertThat(valueOf("eventDelay_1_hashing_gossip"))
                .as("the average keeps recording negative delays")
                .isNegative();
    }

    private EventPipelineTracker newTracker(final boolean averageAndMaxEnabled, final boolean latencyEnabled) {
        return requireNonNull(
                EventPipelineTracker.create(metrics, trackerConfiguration(averageAndMaxEnabled, latencyEnabled), time));
    }

    private static Configuration trackerConfiguration(
            final boolean averageAndMaxEnabled, final boolean latencyEnabled) {
        return new TestConfigBuilder()
                .withValue(AVERAGE_AND_MAX_ENABLED, averageAndMaxEnabled)
                .withValue(LATENCY_ENABLED, latencyEnabled)
                .withValue(LATENCY_WINDOW, WINDOW)
                .withValue(SELF_EVENTS_LATENCY_WINDOW, SELF_EVENTS_WINDOW)
                .getOrCreateConfig();
    }

    /** Creates an event that, like a {@link PlatformEvent}, starts its last stage time at the time received. */
    private static PlatformEvent newEvent(final EventOrigin origin, final Instant received) {
        final AtomicReference<Instant> lastStageTime = new AtomicReference<>(received);
        final PlatformEvent event = mock(PlatformEvent.class);
        when(event.getOrigin()).thenReturn(origin);
        when(event.getTimeReceived()).thenReturn(received);
        when(event.getLastStageTime()).thenAnswer(invocation -> lastStageTime.get());
        doAnswer(invocation -> {
                    lastStageTime.set(invocation.getArgument(0));
                    return null;
                })
                .when(event)
                .setLastStageTime(any());
        return event;
    }

    private String descriptionOf(final String name) {
        return metrics.getMetric(PLATFORM_CATEGORY, name).getDescription();
    }

    private double valueOf(final String name) {
        return (Double) metrics.getMetric(PLATFORM_CATEGORY, name).get(ValueType.VALUE);
    }

    private static Metrics newMetrics(final Configuration configuration) {
        final MetricsConfig metricsConfig = configuration.getConfigData(MetricsConfig.class);
        final MetricKeyRegistry registry = mock(MetricKeyRegistry.class);
        when(registry.register(any(), any(), any())).thenReturn(true);
        return new DefaultPlatformMetrics(
                NodeId.of(1L),
                registry,
                mock(ScheduledExecutorService.class),
                new PlatformMetricsFactoryImpl(metricsConfig),
                metricsConfig);
    }
}
