// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.history.impl;

import static java.util.Objects.requireNonNull;

import com.swirlds.metrics.api.Counter;
import com.swirlds.metrics.api.Metrics;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import javax.inject.Inject;
import javax.inject.Singleton;
import org.hiero.consensus.metrics.RunningAverageMetric;

/**
 * Metrics for the history proof controller state machine.
 */
@Singleton
public class HistoryProofMetrics {
    private static final String CATEGORY = "app";

    private final Metrics metrics;
    private final Counter proofsProduced;
    private final Counter wrapsRetriesStarted;
    private final Counter wrapsRetriesOnCompletedProofs;
    private final RunningAverageMetric wrapsRetriesPerCompletedProof;
    private final Map<Long, StageObservation> stageObservations = new ConcurrentHashMap<>();
    private final Map<StageTransition, TransitionMetrics> transitionMetrics = new ConcurrentHashMap<>();

    public enum Stage {
        WAITING_FOR_METADATA("WMeta", "waiting for metadata"),
        WAITING_FOR_ASSEMBLY("WAsm", "waiting for assembly"),
        WRAPS_R1("R1", "WRAPS R1"),
        WRAPS_R2("R2", "WRAPS R2"),
        WRAPS_R3("R3", "WRAPS R3"),
        WRAPS_AGGREGATE("Agg", "WRAPS aggregate"),
        COMPLETED("Done", "completed"),
        FAILED("Fail", "failed");

        private final String metricToken;
        private final String label;

        Stage(@NonNull final String metricToken, @NonNull final String label) {
            this.metricToken = requireNonNull(metricToken);
            this.label = requireNonNull(label);
        }
    }

    @Inject
    public HistoryProofMetrics(@NonNull final Metrics metrics) {
        this.metrics = requireNonNull(metrics);
        proofsProduced = this.metrics.getOrCreate(new Counter.Config(CATEGORY, "historyProofsProduced")
                .withDescription("Number of completed history proofs produced"));
        wrapsRetriesStarted = this.metrics.getOrCreate(new Counter.Config(CATEGORY, "historyProofWrapsRetriesStarted")
                .withDescription("Number of WRAPS retry attempts started for history proofs"));
        wrapsRetriesOnCompletedProofs =
                this.metrics.getOrCreate(new Counter.Config(CATEGORY, "historyProofRetriesTotal")
                        .withDescription("Total WRAPS retries consumed by completed history proofs"));
        wrapsRetriesPerCompletedProof =
                this.metrics.getOrCreate(new RunningAverageMetric.Config(CATEGORY, "historyProofRetriesAvg")
                        .withDescription("Average WRAPS retries consumed per completed history proof")
                        .withUnit("retries")
                        .withFormat("%,13.3f"));
        registerEagerTransitionMetrics();
    }

    /**
     * Observes the current stage for a construction at the given consensus time.
     *
     * @param constructionId the construction id
     * @param stage the current stage
     * @param consensusNow the current consensus time
     */
    public void observeStage(
            final long constructionId, @NonNull final Stage stage, @NonNull final Instant consensusNow) {
        requireNonNull(stage);
        requireNonNull(consensusNow);
        stageObservations.compute(constructionId, (id, previous) -> {
            if (previous == null) {
                return new StageObservation(stage, consensusNow);
            }
            if (previous.stage() != stage) {
                final long elapsedMillis = elapsedMillis(previous.enteredAt(), consensusNow);
                transitionMetricsFor(previous.stage(), stage).update(elapsedMillis);
                return new StageObservation(stage, consensusNow);
            }
            return previous;
        });
    }

    /**
     * Records that a construction restarted WRAPS signing.
     */
    public void recordRetryStarted() {
        wrapsRetriesStarted.increment();
    }

    /**
     * Records completion of a construction.
     *
     * @param constructionId the construction id
     * @param wrapsRetryCount the number of retries consumed by the construction
     */
    public void recordProofCompleted(final long constructionId, final int wrapsRetryCount) {
        final int retries = Math.max(0, wrapsRetryCount);
        proofsProduced.increment();
        if (retries > 0) {
            wrapsRetriesOnCompletedProofs.add(retries);
        }
        wrapsRetriesPerCompletedProof.update(retries);
        stageObservations.remove(constructionId);
    }

    /**
     * Clears in-memory transition tracking for a construction.
     *
     * @param constructionId the construction id
     */
    public void forgetConstruction(final long constructionId) {
        stageObservations.remove(constructionId);
    }

    private TransitionMetrics transitionMetricsFor(@NonNull final Stage from, @NonNull final Stage to) {
        final var transition = new StageTransition(requireNonNull(from), requireNonNull(to));
        return transitionMetrics.computeIfAbsent(transition, this::newTransitionMetrics);
    }

    private void registerEagerTransitionMetrics() {
        // Happy path transitions
        registerTransition(Stage.WAITING_FOR_METADATA, Stage.WAITING_FOR_ASSEMBLY);
        registerTransition(Stage.WAITING_FOR_ASSEMBLY, Stage.WRAPS_R1);
        registerTransition(Stage.WRAPS_R1, Stage.WRAPS_R2);
        registerTransition(Stage.WRAPS_R2, Stage.WRAPS_R3);
        registerTransition(Stage.WRAPS_R3, Stage.WRAPS_AGGREGATE);
        registerTransition(Stage.WRAPS_AGGREGATE, Stage.COMPLETED);

        // Failure transitions from each in-progress stage
        for (final var stage : List.of(
                Stage.WAITING_FOR_METADATA,
                Stage.WAITING_FOR_ASSEMBLY,
                Stage.WRAPS_R1,
                Stage.WRAPS_R2,
                Stage.WRAPS_R3,
                Stage.WRAPS_AGGREGATE)) {
            registerTransition(stage, Stage.FAILED);
        }
    }

    private void registerTransition(@NonNull final Stage from, @NonNull final Stage to) {
        transitionMetricsFor(requireNonNull(from), requireNonNull(to));
    }

    private TransitionMetrics newTransitionMetrics(@NonNull final StageTransition transition) {
        final var suffix = transition.from().metricToken + "To" + transition.to().metricToken;
        final var count = metrics.getOrCreate(new Counter.Config(CATEGORY, "historyProofTrans" + suffix + "Cnt")
                .withDescription("Number of history proof controller transitions from "
                        + transition.from().label
                        + " to "
                        + transition.to().label));
        final var averageConsensusMillis = metrics.getOrCreate(
                new RunningAverageMetric.Config(CATEGORY, "historyProofTrans" + suffix + "Ms")
                        .withDescription("Average consensus milliseconds elapsed while transitioning from "
                                + transition.from().label
                                + " to "
                                + transition.to().label)
                        .withUnit("ms")
                        .withFormat("%,13.3f"));
        return new TransitionMetrics(count, averageConsensusMillis);
    }

    private static long elapsedMillis(@NonNull final Instant start, @NonNull final Instant end) {
        return Math.max(
                0L, Duration.between(requireNonNull(start), requireNonNull(end)).toMillis());
    }

    private record StageObservation(Stage stage, Instant enteredAt) {}

    private record StageTransition(Stage from, Stage to) {}

    private record TransitionMetrics(Counter count, RunningAverageMetric averageConsensusMillis) {
        void update(final long elapsedMillis) {
            count.increment();
            averageConsensusMillis.update(elapsedMillis);
        }
    }
}
