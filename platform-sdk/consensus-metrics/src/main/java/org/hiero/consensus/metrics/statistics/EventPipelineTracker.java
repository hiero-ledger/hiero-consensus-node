// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.metrics.statistics;

import static com.swirlds.metrics.api.Metrics.PLATFORM_CATEGORY;
import static java.util.Objects.requireNonNull;

import com.swirlds.base.time.Time;
import com.swirlds.config.api.Configuration;
import com.swirlds.metrics.api.Metrics;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.hiero.consensus.metrics.config.ConsensusLayerMetricsConfig;
import org.hiero.consensus.metrics.extensions.LatencyGauges;
import org.hiero.consensus.metrics.extensions.LatencyGauges.Config;
import org.hiero.consensus.model.event.EventOrigin;
import org.hiero.consensus.model.event.PlatformEvent;

/**
 * Tracks the delay experienced by events at each stage of the event processing pipeline.
 * <p>
 * Two families of metrics can be enabled independently in {@link ConsensusLayerMetricsConfig}:
 * <ul>
 *   <li>The average and maximum delay since the event was received ({@code eventDelay_*}), split by
 *   {@link EventOrigin}. They will eventually be removed.</li>
 *   <li>Percentiles and the maximum of the delay since the previous stage, over a sliding window
 *   ({@code eventLatency_*}, see {@link LatencyGauges}). Each stage has one set of gauges for all origins except
 *   {@link EventOrigin#STORAGE}: events read from storage arrive only during the replay at startup, which feeds them as
 *   fast as the pipeline takes them, so their delays say nothing about the latency of a running node.</li>
 * </ul>
 * The average and maximum delay use a dedicated suffix for each origin:
 * <ul>
 *   <li>{@code _runtime} — events created by this runtime ({@link EventOrigin#RUNTIME})</li>
 *   <li>{@code _gossip} — events received through gossip ({@link EventOrigin#GOSSIP})</li>
 *   <li>{@code _storage} — events read from storage ({@link EventOrigin#STORAGE})</li>
 * </ul>
 * The delay since the previous stage starts at {@link PlatformEvent#getLastStageTime()}, which every stage with latency
 * gauges sets when it records an event. A stage must therefore record an event before the next stage receives it: on
 * an output wire, the monitor that records the stage has to be soldered before the next stage.
 */
public class EventPipelineTracker {

    private record MetricKey(@NonNull String name, @NonNull EventOrigin origin) {}

    /**
     * The metrics of one stage and origin; a metric is {@code null} if its family is disabled or does not cover the
     * origin. The latency gauges are shared by all origins of the stage.
     */
    private record StageMetrics(
            @Nullable AverageAndMaxTimeStat averageAndMax,
            @Nullable LatencyGauges latency) {

        void record(@NonNull final PlatformEvent event, @NonNull final Instant now) {
            if (averageAndMax != null) {
                averageAndMax.updateNanos(
                        Duration.between(event.getTimeReceived(), now).toNanos());
            }
            if (latency != null) {
                latency.record(Duration.between(event.getLastStageTime(), now).toNanos());
                event.setLastStageTime(now);
            }
        }
    }

    private final Metrics metrics;
    private final Configuration configuration;
    private final Time time;
    private final boolean averageAndMaxEnabled;
    private final boolean latencyEnabled;
    private final Duration window;
    private final List<Double> percentiles;
    private final Duration selfEventsWindow;
    private final List<Double> selfEventsPercentiles;
    private final Map<MetricKey, StageMetrics> metricMap = new HashMap<>();
    private final Map<EventOrigin, Integer> stepCounters = new EnumMap<>(EventOrigin.class);

    /**
     * Creates a tracker, unless the {@link ConsensusLayerMetricsConfig} disables both families of metrics.
     *
     * @param metrics       the metrics system
     * @param configuration the configuration
     * @param time          the clock that measures the delays and advances the window of the latency gauges
     * @return the tracker, or {@code null} if both families of metrics are disabled
     */
    @Nullable
    public static EventPipelineTracker create(
            @NonNull final Metrics metrics, @NonNull final Configuration configuration, @NonNull final Time time) {
        final ConsensusLayerMetricsConfig config = configuration.getConfigData(ConsensusLayerMetricsConfig.class);
        return config.averageAndMaxEnabled() || config.latencyEnabled()
                ? new EventPipelineTracker(metrics, configuration, time, config)
                : null;
    }

