// SPDX-License-Identifier: Apache-2.0
package com.swirlds.common.metrics.event;

import static com.swirlds.metrics.api.Metrics.PLATFORM_CATEGORY;
import static java.util.Objects.requireNonNull;

import com.swirlds.common.metrics.statistics.AverageAndMaxTimeStat;
import com.swirlds.metrics.api.Metrics;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.hiero.consensus.model.event.PlatformEvent;
import org.hiero.consensus.model.hashgraph.ConsensusRound;

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
     * @param event the event to record
     */
    public void recordEvent(@NonNull final String name, @NonNull final PlatformEvent event) {
        final AverageAndMaxTimeStat stat = metricMap.get(name);
        if (stat != null) {
            stat.update(event.getTimeReceived());
        } else {
            throw new IllegalArgumentException("No metric registered for stage: " + name);
        }
    }

    /**
     * Records the delay experienced by a list of events after the specified stage.
     *
     * @param name   the name of the stage
     * @param events the list of events to record
     */
    public void recordEvents(@NonNull final String name, @NonNull final List<PlatformEvent> events) {
        final AverageAndMaxTimeStat stat = metricMap.get(name);
        if (stat != null) {
            for (final PlatformEvent event : events) {
                stat.update(event.getTimeReceived());
            }
        } else {
            throw new IllegalArgumentException("No metric registered for stage: " + name);
        }
    }

    /**
     * Records the delay experienced by all events in the given consensus rounds after the specified stage.
     *
     * @param name            the name of the stage
     * @param consensusRounds the list of consensus rounds to record
     */
    public void recordRounds(@NonNull final String name, @NonNull final List<ConsensusRound> consensusRounds) {
        final AverageAndMaxTimeStat stat = metricMap.get(name);
        if (stat != null) {
            for (final ConsensusRound round : consensusRounds) {
                for (final PlatformEvent event : round.getConsensusEvents()) {
                    stat.update(event.getTimeReceived());
                }
            }
        } else {
            throw new IllegalArgumentException("No metric registered for stage: " + name);
        }
    }
}
