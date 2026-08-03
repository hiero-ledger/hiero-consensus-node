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

    private final PhaseTimer<TransactionHandlerPhase> roundHandlerPhase;

    /**
     * Constructor
     *
     * @param time the time source
     * @param metrics the metrics system
     */
    public RoundHandlingMetrics(@NonNull final Time time, @NonNull final Metrics metrics) {

        this.roundHandlerPhase = new PhaseTimerBuilder<>(metrics, time, "platform", TransactionHandlerPhase.class)
                .enableAbsoluteTimeMetrics()
                .enableFractionalMetrics()
                .setInitialPhase(IDLE)
                .setMetricsNamePrefix("consensus")
                .build();
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
