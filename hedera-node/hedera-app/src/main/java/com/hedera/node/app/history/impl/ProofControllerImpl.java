// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.history.impl;

import static com.hedera.hapi.util.HapiUtils.asInstant;
import static com.hedera.node.app.history.HistoryService.isCompleted;
import static com.hedera.node.app.history.impl.ProofControllers.isWrapsExtensible;
import static java.util.Objects.requireNonNull;
import static java.util.stream.Collectors.groupingBy;
import static java.util.stream.Collectors.summingLong;
import static java.util.stream.Collectors.toMap;

import com.hedera.hapi.node.state.history.HistoryProof;
import com.hedera.hapi.node.state.history.HistoryProofConstruction;
import com.hedera.hapi.node.state.history.HistoryProofVote;
import com.hedera.hapi.node.state.history.ProofKey;
import com.hedera.hapi.node.state.history.WrapsSigningState;
import com.hedera.node.app.history.HistoryLibrary;
import com.hedera.node.app.history.HistoryService;
import com.hedera.node.app.history.ReadableHistoryStore.ProofKeyPublication;
import com.hedera.node.app.history.ReadableHistoryStore.WrapsMessagePublication;
import com.hedera.node.app.history.WritableHistoryStore;
import com.hedera.node.app.history.impl.ProofKeysAccessorImpl.SchnorrKeyPair;
import com.hedera.node.app.service.roster.impl.RosterTransitionWeights;
import com.hedera.node.config.data.TssConfig;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Default implementation of {@link ProofController}.
 */
public class ProofControllerImpl implements ProofController {
    private static final Logger log = LogManager.getLogger(ProofControllerImpl.class);

    public static final String PROOF_COMPLETE_MSG = "History proof constructed";

    private final long selfId;

    private final Executor executor;
    private final SchnorrKeyPair schnorrKeyPair;
    private final HistoryLibrary historyLibrary;
    private final HistoryService historyService;
    private final HistorySubmissions submissions;
    private final RosterTransitionWeights weights;
    private final HistoryProver.Factory proverFactory;
    private final HistoryProofMetrics historyProofMetrics;

    @Nullable
    private final HistoryProof sourceProof;

    private final Map<Long, HistoryProofVote> votes = new TreeMap<>();
    private final Map<Long, Bytes> targetProofKeys = new TreeMap<>();

    /**
     * The ongoing construction, updated in network state each time the controller makes progress.
     */
    private HistoryProofConstruction construction;

    /**
     * If not null, a future that resolves when this node publishes its Schnorr key.
     */
    @Nullable
    private CompletableFuture<Void> publicationFuture;

    /**
     * If not null, the prover responsible for the current construction.
     */
    @Nullable
    private HistoryProver prover;

    public ProofControllerImpl(
            final long selfId,
            @NonNull final SchnorrKeyPair schnorrKeyPair,
            @NonNull final HistoryProofConstruction construction,
            @NonNull final RosterTransitionWeights weights,
            @NonNull final Executor executor,
            @NonNull final HistorySubmissions submissions,
            @NonNull final WrapsMpcStateMachine machine,
            @NonNull final List<ProofKeyPublication> keyPublications,
            @NonNull final List<WrapsMessagePublication> wrapsMessagePublications,
            @NonNull final Map<Long, HistoryProofVote> votes,
            @NonNull final HistoryService historyService,
            @NonNull final HistoryLibrary historyLibrary,
            @NonNull final HistoryProver.Factory proverFactory,
            @Nullable final HistoryProof sourceProof,
            @NonNull final HistoryProofMetrics historyProofMetrics,
            @NonNull final TssConfig tssConfig) {
        requireNonNull(machine);
        requireNonNull(tssConfig);
        this.selfId = selfId;
        this.executor = requireNonNull(executor);
        this.submissions = requireNonNull(submissions);
        this.weights = requireNonNull(weights);
        this.construction = requireNonNull(construction);
        this.proverFactory = requireNonNull(proverFactory);
        this.sourceProof = sourceProof;
        this.historyProofMetrics = requireNonNull(historyProofMetrics);
        this.historyLibrary = requireNonNull(historyLibrary);
        this.historyService = requireNonNull(historyService);
        this.schnorrKeyPair = requireNonNull(schnorrKeyPair);
        this.votes.putAll(requireNonNull(votes));
        if (!construction.hasTargetProof()) {
            final var cutoffTime = construction.hasGracePeriodEndTime()
                    ? asInstant(construction.gracePeriodEndTimeOrThrow())
                    : Instant.MAX;
            keyPublications.forEach(publication -> {
                if (!publication.adoptionTime().isAfter(cutoffTime)) {
                    maybeUpdateForProofKey(publication);
                }
            });
            this.prover = createProver(tssConfig);
            wrapsMessagePublications.stream().sorted().forEach(publication -> requireNonNull(prover)
                    .replayWrapsSigningMessage(constructionId(), publication));
        }
    }

