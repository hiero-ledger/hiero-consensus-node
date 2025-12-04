// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.metrics.event;

import static com.swirlds.metrics.api.Metrics.PLATFORM_CATEGORY;

import com.swirlds.metrics.api.Metrics;
import org.hiero.consensus.model.hashgraph.ConsensusEngineOutput;
import com.swirlds.platform.stats.AverageAndMaxTimeStat;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.hiero.consensus.model.event.PlatformEvent;
import org.hiero.consensus.model.hashgraph.ConsensusRound;

/**
 * Tracks the delay experienced by events at each stage of the event processing pipeline.
 */
public class EventPipelineTracker {
    private final AverageAndMaxTimeStat hashing;
    private final AverageAndMaxTimeStat validation;
    private final AverageAndMaxTimeStat deduplication;
    private final AverageAndMaxTimeStat sigVerification;
    private final AverageAndMaxTimeStat orphanBuffer;
    private final AverageAndMaxTimeStat pces;
    private final AverageAndMaxTimeStat consensus;

    /**
     * Constructor.
     *
     * @param metrics the metrics system
     */
    public EventPipelineTracker(@NonNull final Metrics metrics) {
        int step = 1;
        this.hashing = new AverageAndMaxTimeStat(
                metrics,
                ChronoUnit.MICROS,
                PLATFORM_CATEGORY,
                "eventDelay_%d_hashing".formatted(step++),
                "event pipeline delay until after hashing");
        this.validation = new AverageAndMaxTimeStat(
                metrics,
                ChronoUnit.MICROS,
                PLATFORM_CATEGORY,
                "eventDelay_%d_validation".formatted(step++),
                "event pipeline delay until after validation");
        this.deduplication = new AverageAndMaxTimeStat(
                metrics,
                ChronoUnit.MICROS,
                PLATFORM_CATEGORY,
                "eventDelay_%d_deduplication".formatted(step++),
                "event pipeline delay until after deduplication");
        this.sigVerification = new AverageAndMaxTimeStat(
                metrics,
                ChronoUnit.MICROS,
                PLATFORM_CATEGORY,
                "eventDelay_%d_verification".formatted(step++),
                "event pipeline delay until after verification");
        this.orphanBuffer = new AverageAndMaxTimeStat(
                metrics,
                ChronoUnit.MICROS,
                PLATFORM_CATEGORY,
                "eventDelay_%d_orphanBuffer".formatted(step++),
                "event pipeline delay until after orphanBuffer");
        this.pces = new AverageAndMaxTimeStat(
                metrics,
                ChronoUnit.MICROS,
                PLATFORM_CATEGORY,
                "eventDelay_%d_pces".formatted(step++),
                "event pipeline delay until after pces");
        this.consensus = new AverageAndMaxTimeStat(
                metrics,
                ChronoUnit.MICROS,
                PLATFORM_CATEGORY,
                "eventDelay_%d_consensus".formatted(step++),
                "event pipeline delay until after consensus");
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
        for (final PlatformEvent event : events) {
            orphanBuffer.update(event.getTimeReceived());
        }
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
        for (final ConsensusRound round : output.consensusRounds()) {
            for (final PlatformEvent event : round.getConsensusEvents()) {
                consensus.update(event.getTimeReceived());
            }
        }
    }
}
