// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.transaction.handling.internal;

import static com.swirlds.metrics.api.Metrics.INTERNAL_CATEGORY;
import static java.util.Objects.requireNonNull;
import static org.hiero.consensus.transaction.handling.internal.TransactionHandlerPhase.IDLE;

import com.swirlds.base.time.Time;
import com.swirlds.metrics.api.LongGauge;
import com.swirlds.metrics.api.Metrics;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Instant;
import org.hiero.consensus.metrics.extensions.PhaseTimer;
import org.hiero.consensus.metrics.extensions.PhaseTimerBuilder;

/**
 * Provides access to statistics relevant to {@link DefaultTransactionHandler}
 */
public class RoundHandlingMetrics {
    private static final LongGauge.Config consensusTimeConfig = new LongGauge.Config(INTERNAL_CATEGORY, "consensusTime")
            .withDescription("The consensus timestamp of the round currently being handled.")
            .withUnit("milliseconds");
    private final LongGauge consensusTime;

    private static final LongGauge.Config consensusTimeDeviationConfig = new LongGauge.Config(
                    INTERNAL_CATEGORY, "consensusTimeDeviation")
            .withDescription("The difference between the consensus time of the round currently being handled and this"
                    + " node's wall clock time. Positive values mean that this node's clock is behind the consensus"
                    + "time, negative values mean that it's ahead.")
            .withUnit("milliseconds");
    private final LongGauge consensusTimeDeviation;

    private static final LongGauge.Config eventsPerRoundConfig = new LongGauge.Config(
                    INTERNAL_CATEGORY, "eventsPerRound")
            .withDescription("The number of events per round")
            .withUnit("count");
    private final LongGauge eventsPerRound;

    private final PhaseTimer<TransactionHandlerPhase> roundHandlerPhase;

    private final Time time;

    /**
     * Constructor
     *
     * @param time the time source
     * @param metrics the metrics system
     */
    public RoundHandlingMetrics(@NonNull final Time time, @NonNull final Metrics metrics) {
        this.time = requireNonNull(time);

        consensusTime = metrics.getOrCreate(consensusTimeConfig);
        consensusTimeDeviation = metrics.getOrCreate(consensusTimeDeviationConfig);
        eventsPerRound = metrics.getOrCreate(eventsPerRoundConfig);

        this.roundHandlerPhase = new PhaseTimerBuilder<>(metrics, time, "platform", TransactionHandlerPhase.class)
                .enableAbsoluteTimeMetrics()
                .enableFractionalMetrics()
                .setInitialPhase(IDLE)
                .setMetricsNamePrefix("consensus")
                .build();
    }

    /**
     * Records the number of events in a round.
     *
     * @param eventCount the number of events in the round
     */
    public void recordEventsPerRound(final int eventCount) {
        eventsPerRound.set(eventCount);
    }

    /**
     * Records the consensus time.
     *
     * @param consensusTime the consensus time of the last transaction in the round that is currently being handled
     */
    public void recordConsensusTime(@NonNull final Instant consensusTime) {
        this.consensusTime.set(consensusTime.toEpochMilli());
        consensusTimeDeviation.set(consensusTime.toEpochMilli() - time.now().toEpochMilli());
    }

    /**
     * Activate a new phase of the transaction handler.
     *
     * @param phase the new phase
     */
    public void setPhase(@NonNull final TransactionHandlerPhase phase) {
        requireNonNull(phase);
        roundHandlerPhase.activatePhase(phase);
    }
}