    @Override
    public long constructionId() {
        return construction.constructionId();
    }

    @Override
    public boolean isStillInProgress(@NonNull final TssConfig tssConfig) {
        requireNonNull(tssConfig);
        if (construction.hasFailureReason()) {
            return false;
        }
        return !isCompleted(construction, tssConfig);
    }

    @Override
    public void advanceConstruction(
            @NonNull final Instant now,
            @Nullable final Bytes metadata,
            @NonNull final WritableHistoryStore historyStore,
            final boolean isActive,
            @NonNull final TssConfig tssConfig) {
        requireNonNull(now);
        requireNonNull(historyStore);
        requireNonNull(tssConfig);
        historyProofMetrics.observeStage(constructionId(), currentStage(metadata), now);
        try {
            if (construction.hasFailureReason()) {
                construction = historyStore.getConstructionOrThrow(constructionId());
                if (construction.hasFailureReason()
                        && !retryIfRecoverableFailure(construction.failureReasonOrThrow(), historyStore, tssConfig)) {
                    return;
                }
            }
            if (!isStillInProgress(tssConfig)) {
                return;
            }
            // Still waiting for the hinTS verification key
            if (metadata == null) {
                if (isActive) {
                    ensureProofKeyPublished();
                }
                return;
            }
            // Have the hinTS verification key, but not yet assembling the history
            // or computing the WRAPS proof (genesis or incremental)
            if (!construction.hasTargetProof()
                    && !construction.hasAssemblyStartTime()
                    && !construction.hasWrapsSigningState()) {
                if (shouldAssemble(now)) {
                    log.info("Assembly start time for construction #{} is {}", construction.constructionId(), now);
                    construction = historyStore.setAssemblyTime(construction.constructionId(), now);
                } else if (isActive) {
                    ensureProofKeyPublished();
                }
                return;
            }
            // Cannot make progress on anything without an active network
            if (!isActive) {
                return;
            }
            final var outcome = requireNonNull(prover)
                    .advance(now, construction, metadata, targetProofKeys, tssConfig, historyStore.getLedgerId());
            switch (outcome) {
                case HistoryProver.Outcome.InProgress ignored ->
                    construction = historyStore.getConstructionOrThrow(constructionId());
                case HistoryProver.Outcome.Completed completed -> finishProof(historyStore, completed.proof(), now);
                case HistoryProver.Outcome.Failed failed -> {
                    if (!retryIfRecoverableFailure(failed.reason(), historyStore, tssConfig)) {
                        log.warn("Failed construction #{} due to {}", constructionId(), failed.reason());
                        construction = historyStore.failForReason(constructionId(), failed.reason());
                    }
                }
            }
        } finally {
            historyProofMetrics.observeStage(constructionId(), currentStage(metadata), now);
        }
    }

    @Override
    public void addProofKeyPublication(@NonNull final ProofKeyPublication publication) {
        requireNonNull(publication);
        // Once the assembly start time (or proof) is known, the proof keys are fixed
        if (!construction.hasGracePeriodEndTime()) {
            return;
        }
        maybeUpdateForProofKey(publication);
    }

    @Override
    public boolean addWrapsMessagePublication(
            @NonNull final WrapsMessagePublication publication,
            @NonNull final WritableHistoryStore writableHistoryStore) {
        requireNonNull(publication);
        requireNonNull(writableHistoryStore);
        if (construction.hasTargetProof()) {
            return false;
        }
        return requireNonNull(prover).addWrapsSigningMessage(constructionId(), publication, writableHistoryStore);
    }