    private EventPipelineTracker(
            @NonNull final Metrics metrics,
            @NonNull final Configuration configuration,
            @NonNull final Time time,
            @NonNull final ConsensusLayerMetricsConfig config) {
        this.metrics = requireNonNull(metrics);
        this.configuration = requireNonNull(configuration);
        this.time = requireNonNull(time);
        this.averageAndMaxEnabled = config.averageAndMaxEnabled();
        this.latencyEnabled = config.latencyEnabled();
        this.window = config.latencyWindow();
        this.percentiles = config.latencyPercentiles();
        this.selfEventsWindow = config.selfEventsLatencyWindow();
        this.selfEventsPercentiles = config.selfEventsLatencyPercentiles();
    }

    /**
     * Registers metrics for tracking event delay after the specified stage, for all {@link EventOrigin} values.
     *
     * @param name the name of the stage
     */
    public void registerMetric(@NonNull final String name) {
        registerMetric(name, EventOrigin.values());
    }

    /**
     * Registers metrics for tracking event delay after the specified stage.
     *
     * @param name    the name of the stage
     * @param origins the event origins expected to pass through this stage
     */
    public void registerMetric(@NonNull final String name, @NonNull final EventOrigin... origins) {
        final LatencyGauges latency = latencyEnabled ? registerLatency(name, origins) : null;
        final String baseDesc = "event pipeline delay until after " + name;

        for (final EventOrigin origin : origins) {
            final int step = stepCounters.merge(origin, 1, Integer::sum);
            final String originName = origin.name().toLowerCase();
            final String desc = baseDesc + " (" + originName + ")";
            final AverageAndMaxTimeStat averageAndMax = averageAndMaxEnabled
                    ? new AverageAndMaxTimeStat(
                            metrics,
                            ChronoUnit.MICROS,
                            PLATFORM_CATEGORY,
                            "eventDelay_%d_%s_%s".formatted(step, name, originName),
                            desc)
                    : null;
            metricMap.put(
                    new MetricKey(name, origin),
                    new StageMetrics(averageAndMax, origin == EventOrigin.STORAGE ? null : latency));
        }
    }

    /**
     * Registers the latency gauges of a stage, shared by all origins except {@link EventOrigin#STORAGE}. A stage that
     * only self events pass sees far fewer events than the others, so it has its own window and percentiles; by default
     * the window is longer and the percentiles end at p99. Must be called before the step counters of the stage's
     * origins are advanced.
     *
     * @param name    the name of the stage
     * @param origins the event origins expected to pass through this stage
     * @return the latency gauges, or {@code null} if only events read from storage pass the stage
     */
    @Nullable
    private LatencyGauges registerLatency(@NonNull final String name, @NonNull final EventOrigin... origins) {
        final List<EventOrigin> measured = Arrays.stream(origins)
                .filter(origin -> origin != EventOrigin.STORAGE)
                .toList();
        if (measured.isEmpty()) {
            return null;
        }
        // the next step of the stage's origins; they agree for every stage registered after the first
        final int step = stepCounters.getOrDefault(measured.getFirst(), 0) + 1;
        final Config baseConfig = new Config(PLATFORM_CATEGORY, "eventLatency_%d_%s".formatted(step, name))
                .withDescription("event pipeline delay from the previous stage until after " + name);
        final Config config;
        if (measured.equals(List.of(EventOrigin.RUNTIME))) {
            config = baseConfig.withWindow(selfEventsWindow).withPercentiles(selfEventsPercentiles);
        } else {
            config = baseConfig.withWindow(window).withPercentiles(percentiles);
        }
        return LatencyGauges.register(metrics, configuration, time, config);
    }

    /**
     * Records the delay experienced by a single event after the specified stage.
     * The event is routed to the appropriate metric based on its {@link EventOrigin}.
     *
     * @param name  the name of the stage
     * @param event the platform event
     */
    public void recordEvent(@NonNull final String name, @NonNull final PlatformEvent event) {
        final StageMetrics stageMetrics = metricMap.get(new MetricKey(name, event.getOrigin()));
        if (stageMetrics != null) {
            stageMetrics.record(event, time.now());
        }
    }

    /**
     * Records the delay experienced by a list of events after the specified stage.
     *
     * @param name   the name of the stage
     * @param events the list of platform events
     */
    public void recordEvents(@NonNull final String name, @NonNull final List<PlatformEvent> events) {
        for (final PlatformEvent event : events) {
            recordEvent(name, event);
        }
    }
}
