// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.metrics.statistics;

import static com.swirlds.metrics.api.Metrics.PLATFORM_CATEGORY;
import static java.util.Objects.requireNonNull;

import com.swirlds.metrics.api.Metrics;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Tracks the delay experienced by events at each stage of the event processing pipeline.
 */
public class EventPipelineTracker {

    private final Metrics metrics;
    private final Map<String, AverageAndMaxTimeStat> metricMap = new HashMap<>();

    /**
     * Constructor.
     *
     * @param metrics the metrics system
     */
    public EventPipelineTracker(@NonNull final Metrics metrics) {
        this.metrics = requireNonNull(metrics);
    }

    /**
     * Registers a new metric for tracking event delay after the specified stage.
     *
     * @param name the name of the stage
     */
    public void registerMetric(@NonNull final String name) {
        final int step = metricMap.size() + 1;
        final AverageAndMaxTimeStat stat = new AverageAndMaxTimeStat(
                metrics,
                ChronoUnit.MICROS,
                PLATFORM_CATEGORY,
                "eventDelay_%d_%s".formatted(step, name),
                "event pipeline delay until after " + name);
        metricMap.put(name, stat);
    }

    /**
     * Records the delay experienced by a single event after the specified stage.
     *
     * @param name  the name of the stage
     * @param start the {@link Instant} when the event entered the pipeline
     */
    public void recordEvent(@NonNull final String name, @NonNull final Instant start) {
        final AverageAndMaxTimeStat stat = metricMap.get(name);
        if (stat != null) {
            stat.update(start);
        } else {
            throw new IllegalArgumentException("No metric registered for stage: " + name);
        }
    }

    /**
     * Records the delay experienced by a list of events after the specified stage.
     *
     * @param name   the name of the stage
     * @param starts the list of instances when each event entered the pipeline
     */
    public void recordEvents(@NonNull final String name, @NonNull final List<Instant> starts) {
        final AverageAndMaxTimeStat stat = metricMap.get(name);
        if (stat != null) {
            for (final Instant start : starts) {
                stat.update(start);
            }
        } else {
            throw new IllegalArgumentException("No metric registered for stage: " + name);
        }
    }
}