    @Override
    public void addProofVote(
            final long nodeId,
            @NonNull final HistoryProofVote vote,
            @NonNull final Instant now,
            @NonNull final WritableHistoryStore historyStore) {
        requireNonNull(vote);
        requireNonNull(now);
        requireNonNull(historyStore);
        if (construction.hasTargetProof() || votes.containsKey(nodeId)) {
            return;
        }
        if (vote.hasProof()) {
            votes.put(nodeId, vote);
        } else if (vote.hasCongruentNodeId()) {
            final var congruentVote = votes.get(vote.congruentNodeIdOrThrow());
            if (congruentVote != null && congruentVote.hasProof()) {
                votes.put(nodeId, congruentVote);
            }
        }
        historyStore.addProofVote(nodeId, construction.constructionId(), vote);
        final var proofWeights = votes.entrySet().stream()
                .collect(groupingBy(
                        entry -> entry.getValue().proofOrThrow(),
                        summingLong(entry -> weights.sourceWeightOf(entry.getKey()))));
        final var maybeWinningProof = proofWeights.entrySet().stream()
                .filter(entry -> entry.getValue() >= weights.sourceWeightThreshold())
                .map(Map.Entry::getKey)
                .findFirst();
        maybeWinningProof.ifPresent(proof -> finishProof(historyStore, proof, now));
        // Let our prover know about the vote to optimize its choice of explicit or congruent voting
        requireNonNull(prover).observeProofVote(nodeId, vote, maybeWinningProof.isPresent());
    }

    @Override
    public void cancelPendingWork() {
        final var sb = new StringBuilder("Canceled work on proof construction #").append(construction.constructionId());
        boolean canceledSomething = false;
        if (publicationFuture != null && !publicationFuture.isDone()) {
            sb.append("\n  * In-flight publication");
            publicationFuture.cancel(true);
            canceledSomething = true;
        }
        if (prover != null && prover.cancelPendingWork()) {
            sb.append("\n  * In-flight prover work");
            canceledSomething = true;
        }
        if (canceledSomething) {
            log.info(sb.toString());
        }
        historyProofMetrics.forgetConstruction(constructionId());
    }

    /**
     * Finishes the active construction, commits its proof to state, and notifies the history service.
     * @param historyStore the writable history store
     * @param proof the proof
     * @param now the current consensus time
     */
    private void finishProof(
            @NonNull final WritableHistoryStore historyStore,
            @NonNull final HistoryProof proof,
            @NonNull final Instant now) {
        construction = historyStore.completeProof(construction.constructionId(), proof);
        historyProofMetrics.observeStage(constructionId(), HistoryProofMetrics.Stage.COMPLETED, now);
        historyProofMetrics.recordProofCompleted(constructionId(), construction.wrapsRetryCount());
        log.info(
                "{} (#{}, WRAPS-extensible? {})",
                PROOF_COMPLETE_MSG,
                construction.constructionId(),
                isWrapsExtensible(proof));
        historyService.onFinished(historyStore, construction, weights.targetNodeWeights());
    }

    /**
     * If the given failure reason is recoverable and retry budget remains, restarts WRAPS signing for this
     * construction and reinitializes in-memory prover state.
     *
     * @param reason the failure reason
     * @param historyStore the writable history store
     * @param tssConfig the TSS configuration
     * @return whether a retry was started
     */
    private boolean retryIfRecoverableFailure(
            @NonNull final String reason,
            @NonNull final WritableHistoryStore historyStore,
            @NonNull final TssConfig tssConfig) {
        requireNonNull(reason);
        requireNonNull(historyStore);
        requireNonNull(tssConfig);
        if (!WrapsHistoryProver.isRecoverableFailure(reason)) {
            return false;
        }
        final int maxWrapsRetries = tssConfig.maxWrapsRetries();
        if (construction.wrapsRetryCount() >= maxWrapsRetries) {
            log.warn(
                    "Construction #{} exhausted WRAPS retry budget ({}) after recoverable failure '{}'",
                    constructionId(),
                    maxWrapsRetries,
                    reason);
            return false;
        }
        if (prover != null) {
            prover.cancelPendingWork();
        }
        construction = historyStore.restartWrapsSigning(constructionId(), weights.sourceNodeIds());
        historyProofMetrics.recordRetryStarted();
        prover = createProver(tssConfig);
        log.warn(
                "Restarted WRAPS signing for construction #{} (retry {}/{}) after recoverable failure '{}'",
                constructionId(),
                construction.wrapsRetryCount(),
                maxWrapsRetries,
                reason);
        return true;
    }

