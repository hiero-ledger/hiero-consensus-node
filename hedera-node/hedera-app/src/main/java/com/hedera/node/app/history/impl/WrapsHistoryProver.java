// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.history.impl;

import static com.hedera.hapi.node.state.history.WrapsPhase.AGGREGATE;
import static com.hedera.hapi.node.state.history.WrapsPhase.POST_AGGREGATION;
import static com.hedera.hapi.node.state.history.WrapsPhase.R1;
import static com.hedera.hapi.node.state.history.WrapsPhase.R2;
import static com.hedera.hapi.node.state.history.WrapsPhase.R3;
import static com.hedera.hapi.util.HapiUtils.asInstant;
import static com.hedera.node.app.hapi.utils.CommonUtils.noThrowSha384HashOf;
import static com.hedera.node.app.history.HistoryLibrary.MISSING_SCHNORR_KEY;
import static com.hedera.node.app.history.impl.ProofControllers.groundsChainOfTrust;
import static com.hedera.node.app.history.impl.ProofControllers.isWrapsExtensible;
import static com.hedera.node.app.history.impl.WrapsMpcStateMachine.POST_MPC_PHASES;
import static java.util.Collections.emptySortedMap;
import static java.util.Objects.requireNonNull;
import static java.util.concurrent.TimeUnit.MILLISECONDS;

