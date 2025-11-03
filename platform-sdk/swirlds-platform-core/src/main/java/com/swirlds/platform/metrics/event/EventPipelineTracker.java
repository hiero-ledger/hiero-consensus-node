// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.metrics.event;

import com.swirlds.base.time.Time;
import com.swirlds.metrics.api.Metrics;
import com.swirlds.platform.components.consensus.ConsensusEngineOutput;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import org.hiero.consensus.model.event.PlatformEvent;
import org.hiero.consensus.model.hashgraph.ConsensusRound;

/**
 * Tracks the delay experienced by events at each stage of the event processing pipeline.
 */
public class EventPipelineTracker {
    private final MaxDurationMetric hashing;
    private final MaxDurationMetric validation;
    private final MaxDurationMetric deduplication;
    private final MaxDurationMetric sigVerification;
    private final MaxDurationMetric orphanBuffer;
    private final MaxDurationMetric pces;
    private final MaxDurationMetric consensus;

    /**
     * Constructor.
     *
     * @param metrics the metrics system
     * @param time    the time source
     */
    public EventPipelineTracker(@NonNull final Metrics metrics, @NonNull final Time time) {
        int step = 1;
        this.hashing = new MaxDurationMetric(metrics, time, "eventDelay.%d.hashing".formatted(step++));
        this.validation = new MaxDurationMetric(metrics, time, "eventDelay.%d.validation".formatted(step++));
        this.deduplication = new MaxDurationMetric(metrics, time, "eventDelay.%d.deduplication".formatted(step++));
        this.sigVerification = new MaxDurationMetric(metrics, time, "eventDelay.%d.verification".formatted(step++));
        this.orphanBuffer = new MaxDurationMetric(metrics, time, "eventDelay.%d.orphanBuffer".formatted(step++));
        this.pces = new MaxDurationMetric(metrics, time, "eventDelay.%d.pces".formatted(step++));
        this.consensus = new MaxDurationMetric(metrics, time, "eventDelay.%d.consensus".formatted(step));
    }

    /**
     * Called after an event has been hashed.
     *
     * @param event the event that has been hashed
     */
    public void afterHashing(@NonNull final PlatformEvent event) {
        hashing.update(event.getTimeReceived());
    }

    /**
     * Called after an event has been validated.
     *
     * @param event the event that has been validated
     */
    public void afterValidation(@NonNull final PlatformEvent event) {
        validation.update(event.getTimeReceived());
    }

    /**
     * Called after an event has been deduplicated.
     *
     * @param event the event that has been deduplicated
     */
    public void afterDeduplication(@NonNull final PlatformEvent event) {
        deduplication.update(event.getTimeReceived());
    }

    /**
     * Called after an event's signature has been verified.
     *
     * @param event the event that has been signature verified
     */
    public void afterSigVerification(@NonNull final PlatformEvent event) {
        sigVerification.update(event.getTimeReceived());
    }

    /**
     * Called after events have exited the orphan buffer.
     *
     * @param events the events that have exited the orphan buffer
     */
    public void afterOrphanBuffer(@NonNull final List<PlatformEvent> events) {
        if (events.isEmpty()) {
            return;
        }
        orphanBuffer.update(events.stream()
                .map(PlatformEvent::getTimeReceived)
                .max(Comparator.naturalOrder())
                .get());
    }

    /**
     * Called after an event has been written to PCES.
     *
     * @param event the event that has been written to PCES
     */
    public void afterPces(@NonNull final PlatformEvent event) {
        pces.update(event.getTimeReceived());
    }

    /**
     * Called after consensus has been reached on one or more rounds.
     *
     * @param output the consensus engine output containing the rounds that reached consensus
     */
    public void afterConsensus(@NonNull final ConsensusEngineOutput output) {
        final Optional<Instant> max = output.consensusRounds().stream()
                .map(ConsensusRound::getConsensusEvents)
                .flatMap(List::stream)
                .map(PlatformEvent::getTimeReceived)
                .max(Comparator.naturalOrder());
        if (max.isEmpty()) {
            return;
        }
        consensus.update(max.get());
    }
}