    /**
     * Applies a deterministic policy to recommend an assembly behavior at the given time.
     *
     * @param now the current consensus time
     * @return the recommendation
     */
    private boolean shouldAssemble(@NonNull final Instant now) {
        // If every active node in the target roster has published a proof key,
        // assemble the new history now; there is nothing else to wait for
        if (targetProofKeys.size() == weights.numTargetNodesInSource()) {
            log.info("All target nodes have published proof keys for construction #{}", construction.constructionId());
            return true;
        }
        if (now.isBefore(asInstant(construction.gracePeriodEndTimeOrThrow()))) {
            return false;
        } else {
            return publishedWeight() >= weights.targetWeightThreshold();
        }
    }

    /**
     * Ensures this node has published its proof key.
     */
    private void ensureProofKeyPublished() {
        if (publicationFuture == null && weights.targetIncludes(selfId) && !targetProofKeys.containsKey(selfId)) {
            log.info("Publishing Schnorr key for construction #{}", construction.constructionId());
            publicationFuture = CompletableFuture.runAsync(
                            () -> submissions
                                    .submitProofKeyPublication(schnorrKeyPair.publicKey())
                                    .join(),
                            executor)
                    .exceptionally(e -> {
                        log.error("Error publishing proof key", e);
                        return null;
                    });
        }
    }

    /**
     * If the given publication was for a node in the target roster, updates the target proof keys.
     * @param publication the publication
     */
    private void maybeUpdateForProofKey(@NonNull final ProofKeyPublication publication) {
        final long nodeId = publication.nodeId();
        if (!weights.targetIncludes(nodeId)) {
            return;
        }
        targetProofKeys.put(nodeId, publication.proofKey());
    }

    /**
     * Returns the weight of the nodes in the target roster that have published their proof keys.
     */
    private long publishedWeight() {
        return targetProofKeys.keySet().stream()
                .mapToLong(weights::targetWeightOf)
                .sum();
    }

    private HistoryProver createProver(@NonNull final TssConfig tssConfig) {
        final Map<Long, Bytes> sourceProofKeys = sourceProof == null
                ? targetProofKeys
                : sourceProof.targetProofKeys().stream().collect(toMap(ProofKey::nodeId, ProofKey::key));
        return proverFactory.create(
                selfId,
                tssConfig,
                schnorrKeyPair,
                sourceProof,
                weights,
                sourceProofKeys,
                executor,
                historyLibrary,
                submissions);
    }

    private HistoryProofMetrics.Stage currentStage(@Nullable final Bytes metadata) {
        if (construction.hasTargetProof()) {
            return HistoryProofMetrics.Stage.COMPLETED;
        }
        if (construction.hasFailureReason()) {
            return HistoryProofMetrics.Stage.FAILED;
        }
        if (metadata == null) {
            return HistoryProofMetrics.Stage.WAITING_FOR_METADATA;
        }
        if (!construction.hasAssemblyStartTime() && !construction.hasWrapsSigningState()) {
            return HistoryProofMetrics.Stage.WAITING_FOR_ASSEMBLY;
        }
        final var phase =
                construction.wrapsSigningStateOrElse(WrapsSigningState.DEFAULT).phase();
        return switch (phase) {
            case R1 -> HistoryProofMetrics.Stage.WRAPS_R1;
            case R2 -> HistoryProofMetrics.Stage.WRAPS_R2;
            case R3 -> HistoryProofMetrics.Stage.WRAPS_R3;
            case AGGREGATE, POST_AGGREGATION, UNRECOGNIZED -> HistoryProofMetrics.Stage.WRAPS_AGGREGATE;
        };
    }
}
