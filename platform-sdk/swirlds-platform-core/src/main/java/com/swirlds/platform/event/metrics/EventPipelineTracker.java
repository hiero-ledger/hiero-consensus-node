package com.swirlds.platform.event.metrics;

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

public class EventPipelineTracker {
    private final MaxDurationMetric hashing;
    private final MaxDurationMetric validation;
    private final MaxDurationMetric deduplication;
    private final MaxDurationMetric sigVerification;
    private final MaxDurationMetric orphanBuffer;
    private final MaxDurationMetric pces;
    private final MaxDurationMetric consensus;

    public EventPipelineTracker(final Metrics metrics, final Time time) {
        int step = 1;
        this.hashing = new MaxDurationMetric(metrics, time, "eventDelay.%d.hashing"
                .formatted(step++));
        this.validation = new MaxDurationMetric(metrics, time, "eventDelay.%d.validation"
                .formatted(step++));
        this.deduplication = new MaxDurationMetric(metrics, time, "eventDelay.%d.deduplication"
                .formatted(step++));
        this.sigVerification = new MaxDurationMetric(metrics, time, "eventDelay.%d.verification"
                .formatted(step++));
        this.orphanBuffer = new MaxDurationMetric(metrics, time, "eventDelay.%d.orphanBuffer"
                .formatted(step++));
        this.pces = new MaxDurationMetric(metrics, time, "eventDelay.%d.pces"
                .formatted(step++));
        this.consensus = new MaxDurationMetric(metrics, time, "eventDelay.%d.consensus"
                .formatted(step));

    }

    public void afterHashing(@NonNull final PlatformEvent event) {
        hashing.update(event.getTimeReceived());
    }

    public void afterValidation(@NonNull final PlatformEvent event) {
        validation.update(event.getTimeReceived());
    }

    public void afterDeduplication(@NonNull final PlatformEvent event) {
        deduplication.update(event.getTimeReceived());
    }

    public void afterSigVerification(@NonNull final PlatformEvent event) {
        sigVerification.update(event.getTimeReceived());
    }

    public void afterOrphanBuffer(@NonNull final List<PlatformEvent> events) {
        if(events.isEmpty()) {
            return;
        }
        orphanBuffer.update(events.stream().map(PlatformEvent::getTimeReceived)
                .max(Comparator.naturalOrder()).get());
    }

    public void afterPces(@NonNull final PlatformEvent event) {
        pces.update(event.getTimeReceived());
    }

    public void afterConsensus(@NonNull final ConsensusEngineOutput output) {
        final Optional<Instant> max = output.consensusRounds()
                .stream()
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
