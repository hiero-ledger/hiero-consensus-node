// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.metrics.statistics;

import static com.swirlds.metrics.api.Metrics.PLATFORM_CATEGORY;
import static java.util.Objects.requireNonNull;

import com.swirlds.metrics.api.Metrics;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.hiero.consensus.model.event.EventOrigin;
import org.hiero.consensus.model.event.PlatformEvent;

/**
 * Tracks the delay experienced by events at each stage of the event processing pipeline.
 * <p>
 * Metrics are split by {@link EventOrigin}, with a dedicated suffix for each origin:
 * <ul>
 *   <li>{@code _runtime} — events created by this runtime ({@link EventOrigin#RUNTIME})</li>
 *   <li>{@code _gossip} — events received through gossip ({@link EventOrigin#GOSSIP})</li>
 *   <li>{@code _storage} — events read from storage ({@link EventOrigin#STORAGE})</li>
 * </ul>
 */
public class EventPipelineTracker {

    private record MetricKey(@NonNull String name, @NonNull EventOrigin origin) {}

    private final Metrics metrics;
    private final Map<MetricKey, AverageAndMaxTimeStat> metricMap = new HashMap<>();
    private final Map<EventOrigin, Integer> stepCounters = new EnumMap<>(EventOrigin.class);

    /**
     * Constructor.
     *
     * @param metrics the metrics system
     */
    public EventPipelineTracker(@NonNull final Metrics metrics) {
        this.metrics = requireNonNull(metrics);
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
        final String baseDesc = "event pipeline delay until after " + name;

        for (final EventOrigin origin : origins) {
            final int step = stepCounters.merge(origin, 1, Integer::sum);
            final String originName = origin.name().toLowerCase();
            metricMap.put(
                    new MetricKey(name, origin),
                    new AverageAndMaxTimeStat(
                            metrics,
                            ChronoUnit.MICROS,
                            PLATFORM_CATEGORY,
                            "eventDelay_%d_%s_%s".formatted(step, name, originName),
                            baseDesc + " (" + originName + ")"));
        }
    }

    /**
     * Records the delay experienced by a single event after the specified stage.
     * The delay is measured from {@link PlatformEvent#getTimeReceived()} to {@link Instant#now()}.
     * <p>
     * For concurrent pipelines, prefer {@link #recordEvent(String, PlatformEvent, Instant)} to
     * avoid measuring thread scheduling noise.
     *
     * @param name  the name of the stage
     * @param event the platform event
     */
    public void recordEvent(@NonNull final String name, @NonNull final PlatformEvent event) {
        final AverageAndMaxTimeStat stat = metricMap.get(new MetricKey(name, event.getOrigin()));
        if (stat != null) {
            stat.update(event.getTimeReceived());
        }
    }

    /**
     * Records the delay experienced by a single event after the specified stage, using a
     * pre-captured timestamp. This avoids measuring thread scheduling delay between the
     * call site and the stat update, which matters in concurrent pipelines.
     *
     * @param name  the name of the stage
     * @param event the platform event
     * @param now   the timestamp captured at the point the stage completed
     */
    public void recordEvent(
            @NonNull final String name, @NonNull final PlatformEvent event, @NonNull final Instant now) {
        final AverageAndMaxTimeStat stat = metricMap.get(new MetricKey(name, event.getOrigin()));
        if (stat != null) {
            stat.update(event.getTimeReceived(), now);
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
