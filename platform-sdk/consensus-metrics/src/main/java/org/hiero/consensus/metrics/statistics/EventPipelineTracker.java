// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.metrics.statistics;

import static com.swirlds.metrics.api.Metrics.PLATFORM_CATEGORY;
import static java.util.Objects.requireNonNull;

import com.swirlds.metrics.api.Metrics;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.hiero.consensus.model.event.PlatformEvent;
import org.hiero.consensus.model.node.NodeId;

/**
 * Tracks the delay experienced by events at each stage of the event processing pipeline.
 * <p>
 * Metrics are split into two tracks based on event origin:
 * <ul>
 *   <li>{@code _self} — events created by this node (enter at stage 2, skipping hashing)</li>
 *   <li>{@code _recv} — events received from other nodes via gossip (enter at stage 1)</li>
 * </ul>
 */
public class EventPipelineTracker {

    private static final String SELF_SUFFIX = "_self";
    private static final String RECV_SUFFIX = "_recv";

    private final Metrics metrics;
    private final NodeId selfId;
    private final Map<String, AverageAndMaxTimeStat> metricMap = new HashMap<>();
    private int stepCounter = 0;

    /**
     * Constructor.
     *
     * @param metrics the metrics system
     * @param selfId  the node ID of this node, used to distinguish self vs received events
     */
    public EventPipelineTracker(@NonNull final Metrics metrics, @NonNull final NodeId selfId) {
        this.metrics = requireNonNull(metrics);
        this.selfId = requireNonNull(selfId);
    }

    /**
     * Registers metrics for tracking event delay after the specified stage.
     * <p>
     * When {@code selfEventsExpected} is {@code true}, both {@code _self} and {@code _recv} metrics are registered.
     * When {@code false} (e.g. hashing, which only processes received events), only {@code _recv} is registered.
     *
     * @param name               the name of the stage
     * @param selfEventsExpected whether self-created events pass through this stage
     */
    public void registerMetric(@NonNull final String name, final boolean selfEventsExpected) {
        stepCounter++;
        final String baseName = "eventDelay_%d_%s".formatted(stepCounter, name);
        final String baseDesc = "event pipeline delay until after " + name;

        // Always register recv
        metricMap.put(
                name + RECV_SUFFIX,
                new AverageAndMaxTimeStat(
                        metrics, ChronoUnit.MICROS, PLATFORM_CATEGORY, baseName + RECV_SUFFIX, baseDesc + " (recv)"));

        if (selfEventsExpected) {
            metricMap.put(
                    name + SELF_SUFFIX,
                    new AverageAndMaxTimeStat(
                            metrics,
                            ChronoUnit.MICROS,
                            PLATFORM_CATEGORY,
                            baseName + SELF_SUFFIX,
                            baseDesc + " (self)"));
        }
    }

    /**
     * Records the delay experienced by a single event after the specified stage.
     * The event is routed to the appropriate {@code _self} or {@code _recv} metric
     * based on its creator.
     *
     * @param name  the name of the stage
     * @param event the platform event
     */
    public void recordEvent(@NonNull final String name, @NonNull final PlatformEvent event) {
        final String suffix = event.getCreatorId().equals(selfId) ? SELF_SUFFIX : RECV_SUFFIX;
        final AverageAndMaxTimeStat stat = metricMap.get(name + suffix);
        if (stat != null) {
            stat.update(event.getTimeReceived());
        } else {
            throw new IllegalArgumentException("No metric registered for stage: " + name + suffix);
        }
    }

    /**
     * Records the delay experienced by a list of events after the specified stage.
     * Each event is routed to the appropriate {@code _self} or {@code _recv} metric
     * based on its creator.
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