import com.hedera.hapi.node.state.history.AggregatedNodeSignatures;
import com.hedera.hapi.node.state.history.ChainOfTrustProof;
import com.hedera.hapi.node.state.history.History;
import com.hedera.hapi.node.state.history.HistoryProof;
import com.hedera.hapi.node.state.history.HistoryProofConstruction;
import com.hedera.hapi.node.state.history.HistoryProofVote;
import com.hedera.hapi.node.state.history.WrapsPhase;
import com.hedera.hapi.node.state.history.WrapsSigningState;
import com.hedera.node.app.history.HistoryLibrary;
import com.hedera.node.app.history.HistoryLibrary.AddressBook;
import com.hedera.node.app.history.ReadableHistoryStore.WrapsMessagePublication;
import com.hedera.node.app.history.WritableHistoryStore;
import com.hedera.node.app.history.impl.ProofKeysAccessorImpl.SchnorrKeyPair;
import com.hedera.node.app.service.roster.impl.RosterTransitionWeights;
import com.hedera.node.config.data.TssConfig;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeSet;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * A {@link HistoryProver} that uses the WRAPS protocol to construct a {@link HistoryProof} that uses a
 * {@link ChainOfTrustProof#wrapsProof()} to establish chain of trust from the genesis address book hash.
 * The state machine moves first through a signing protocol that forms an aggregate signature from three
 * rounds of exchanging WRAPS messages; and then runs a heavy compression step to form a succinct proof.
 */
public class WrapsHistoryProver implements HistoryProver {
    private static final Logger log = LogManager.getLogger(WrapsHistoryProver.class);
    public static final String MISSING_MESSAGES_FAILURE_PREFIX = "Still missing messages from R1 nodes ";
    public static final String AGGREGATION_FAILURE_PREFIX = "WRAPS aggregation failed for R1 nodes ";
    public static final String R1_TIMEOUT_FAILURE_PREFIX =
            "R1 did not reach the signing threshold before the end of" + " its grace period, with messages from nodes ";
    public static final String WRAPS_NOT_READY_FAILURE_PREFIX = "WRAPS library is not ready";
    public static final String LEDGER_ID_NOT_READY_FAILURE_PREFIX = "Ledger id is not yet available";

    /**
     * The private key (the scalar 1) used to check other nodes' R1 and R2 messages; the check discards the output
     * of the round it runs, and a fixed key gives every node the same verdict.
     */
    private static final Bytes MESSAGE_CHECK_PRIVATE_KEY = Bytes.fromHex("01" + "00".repeat(31));

    private final long selfId;
    private final Duration wrapsMessageGracePeriod;
    private final SchnorrKeyPair schnorrKeyPair;
    private final Map<Long, Bytes> proofKeys;
    private final RosterTransitionWeights weights;
    private final Delayer delayer;
    private final Executor executor;

    @Nullable
    private final HistoryProof sourceProof;

    private final HistoryLibrary historyLibrary;
    private final HistorySubmissions submissions;
    private final WrapsMpcStateMachine machine;
    private final Object voteLock = new Object();

    private final Map<WrapsPhase, SortedMap<Long, WrapsMessagePublication>> phaseMessages =
            new EnumMap<>(WrapsPhase.class);
    private final Map<Long, Bytes> explicitHistoryProofHashes = new HashMap<>();

    /**
     * If non-null, the phase whose last publish returned a WRAPS-not-ready noop;
     * cleared at the next publishIfNeeded so the publish is retried.
     */
    @Nullable
    private volatile WrapsPhase phaseNeedingWrapsReadinessRetry;

    /**
     * Whether this construction extends {@link #sourceProof} by folding onto it, rather than grounding a
     * genesis proof. False at network genesis, and for any construction that grounds a fresh chain of trust
     * for the roster the network already has.
     */
    private volatile boolean foldsOntoSourceProof;

    /**
     * If not null, the WRAPS message being signed for the current construction.
     */
    @Nullable
    private byte[] wrapsMessage;

    /**
     * If not null, the target address book being added to the chain of trust.
     */
    @Nullable
    private AddressBook targetAddressBook;

    /**
     * If not null, the hash of the target address book.
     */
    @Nullable
    private byte[] targetAddressBookHash;

    /**
     * If non-null, the entropy used to generate the R1 message. (If this node rejoins the network
     * after a restart, having lost its entropy, it cannot continue and the protocol will time out.)
     */
    @Nullable
    private byte[] entropy;

    /**
     * If non-null, the verified aggregate signature over the R1 participants' messages.
     */
    @Nullable
    private byte[] aggregateSignature;

    /**
     * Whether this node has logged that it cannot sign because its key differs from its key in the source proof.
     */
    private boolean ownKeyMismatchLogged;

    /**
     * Future that resolves on submission of this node's R1 signing message.
     */
    @Nullable
    private CompletableFuture<Void> r1Future;

    /**
     * Future that resolves on submission of this node's R2 signing message.
     */
    @Nullable
    private CompletableFuture<Void> r2Future;

    /**
     * Future that resolves on submission of this node's R3 signing message.
     */
    @Nullable
    private CompletableFuture<Void> r3Future;

    /**
     * If non-null, the history proof we have constructed (recursive or otherwise).
     */
    @Nullable
    private HistoryProof historyProof;

    /**
     * Future that resolves on the completion of the proof vote decision post-jitter.
     */
    @Nullable
    private CompletableFuture<VoteDecision> voteDecisionFuture;

    /**
     * Future that resolves on submission of this node's vote for proof.
     */
    @Nullable
    private volatile CompletableFuture<Void> voteFuture;

    /**
     * The kind of proof work associated with {@link #voteFuture}; this is set while either computing or voting on it.
     */
    @Nullable
    private ProofKind voteFutureKind;

    /**
     * If non-null, a node whose explicit recursive proof has already been validated.
     */
    @Nullable
    private Long validRecursiveProofNodeId;

    private boolean nonRecursiveProofFinalized;
    private boolean recursiveProofFinalized;

    /**
     * The current WRAPS phase; starts with R1 and advances as messages are received.
     */
    private WrapsPhase wrapsPhase = R1;

    /**
     * Indicates this prover's construction has been canceled and any post-output work should be skipped.
     */
    private volatile boolean constructionCanceled = false;

    private sealed interface WrapsPhaseOutput
            permits NoopOutput, MessagePhaseOutput, ProofPhaseOutput, AggregatePhaseOutput {}

    private record NoopOutput(String reason) implements WrapsPhaseOutput {}

    private record MessagePhaseOutput(byte[] message) implements WrapsPhaseOutput {}

    private record AggregatePhaseOutput(byte[] signature, List<Long> nodeIds) implements WrapsPhaseOutput {}

    private record ProofPhaseOutput(byte[] compressed, byte[] uncompressed) implements WrapsPhaseOutput {
        @NonNull
        @Override
        public String toString() {
            return "WRAPS{compressed="
                    + compressed.length
                    + " bytes (" + Bytes.wrap(noThrowSha384HashOf(compressed)) + "), " + "uncompressed="
                    + uncompressed.length
                    + " bytes (" + Bytes.wrap(noThrowSha384HashOf(uncompressed)) + ")"
                    + "}";
        }
    }

    private enum VoteChoice {
        SUBMIT,
        SKIP
    }

    private enum ProofKind {
        NON_RECURSIVE,
        RECURSIVE
    }

    private record VoteDecision(VoteChoice choice, @Nullable Long congruentNodeId) {
        static VoteDecision explicit() {
            return new VoteDecision(VoteChoice.SUBMIT, null);
        }

        static VoteDecision skip() {
            return new VoteDecision(VoteChoice.SKIP, null);
        }

        static VoteDecision congruent(long nodeId) {
            return new VoteDecision(VoteChoice.SUBMIT, nodeId);
        }
    }

    public interface Delayer {
        @NonNull
        Executor delayedExecutor(long delay, @NonNull TimeUnit unit, @NonNull Executor executor);
    }

    public WrapsHistoryProver(
            final long selfId,
            @NonNull final Duration wrapsMessageGracePeriod,
            @NonNull final SchnorrKeyPair schnorrKeyPair,
            @Nullable final HistoryProof sourceProof,
            @NonNull final RosterTransitionWeights weights,
            @NonNull final Map<Long, Bytes> proofKeys,
            @NonNull final Delayer delayer,
            @NonNull final Executor executor,
            @NonNull final HistoryLibrary historyLibrary,
            @NonNull final HistorySubmissions submissions,
            @NonNull final WrapsMpcStateMachine machine) {
        this.selfId = selfId;
        this.sourceProof = sourceProof;
        this.wrapsMessageGracePeriod = requireNonNull(wrapsMessageGracePeriod);
        this.schnorrKeyPair = requireNonNull(schnorrKeyPair);
        this.weights = requireNonNull(weights);
        this.proofKeys = requireNonNull(proofKeys);
        this.delayer = requireNonNull(delayer);
        this.executor = requireNonNull(executor);
        this.historyLibrary = requireNonNull(historyLibrary);
        this.submissions = requireNonNull(submissions);
        this.machine = requireNonNull(machine);
    }

    @NonNull
    @Override
    public Outcome advance(
            @NonNull final Instant now,
            @NonNull final HistoryProofConstruction construction,
            @NonNull final Bytes targetMetadata,
            @NonNull final Map<Long, Bytes> targetProofKeys,
            @NonNull final TssConfig tssConfig,
            @Nullable final Bytes ledgerId,
            final boolean canSubmit) {
        requireNonNull(now);
        requireNonNull(construction);
        requireNonNull(targetMetadata);
        requireNonNull(targetProofKeys);
        requireNonNull(tssConfig);
        if (ledgerId == null && sourceProof != null) {
            return new Outcome.Failed("Only genesis WRAPS proofs are allowed to not have a ledger id");
        }
        // A construction with the same roster as source and target grounds a chain of trust, even when there
        // is a source proof it could fold onto; that is how a fresh genesis proof replaces the active one
        foldsOntoSourceProof =
                tssConfig.wrapsEnabled() && isWrapsExtensible(sourceProof) && !groundsChainOfTrust(construction);
        final var state = construction.wrapsSigningStateOrElse(WrapsSigningState.DEFAULT);
        if (state.phase() != AGGREGATE
                && state.hasGracePeriodEndTime()
                && now.isAfter(asInstant(state.gracePeriodEndTimeOrThrow()))) {
            if (state.phase() == R1) {
                return new Outcome.Failed(R1_TIMEOUT_FAILURE_PREFIX
                        + phaseMessages.getOrDefault(R1, emptySortedMap()).keySet());
            }
            final var submittingNodes =
                    phaseMessages.getOrDefault(state.phase(), emptySortedMap()).keySet();
            // If we reached a stage with a grace period, we must have at least one R1 message, so no getOrDefault()
            final var missingNodes = phaseMessages.get(R1).keySet().stream()
                    .filter(nodeId -> !submittingNodes.contains(nodeId))
                    .toList();
            return new Outcome.Failed(
                    MISSING_MESSAGES_FAILURE_PREFIX + missingNodes + " after end of grace period for phase "
                            + state.phase(),
                    Set.copyOf(missingNodes));
        } else {
            // Every node must agree on whether the aggregate is usable, so this check cannot wait for canSubmit
            if (state.phase() == AGGREGATE && aggregateSignature == null) {
                ensureWrapsMessage(targetProofKeys, targetMetadata);
                aggregateSignature = verifiedAggregateSignature();
                if (aggregateSignature == null) {
                    return new Outcome.Failed(
                            AGGREGATION_FAILURE_PREFIX + phaseMessages.get(R1).keySet());
                }
            }
            if (!canSubmit) {
                return Outcome.InProgress.INSTANCE;
            }
            ensureWrapsMessage(targetProofKeys, targetMetadata);
            final var effectivePhase = construction.hasTargetProof() ? POST_AGGREGATION : state.phase();
            publishIfNeeded(
                    construction.constructionId(),
                    effectivePhase,
                    targetMetadata,
                    targetProofKeys,
                    tssConfig,
                    ledgerId,
                    construction.targetProof());
        }
        return Outcome.InProgress.INSTANCE;
    }

    public static boolean isRecoverableFailure(@NonNull final String reason) {
        requireNonNull(reason);
        return reason.startsWith(MISSING_MESSAGES_FAILURE_PREFIX)
                || reason.startsWith(AGGREGATION_FAILURE_PREFIX)
                || reason.startsWith(R1_TIMEOUT_FAILURE_PREFIX);
    }

    @Override
    public boolean addWrapsSigningMessage(
            final long constructionId,
            @NonNull final WrapsMessagePublication publication,
            @NonNull final WritableHistoryStore writableHistoryStore) {
        requireNonNull(publication);
        requireNonNull(writableHistoryStore);
        return receiveWrapsSigningMessage(constructionId, publication, writableHistoryStore);
    }

    @Override
    public void replayWrapsSigningMessage(long constructionId, @NonNull WrapsMessagePublication publication) {
        receiveWrapsSigningMessage(constructionId, publication, null);
    }

    @Override
    public void observeProofVote(
            final long nodeId,
            @NonNull final HistoryProofVote vote,
            final boolean proofFinalized,
            @NonNull final ProofVoteCategory proofVoteCategory) {
        requireNonNull(vote);
        requireNonNull(proofVoteCategory);
        if (proofFinalized) {
            log.info("Observed finalized proof via node{}; skipping vote", nodeId);
            final var proofKind = proofKindOf(proofVoteCategory);
            final CompletableFuture<VoteDecision> decisionFuture;
            synchronized (voteLock) {
                markFinalized(proofKind);
                decisionFuture = voteFutureKind == null || voteFutureKind == proofKind ? voteDecisionFuture : null;
                if (decisionFuture != null) {
                    voteDecisionFuture = null;
                }
                if (voteFutureKind == proofKind) {
                    voteFuture = null;
                    voteFutureKind = null;
                }
            }
            if (decisionFuture != null) {
                decisionFuture.complete(VoteDecision.skip());
            }
            return;
        }
        // Explicit vote case
        if (vote.hasProof()) {
            final var proof = vote.proofOrElse(HistoryProof.DEFAULT);
            switch (proofVoteCategory) {
                case NOT_RECURSIVE -> {
                    // Always store a hash – useful if we haven't finished our own proof yet
                    final var hash = hashOf(proof);
                    final CompletableFuture<VoteDecision> decisionFuture;
                    synchronized (voteLock) {
                        explicitHistoryProofHashes.put(nodeId, hash);
                        // If we already have our proof, see if it matches; save a few bytes by using congruent vote
                        decisionFuture = (voteFutureKind == null || voteFutureKind == ProofKind.NON_RECURSIVE)
                                        && historyProof != null
                                        && selfProofHashOrThrow().equals(hash)
                                ? voteDecisionFuture
                                : null;
                    }
                    if (decisionFuture != null && decisionFuture.complete(VoteDecision.congruent(nodeId))) {
                        log.info("Observed matching explicit proof from node{}; voting congruent instead", nodeId);
                    }
                }
                case VALID_RECURSIVE -> {
                    // This is the big win, avoiding an explicit vote for the megabyte-scale WRAPS proof
                    final CompletableFuture<VoteDecision> decisionFuture;
                    synchronized (voteLock) {
                        validRecursiveProofNodeId = nodeId;
                        decisionFuture = voteFutureKind == null || voteFutureKind == ProofKind.RECURSIVE
                                ? voteDecisionFuture
                                : null;
                    }
                    if (decisionFuture != null && decisionFuture.complete(VoteDecision.congruent(nodeId))) {
                        log.info(
                                "Observed valid explicit recursive proof from node{}; voting congruent instead",
                                nodeId);
                    }
                }
                case INVALID_RECURSIVE -> {
                    // No-op, an invalid proof obviously has no use for us
                }
            }
        }
    }

    @Override
    public boolean cancelPendingWork() {
        constructionCanceled = true;
        final var sb = new StringBuilder("Canceled work on WRAPS prover");
        boolean canceledSomething = false;
        if (r1Future != null && !r1Future.isDone()) {
            sb.append("\n  * In-flight R1 future");
            r1Future.cancel(true);
            canceledSomething = true;
        }
        if (r2Future != null && !r2Future.isDone()) {
            sb.append("\n  * In-flight R2 future");
            r2Future.cancel(true);
            canceledSomething = true;
        }
        if (r3Future != null && !r3Future.isDone()) {
            sb.append("\n  * In-flight R3 future");
            r3Future.cancel(true);
            canceledSomething = true;
        }
        if (voteFuture != null && !voteFuture.isDone()) {
            sb.append("\n  * In-flight vote future");
            voteFuture.cancel(true);
            canceledSomething = true;
        }
        if (canceledSomething) {
            log.info(sb.toString());
        }
        return canceledSomething;
    }

    private boolean receiveWrapsSigningMessage(
            final long constructionId,
            @NonNull final WrapsMessagePublication publication,
            @Nullable final WritableHistoryStore writableHistoryStore) {
        // Only source nodes sign; and if a node did not publish its Schnorr key in time to make it into
        // the source roster, we ignore any WRAPS message it publishes later after coming online
        if (!weights.sourceNodeWeights().containsKey(publication.nodeId())
                || MISSING_SCHNORR_KEY.equals(proofKeys.getOrDefault(publication.nodeId(), MISSING_SCHNORR_KEY))) {
            return false;
        }
        // A replayed message was already checked when it was first accepted
        if (writableHistoryStore != null && !isUsableInNextRound(publication)) {
            log.warn(
                    "Rejected unusable {} message from node{} for construction #{}, ignoring its later messages",
                    publication.phase(),
                    publication.nodeId(),
                    constructionId);
            // Excluding the sender in state stops every node from checking its repeats
            writableHistoryStore.excludeFromWrapsSigning(constructionId, Set.of(publication.nodeId()));
            return false;
        }
        final var transition = machine.onNext(publication, wrapsPhase, weights, wrapsMessageGracePeriod, phaseMessages);
        log.info(
                "Received {} message from node{} for construction #{} in phase={}) -> {} (new phase={})",
                publication.phase(),
                publication.nodeId(),
                constructionId,
                wrapsPhase,
                transition.publicationAccepted() ? "accepted" : "rejected",
                transition.newCurrentPhase());
        if (transition.publicationAccepted()) {
            if (transition.newCurrentPhase() != wrapsPhase) {
                wrapsPhase = transition.newCurrentPhase();
                log.info("Advanced to {} for construction #{}", wrapsPhase, constructionId);
                if (writableHistoryStore != null) {
                    writableHistoryStore.advanceWrapsSigningPhase(
                            constructionId, wrapsPhase, transition.gracePeriodEndTimeUpdate());
                }
            }
            return true;
        }
        return false;
    }

    /**
     * Returns whether honest nodes can use the given message when they compute their next round. Every node's R2
     * reads all R1 messages, and every node's R3 checks each R2 message against its sender's R1; so one unusable
     * message would keep every honest node from publishing. The check runs the round that consumes the message for
     * its sender alone, using entropy and a key that serve no other purpose.
     *
     * @param publication the publication to check
     * @return whether the message is usable, or will be rejected anyway
     */
    private boolean isUsableInNextRound(@NonNull final WrapsMessagePublication publication) {
        final var phase = publication.phase();
        final long nodeId = publication.nodeId();
        if (phase != wrapsPhase
                || phaseMessages.getOrDefault(phase, emptySortedMap()).containsKey(nodeId)) {
            return true;
        }
        final var message = new byte[][] {publication.message().toByteArray()};
        final var senderBook = new AddressBook(
                new long[] {1L}, new byte[][] {proofKeys.get(nodeId).toByteArray()}, new long[] {nodeId});
        return switch (phase) {
            case R1 ->
                historyLibrary.runWrapsPhaseR2(
                                new byte[32],
                                new byte[0],
                                message,
                                MESSAGE_CHECK_PRIVATE_KEY.toByteArray(),
                                senderBook,
                                Set.of(nodeId))
                        != null;
            case R2 -> {
                final var r1 = phaseMessages.get(R1).get(nodeId);
                yield r1 == null
                        || historyLibrary.runWrapsPhaseR3(
                                        new byte[32],
                                        new byte[0],
                                        new byte[][] {r1.message().toByteArray()},
                                        message,
                                        MESSAGE_CHECK_PRIVATE_KEY.toByteArray(),
                                        senderBook,
                                        Set.of(nodeId))
                                != null;
            }
            default -> true;
        };
    }

    /**
     * Computes the message the source nodes sign, if it is not already known.
     */
    private void ensureWrapsMessage(
            @NonNull final Map<Long, Bytes> targetProofKeys, @NonNull final Bytes targetMetadata) {
        if (wrapsMessage == null) {
            // Avoid caching a partial derived state if one of these computations throws.
            final var computedTargetAddressBook =
                    AddressBook.from(weights.targetNodeWeights(), nodeId -> targetProofKeys
                            .getOrDefault(nodeId, MISSING_SCHNORR_KEY)
                            .toByteArray());
            final var computedWrapsMessage =
                    historyLibrary.computeWrapsMessage(computedTargetAddressBook, targetMetadata.toByteArray());
            final var computedTargetAddressBookHash = historyLibrary.hashAddressBook(computedTargetAddressBook);
            targetAddressBook = computedTargetAddressBook;
            wrapsMessage = computedWrapsMessage;
            targetAddressBookHash = computedTargetAddressBookHash;
        }
    }

    /**
     * Returns the aggregate signature over the R1 participants' messages, or null if they do not aggregate to a
     * valid signature.
     */
    private @Nullable byte[] verifiedAggregateSignature() {
        final var message = requireNonNull(wrapsMessage);
        final var sourceBook = sourceBook();
        final var signature = historyLibrary.runAggregationPhase(
                message,
                rawMessagesFor(R1),
                rawMessagesFor(R2),
                rawMessagesFor(R3),
                sourceBook,
                phaseMessages.get(R1).keySet());
        return signature != null
                        && historyLibrary.verifyAggregateSignature(
                                message, sourceBook.nodeIds(), sourceBook.publicKeys(), sourceBook.weights(), signature)
                ? signature
                : null;
    }

    private AddressBook sourceBook() {
        return AddressBook.from(
                weights.sourceNodeWeights(),
                nodeId -> proofKeys.getOrDefault(nodeId, MISSING_SCHNORR_KEY).toByteArray());
    }

    /**
     * Ensures this node has published its WRAPS message or aggregate signature vote.
     */
    private void publishIfNeeded(
            final long constructionId,
            @NonNull final WrapsPhase phase,
            @NonNull final Bytes targetMetadata,
            @NonNull final Map<Long, Bytes> targetProofKeys,
            @NonNull final TssConfig tssConfig,
            @Nullable final Bytes ledgerId,
            @Nullable final HistoryProof aggregatedSignatureProof) {
        if (shouldSkipAfterCancellation(constructionId, phase)) {
            return;
        }
        // Other nodes verify this node's signing messages with its key in the source proof, so signing with any
        // other key could only keep the R1 participants' messages from aggregating
        if (phase == R1 && !schnorrKeyPair.publicKey().equals(proofKeys.get(selfId))) {
            if (proofKeys.containsKey(selfId) && !ownKeyMismatchLogged) {
                log.error(
                        "Not signing construction #{}, since this node's Schnorr key {} is not its key {} in the"
                                + " source proof",
                        constructionId,
                        schnorrKeyPair.publicKey(),
                        proofKeys.get(selfId));
                ownKeyMismatchLogged = true;
            }
            return;
        }
        final boolean isWrapsReadinessRetry = phase == phaseNeedingWrapsReadinessRetry;
        if (isWrapsReadinessRetry) {
            consumerOf(phase).accept(null);
            phaseNeedingWrapsReadinessRetry = null;
        }
        // Skip building sourceBook/proofKeyList/chained futures while the WRAPS library is still loading.
        final boolean needsWrapsForOutput = phase == POST_AGGREGATION || (phase == AGGREGATE && foldsOntoSourceProof);
        // The genesis proof also needs the ledger id, which is not established at the instant the library
        // becomes ready; without this the phase proceeds and dereferences a null ledgerId.
        final String notReadyReason;
        if (!needsWrapsForOutput) {
            notReadyReason = null;
        } else if (!historyLibrary.wrapsProverReady(tssConfig.wrapsProvingKeyHash())) {
            notReadyReason = "WRAPS library is not ready";
        } else if (phase == POST_AGGREGATION && ledgerId == null) {
            notReadyReason = "ledger id is not yet available";
        } else {
            notReadyReason = null;
        }
        if (notReadyReason != null) {
            if (isWrapsReadinessRetry) {
                log.debug("Deferring {} output for construction #{}: {}", phase, constructionId, notReadyReason);
            } else {
                log.info(
                        "Deferring {} output for construction #{}: {} (will retry each consensus round until ready)",
                        phase,
                        constructionId,
                        notReadyReason);
            }
            phaseNeedingWrapsReadinessRetry = phase;
            return;
        }
        if (futureOf(phase) == null
                && (POST_MPC_PHASES.contains(phase)
                        || !phaseMessages.getOrDefault(phase, emptySortedMap()).containsKey(selfId))) {
            if (isWrapsReadinessRetry) {
                log.debug(
                        "Re-attempting publication of {} output on construction #{} after a WRAPS-not-ready noop",
                        phase,
                        constructionId);
            } else if (phase == POST_AGGREGATION) {
                log.info("Considering publication of vote for genesis WRAPS proof on construction #{}", constructionId);
            } else {
                log.info("Considering publication of WRAPS {} output on construction #{}", phase, constructionId);
            }
            final var sourceBook = sourceBook();
            final var targetBook = requireNonNull(targetAddressBook);
            final var targetBookHash = requireNonNull(targetAddressBookHash);
            final var proofKeyList = proofKeyListFrom(targetProofKeys);
            consumerOf(phase)
                    .accept(outputFuture(
                                    phase,
                                    tssConfig,
                                    ledgerId,
                                    sourceBook,
                                    targetBook,
                                    targetMetadata,
                                    aggregatedSignatureProof)
                            .thenAcceptAsync(
                                    output -> {
                                        if (output == null) {
                                            if (phase == R1 || POST_MPC_PHASES.contains(phase)) {
                                                log.warn("Got null output for {} phase, skipping publication", phase);
                                            }
                                            return;
                                        }
                                        if (shouldSkipAfterCancellation(constructionId, phase)) {
                                            return;
                                        }
                                        switch (output) {
                                            case MessagePhaseOutput messageOutput -> {
                                                if (shouldSkipAfterCancellation(constructionId, phase)) {
                                                    return;
                                                }
                                                final var wrapsMessage = Bytes.wrap(messageOutput.message());
                                                submissions
                                                        .submitWrapsSigningMessage(phase, wrapsMessage, constructionId)
                                                        .join();
                                            }
                                            case AggregatePhaseOutput aggregatePhaseOutput -> {
                                                // We are doing a non-recursive proof via an aggregate signature
                                                final var aggregatedNodeSignatures = new AggregatedNodeSignatures(
                                                        Bytes.wrap(aggregatePhaseOutput.signature()),
                                                        new ArrayList<>(phaseMessages
                                                                .get(R1)
                                                                .keySet()),
                                                        targetMetadata);
                                                final var proof = HistoryProof.newBuilder()
                                                        .targetProofKeys(proofKeyList)
                                                        .targetHistory(
                                                                new History(Bytes.wrap(targetBookHash), targetMetadata))
                                                        .chainOfTrustProof(ChainOfTrustProof.newBuilder()
                                                                .aggregatedNodeSignatures(aggregatedNodeSignatures))
                                                        .build();
                                                scheduleVoteWithJitter(constructionId, tssConfig, proof);
                                            }
                                            case ProofPhaseOutput proofOutput -> {
                                                // We have a WRAPS proof
                                                final var recursiveProof = Bytes.wrap(proofOutput.compressed());
                                                final var uncompressedProof = Bytes.wrap(proofOutput.uncompressed());
                                                final var proof = HistoryProof.newBuilder()
                                                        .targetProofKeys(proofKeyList)
                                                        .targetHistory(
                                                                new History(Bytes.wrap(targetBookHash), targetMetadata))
                                                        .chainOfTrustProof(ChainOfTrustProof.newBuilder()
                                                                .wrapsProof(recursiveProof))
                                                        .uncompressedWrapsProof(uncompressedProof)
                                                        .build();
                                                scheduleVoteWithJitter(constructionId, tssConfig, proof);
                                            }
                                            case NoopOutput noopOutput -> {
                                                if (WRAPS_NOT_READY_FAILURE_PREFIX.equals(noopOutput.reason())
                                                        || LEDGER_ID_NOT_READY_FAILURE_PREFIX.equals(
                                                                noopOutput.reason())) {
                                                    // Flag instead of clearing voteFuture inline; the outer accept()
                                                    // hasn't returned yet.
                                                    log.debug(
                                                            "Deferring {} output: {} (will retry next round)",
                                                            phase,
                                                            noopOutput.reason());
                                                    phaseNeedingWrapsReadinessRetry = phase;
                                                } else {
                                                    log.info(
                                                            "Skipping publication of {} output: {}",
                                                            phase,
                                                            noopOutput.reason());
                                                }
                                            }
                                        }
                                    },
                                    executor)
                            .exceptionally(e -> {
                                log.error(
                                        "Failed to publish WRAPS {} message for construction #{}",
                                        phase,
                                        constructionId,
                                        e);
                                return null;
                            }));
        }
    }

    private void scheduleVoteWithJitter(
            final long constructionId, @NonNull final TssConfig tssConfig, @NonNull final HistoryProof proof) {
        if (constructionCanceled) {
            log.info("Skipping vote scheduling on canceled construction #{}", constructionId);
            return;
        }
        final var proofKind = proofKindOf(proof);
        final var selfProofHash = hashOf(proof);
        final var decisionFuture = new CompletableFuture<VoteDecision>();
        final var submissionFuture = decisionFuture.thenCompose(decision -> switch (decision.choice()) {
            case SKIP -> CompletableFuture.completedFuture(null);
            case SUBMIT -> {
                final var congruentNodeId = decision.congruentNodeId();
                if (congruentNodeId != null) {
                    log.info(
                            "Submitting congruent vote to node{} for construction #{}",
                            congruentNodeId,
                            constructionId);
                    yield submissions.submitCongruentProofVote(constructionId, congruentNodeId);
                } else {
                    log.info("Submitting explicit proof vote for construction #{}", constructionId);
                    yield submissions.submitExplicitProofVote(constructionId, proof);
                }
            }
        });
        final VoteDecision immediateDecision;
        synchronized (voteLock) {
            if (constructionCanceled || isFinalized(proofKind)) {
                log.info(
                        "Skipping {} proof vote scheduling on finalized construction #{}",
                        proofKind == ProofKind.RECURSIVE ? "recursive" : "non-recursive",
                        constructionId);
                return;
            }
            this.historyProof = proof;
            immediateDecision = immediateDecisionFor(proofKind, selfProofHash);
            voteDecisionFuture = decisionFuture;
            voteFuture = submissionFuture;
            voteFutureKind = proofKind;
        }
        if (immediateDecision != null) {
            if (immediateDecision.congruentNodeId() != null) {
                log.info(
                        "Already observed usable explicit proof from node{}; voting congruent immediately",
                        immediateDecision.congruentNodeId());
            }
            decisionFuture.complete(immediateDecision);
            return;
        }

        final long jitterMs = computeJitterMs(tssConfig, constructionId);
        final var delayed = delayer.delayedExecutor(jitterMs, MILLISECONDS, executor);

        // If this is the first thread to complete the vote decision, we submit an explicit vote
        CompletableFuture.runAsync(() -> decisionFuture.complete(VoteDecision.explicit()), delayed);
    }

    @Nullable
    private VoteDecision immediateDecisionFor(@NonNull final ProofKind proofKind, @NonNull final Bytes selfProofHash) {
        if (proofKind == ProofKind.RECURSIVE) {
            return validRecursiveProofNodeId == null ? null : VoteDecision.congruent(validRecursiveProofNodeId);
        }
        for (final var entry : explicitHistoryProofHashes.entrySet()) {
            if (selfProofHash.equals(entry.getValue())) {
                return VoteDecision.congruent(entry.getKey());
            }
        }
        return null;
    }

    private boolean isFinalized(@NonNull final ProofKind proofKind) {
        return proofKind == ProofKind.RECURSIVE ? recursiveProofFinalized : nonRecursiveProofFinalized;
    }

    private void markFinalized(@NonNull final ProofKind proofKind) {
        if (proofKind == ProofKind.RECURSIVE) {
            recursiveProofFinalized = true;
        } else {
            nonRecursiveProofFinalized = true;
        }
    }

    private boolean shouldSkipAfterCancellation(final long constructionId, @NonNull final WrapsPhase phase) {
        if (constructionCanceled) {
            log.info("Skipping post-output work for WRAPS {} on canceled construction #{}", phase, constructionId);
            return true;
        }
        return false;
    }

    private long computeJitterMs(@NonNull final TssConfig tssConfig, final long constructionId) {
        final var allNodes = new ArrayList<>(weights.targetNodeWeights().keySet());
        final int n = allNodes.size();
        final int selfIndex = allNodes.indexOf(selfId);
        final int leaderIndex = Math.floorMod((int) constructionId, n);
        final int rank = Math.floorMod(selfIndex - leaderIndex, n);
        return tssConfig.wrapsVoteJitterPerRank().toMillis() * rank;
    }

    private CompletableFuture<WrapsPhaseOutput> outputFuture(
            @NonNull final WrapsPhase phase,
            @NonNull final TssConfig tssConfig,
            @Nullable final Bytes ledgerId,
            @NonNull final AddressBook sourceBook,
            @NonNull final AddressBook targetBook,
            @NonNull final Bytes targetMetadata,
            @Nullable final HistoryProof aggregatedSignatureProof) {
        final var message = requireNonNull(wrapsMessage);
        final var verifiedSignature = aggregateSignature;
        return CompletableFuture.supplyAsync(
                () -> switch (phase) {
                    case UNRECOGNIZED -> throw new IllegalArgumentException("Unrecognized phase");
                    case R1 -> {
                        if (entropy == null) {
                            entropy = new byte[32];
                            new SecureRandom().nextBytes(entropy);
                            yield new MessagePhaseOutput(historyLibrary.runWrapsPhaseR1(
                                    entropy,
                                    message,
                                    schnorrKeyPair.privateKey().toByteArray()));
                        }
                        yield null;
                    }
                    case R2 -> {
                        if (entropy != null && phaseMessages.get(R1).containsKey(selfId)) {
                            yield new MessagePhaseOutput(historyLibrary.runWrapsPhaseR2(
                                    entropy,
                                    message,
                                    rawMessagesFor(R1),
                                    schnorrKeyPair.privateKey().toByteArray(),
                                    sourceBook,
                                    phaseMessages.get(R1).keySet()));
                        }
                        yield null;
                    }
                    case R3 -> {
                        if (entropy != null && phaseMessages.get(R1).containsKey(selfId)) {
                            yield new MessagePhaseOutput(historyLibrary.runWrapsPhaseR3(
                                    entropy,
                                    message,
                                    rawMessagesFor(R1),
                                    rawMessagesFor(R2),
                                    schnorrKeyPair.privateKey().toByteArray(),
                                    sourceBook,
                                    phaseMessages.get(R1).keySet()));
                        }
                        yield null;
                    }
                    case AGGREGATE -> {
                        final var signers = phaseMessages.get(R1).keySet();
                        // advance() verified this signature before publishing any AGGREGATE output
                        final var signature = requireNonNull(verifiedSignature);
                        // Sans a proof to fold onto, we are grounding a chain of trust and need an
                        // aggregate signature proof right away
                        if (!foldsOntoSourceProof) {
                            yield new AggregatePhaseOutput(
                                    signature, signers.stream().toList());
                        } else {
                            final var foldedProof = requireNonNull(sourceProof);
                            if (!historyLibrary.wrapsProverReady(tssConfig.wrapsProvingKeyHash())) {
                                yield new NoopOutput(WRAPS_NOT_READY_FAILURE_PREFIX);
                            }
                            final long now = System.nanoTime();
                            log.info(
                                    """
                                            Constructing incremental WRAPS proof with:
                                              ledgerId={}
                                              sourceBook={}
                                              sourceProofHash={}
                                              targetMetadata={}
                                              aggregateSignature={}
                                              signers={}
                                              targetBook={}
                                            """,
                                    ledgerId,
                                    sourceBook,
                                    noThrowSha384HashOf(foldedProof.uncompressedWrapsProof()),
                                    targetMetadata,
                                    Bytes.wrap(signature),
                                    signers,
                                    targetBook);
                            final var proof = historyLibrary.constructIncrementalWrapsProof(
                                    requireNonNull(ledgerId).toByteArray(),
                                    foldedProof.uncompressedWrapsProof().toByteArray(),
                                    sourceBook,
                                    targetBook,
                                    targetMetadata.toByteArray(),
                                    signature,
                                    signers);
                            if (proof == null) {
                                yield new NoopOutput("Incremental WRAPS proof construction returned null");
                            }
                            final var output = new ProofPhaseOutput(proof.compressed(), proof.uncompressed());
                            logElapsed(
                                    constructionCanceled
                                            ? "constructing canceled incremental WRAPS proof"
                                            : "constructing incremental WRAPS proof -> " + output,
                                    now);
                            yield output;
                        }
                    }
                    case POST_AGGREGATION -> {
                        if (!historyLibrary.wrapsProverReady(tssConfig.wrapsProvingKeyHash())) {
                            yield new NoopOutput(WRAPS_NOT_READY_FAILURE_PREFIX);
                        }
                        if (ledgerId == null) {
                            yield new NoopOutput(LEDGER_ID_NOT_READY_FAILURE_PREFIX);
                        }
                        final var signature = requireNonNull(aggregatedSignatureProof)
                                .chainOfTrustProofOrThrow()
                                .aggregatedNodeSignaturesOrThrow()
                                .aggregatedSignature()
                                .toByteArray();
                        final var signers = new TreeSet<>(aggregatedSignatureProof
                                .chainOfTrustProofOrThrow()
                                .aggregatedNodeSignaturesOrThrow()
                                .signingNodeIds());
                        final long now = System.nanoTime();
                        // The library rejects any anchor but the hash of the book the proof is grounded in;
                        // this is the ledger id the preceding aggregate signature proof established
                        final var genesisAddressBookHash = requireNonNull(targetAddressBookHash);
                        log.info(
                                """
                                        Constructing genesis WRAPS proof with:
                                          ledgerId={}
                                          genesisAddressBookHash={}
                                          targetMetadata={}
                                          aggregateSignature={}
                                          signers={}
                                          targetBook={}
                                        """,
                                ledgerId,
                                Bytes.wrap(genesisAddressBookHash),
                                targetMetadata,
                                Bytes.wrap(signature),
                                signers,
                                targetBook);
                        final var proof = historyLibrary.constructGenesisWrapsProof(
                                genesisAddressBookHash, targetMetadata.toByteArray(), signature, signers, targetBook);
                        if (proof == null) {
                            yield new NoopOutput("Genesis WRAPS proof construction returned null");
                        }
                        final var output = new ProofPhaseOutput(proof.compressed(), proof.uncompressed());
                        logElapsed("constructing genesis WRAPS proof -> " + output, now);
                        yield output;
                    }
                },
                executor);
    }

    private void logElapsed(@NonNull final String event, final long startNs) {
        final var duration = Duration.ofNanos(System.nanoTime() - startNs);
        log.info("FINISHED {} - took {}m {}s", event, duration.toMinutes(), duration.toSecondsPart());
    }

    private byte[][] rawMessagesFor(@NonNull final WrapsPhase phase) {
        return phaseMessages.get(phase).values().stream()
                .map(WrapsMessagePublication::message)
                .map(Bytes::toByteArray)
                .toArray(byte[][]::new);
    }

    private CompletableFuture<Void> futureOf(@NonNull final WrapsPhase phase) {
        return switch (phase) {
            case UNRECOGNIZED -> throw new IllegalArgumentException("Unrecognized phase");
            case R1 -> r1Future;
            case R2 -> r2Future;
            case R3 -> r3Future;
            case AGGREGATE, POST_AGGREGATION -> voteFuture;
        };
    }

    private Consumer<CompletableFuture<Void>> consumerOf(@NonNull final WrapsPhase phase) {
        return switch (phase) {
            case UNRECOGNIZED -> throw new IllegalArgumentException("Unrecognized phase");
            case R1 -> f -> r1Future = f;
            case R2 -> f -> r2Future = f;
            case R3 -> f -> r3Future = f;
            case AGGREGATE -> f -> setVoteFuture(f, ProofKind.NON_RECURSIVE);
            case POST_AGGREGATION -> f -> setVoteFuture(f, ProofKind.RECURSIVE);
        };
    }

    private void setVoteFuture(@Nullable final CompletableFuture<Void> future, @NonNull final ProofKind proofKind) {
        synchronized (voteLock) {
            voteFuture = future;
            voteFutureKind = future == null ? null : proofKind;
        }
    }

    private Bytes selfProofHashOrThrow() {
        return explicitHistoryProofHashes.computeIfAbsent(selfId, k -> hashOf(requireNonNull(historyProof)));
    }

    private static ProofKind proofKindOf(@NonNull final HistoryProof proof) {
        return proof.chainOfTrustProofOrElse(ChainOfTrustProof.DEFAULT).hasWrapsProof()
                ? ProofKind.RECURSIVE
                : ProofKind.NON_RECURSIVE;
    }

    private static ProofKind proofKindOf(@NonNull final ProofVoteCategory category) {
        return category == ProofVoteCategory.NOT_RECURSIVE ? ProofKind.NON_RECURSIVE : ProofKind.RECURSIVE;
    }

    private static Bytes hashOf(@NonNull final HistoryProof proof) {
        return noThrowSha384HashOf(HistoryProof.PROTOBUF.toBytes(proof));
    }
}
