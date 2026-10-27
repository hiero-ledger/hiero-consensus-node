// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.history.impl;

import static com.hedera.hapi.node.state.history.WrapsPhase.AGGREGATE;
import static com.hedera.hapi.node.state.history.WrapsPhase.R1;
import static com.hedera.hapi.node.state.history.WrapsPhase.R2;
import static com.hedera.hapi.node.state.history.WrapsPhase.R3;
import static com.hedera.hapi.util.HapiUtils.asInstant;
import static com.hedera.node.app.hapi.utils.CommonUtils.noThrowSha384HashOf;
import static com.hedera.node.app.history.HistoryLibrary.MAX_ADDRESS_BOOK_SIZE;
import static com.hedera.node.app.history.HistoryLibrary.MISSING_SCHNORR_KEY;
import static com.hedera.node.app.history.impl.ProofControllers.groundsChainOfTrust;
import static com.hedera.node.app.history.impl.ProofControllers.isWrapsExtensible;
import static com.hedera.node.app.history.impl.WrapsMpcStateMachine.POST_MPC_PHASES;
import static java.util.Collections.emptySortedMap;
import static java.util.Objects.requireNonNull;
import static java.util.concurrent.TimeUnit.MILLISECONDS;

import com.hedera.cryptography.wraps.SchnorrKeys;
import com.hedera.cryptography.wraps.WRAPSLibraryBridge;
import com.hedera.hapi.node.state.history.ChainOfTrustProof;
import com.hedera.hapi.node.state.history.History;
import com.hedera.hapi.node.state.history.HistoryProof;
import com.hedera.hapi.node.state.history.HistoryProofConstruction;
import com.hedera.hapi.node.state.history.HistoryProofVote;
import com.hedera.hapi.node.state.history.ProofKey;
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
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SortedMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * A {@link HistoryProver} that uses the WRAPS protocol to construct a {@link HistoryProof} that uses a
 * {@link ChainOfTrustProof#wrapsProof()} to establish chain of trust from the ledger id. The state machine moves
 * first through a signing protocol that forms an aggregate signature from three rounds of exchanging WRAPS
 * messages; and then uses that signature to compute a succinct proof that either grounds a new chain of trust
 * (a genesis proof, whose ledger id is the signed message) or extends the chain of trust of the source proof.
 * <p>
 * Since computing a proof is expensive, each node waits a jitter proportional to its rank among the source nodes
 * (where nodes that published R1 messages for the construction rank first) before computing one; and if it observes a
 * valid proof from another node in the meantime, simply votes congruent with that proof instead. So in the common
 * case only the top-ranked node computes a proof.
 */
public class WrapsHistoryProver implements HistoryProver {
    private static final Logger log = LogManager.getLogger(WrapsHistoryProver.class);
    public static final String MISSING_MESSAGES_FAILURE_PREFIX = "Still missing messages from R1 nodes ";
    public static final String AGGREGATION_FAILURE_PREFIX = "WRAPS aggregation failed for R1 nodes ";
    public static final String R1_TIMEOUT_FAILURE_PREFIX =
            "R1 did not reach the signing threshold before the end of" + " its grace period, with messages from nodes ";
    public static final String WRAPS_NOT_READY_FAILURE_PREFIX = "WRAPS library is not ready";
    public static final String NOT_WRAPS_EXTENSIBLE_FAILURE_PREFIX = "Source proof is not WRAPS-extensible";
    public static final String ADDRESS_BOOK_TOO_LARGE_FAILURE_PREFIX = "Address book too large for a WRAPS proof";

    /**
     * The key pair of the signer that checks other nodes' R1 and R2 messages, derived from a fixed seed so every node
     * reaches the same verdict; since it never signs anything else, it does not matter that anyone can derive its
     * private key.
     */
    private static final SchnorrKeys CHECK_KEYS = requireNonNull(
            WRAPSLibraryBridge.getInstance().generateSchnorrKeys(new byte[WRAPSLibraryBridge.ENTROPY_SIZE]));

    private static final Set<Long> CHECK_SIGNERS = Set.of(0L, 1L);

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
     * If non-null, the signer that checks other nodes' R1 and R2 messages.
     */
    @Nullable
    private MessageChecker messageChecker;

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
     * If non-null, a node whose explicit proof has already been validated.
     */
    @Nullable
    private Long validProofNodeId;

    /**
     * Whether the network has already finalized a proof for this construction.
     */
    private boolean proofFinalized;

    /**
     * The current WRAPS phase; starts with R1 and advances as messages are received.
     */
    private WrapsPhase wrapsPhase = R1;

    /**
     * Indicates this prover's construction has been canceled and any post-output work should be skipped.
     */
    private volatile boolean constructionCanceled = false;

    private sealed interface ProofOutput permits NoopOutput, ProofPhaseOutput {}

    private record NoopOutput(String reason) implements ProofOutput {}

    private record ProofPhaseOutput(byte[] compressed, byte[] uncompressed) implements ProofOutput {
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

    /**
     * A signer that checks other nodes' R1 and R2 messages by running the round that consumes them, in a two-node
     * address book whose other seat holds the sender's key and the message under check.
     *
     * @param message the message it signs
     * @param r1 its R1 message
     * @param r2 its R2 message
     */
    private record MessageChecker(byte[] message, byte[] r1, byte[] r2) {}

    private enum VoteChoice {
        SUBMIT,
        SKIP
    }

    private record VoteDecision(
            VoteChoice choice,
            @Nullable Long congruentNodeId,
            @Nullable HistoryProof proof) {
        static VoteDecision explicit(@NonNull final HistoryProof proof) {
            return new VoteDecision(VoteChoice.SUBMIT, null, proof);
        }

        static VoteDecision skip() {
            return new VoteDecision(VoteChoice.SKIP, null, null);
        }

        static VoteDecision congruent(long nodeId) {
            return new VoteDecision(VoteChoice.SUBMIT, nodeId, null);
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
        final int largestBookSize = Math.max(
                weights.sourceNodeWeights().size(), weights.targetNodeWeights().size());
        if (largestBookSize > MAX_ADDRESS_BOOK_SIZE) {
            return new Outcome.Failed(ADDRESS_BOOK_TOO_LARGE_FAILURE_PREFIX + " (" + largestBookSize
                    + " nodes, but a WRAPS proof covers at most " + MAX_ADDRESS_BOOK_SIZE + ")");
        }
        if (ledgerId == null && sourceProof != null) {
            return new Outcome.Failed("Only genesis WRAPS proofs are allowed to not have a ledger id");
        }
        // A construction with the same roster as source and target grounds a chain of trust, even when there
        // is a source proof it could fold onto; that is how a fresh genesis proof replaces the active one
        final boolean groundsChainOfTrust = groundsChainOfTrust(construction);
        if (!groundsChainOfTrust && !isWrapsExtensible(sourceProof)) {
            return new Outcome.Failed(
                    NOT_WRAPS_EXTENSIBLE_FAILURE_PREFIX + ", so cannot extend its chain of trust to a new roster");
        }
        foldsOntoSourceProof = !groundsChainOfTrust;
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
            publishIfNeeded(
                    construction.constructionId(), state.phase(), targetMetadata, targetProofKeys, tssConfig, ledgerId);
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
            final CompletableFuture<VoteDecision> decisionFuture;
            synchronized (voteLock) {
                this.proofFinalized = true;
                decisionFuture = voteDecisionFuture;
                voteDecisionFuture = null;
                voteFuture = null;
            }
            if (decisionFuture != null) {
                decisionFuture.complete(VoteDecision.skip());
            }
            return;
        }
        // An invalid explicit proof obviously has no use for us; and a congruent vote names an explicit vote
        // that we already observed and categorized
        if (vote.hasProof() && proofVoteCategory == ProofVoteCategory.VALID) {
            // This is the big win, avoiding an explicit vote for the large WRAPS proof
            final CompletableFuture<VoteDecision> decisionFuture;
            synchronized (voteLock) {
                validProofNodeId = nodeId;
                decisionFuture = voteDecisionFuture;
            }
            if (decisionFuture != null && decisionFuture.complete(VoteDecision.congruent(nodeId))) {
                log.info("Observed valid explicit proof from node{}; voting congruent instead", nodeId);
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
     * reads all R1 messages, and every node's R3 checks that each R2 message opens its sender's R1 commitment; so
     * one unusable message would keep every honest node from publishing. The check runs the round that consumes the
     * message for a signer used for nothing else (see {@link MessageChecker}), so every node reaches the same verdict.
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
        final var checker = messageChecker();
        final var message = publication.message().toByteArray();
        // Honest rounds also fail for some keys the source proof can hold, such as an identity point with any proof
        // of knowledge; so the sender's seat holds its key from the source proof
        final var book = new AddressBook(
                new long[] {1L, 1L},
                new byte[][] {proofKeys.get(nodeId).toByteArray(), CHECK_KEYS.publicKey()},
                new long[] {0L, 1L});
        return switch (phase) {
            case R1 ->
                historyLibrary.runWrapsPhaseR2(
                                new byte[32],
                                checker.message(),
                                new byte[][] {message, checker.r1()},
                                CHECK_KEYS.privateKey(),
                                book,
                                CHECK_SIGNERS)
                        != null;
            case R2 -> {
                final var r1 = phaseMessages.get(R1).get(nodeId);
                yield r1 == null
                        || historyLibrary.runWrapsPhaseR3(
                                        new byte[32],
                                        checker.message(),
                                        new byte[][] {r1.message().toByteArray(), checker.r1()},
                                        new byte[][] {message, checker.r2()},
                                        CHECK_KEYS.privateKey(),
                                        book,
                                        CHECK_SIGNERS)
                                != null;
            }
            default -> true;
        };
    }

    private MessageChecker messageChecker() {
        if (messageChecker == null) {
            final var book = new AddressBook(new long[] {1L}, new byte[][] {CHECK_KEYS.publicKey()}, new long[] {1L});
            // The checks never depend on the message signed, so any valid one will do
            final var message = historyLibrary.computeWrapsMessage(book, new byte[] {0});
            final var r1 = historyLibrary.runWrapsPhaseR1(new byte[32], message, CHECK_KEYS.privateKey());
            final var r2 = historyLibrary.runWrapsPhaseR2(
                    new byte[32], message, new byte[][] {r1}, CHECK_KEYS.privateKey(), book, Set.of(1L));
            messageChecker = new MessageChecker(message, r1, r2);
        }
        return messageChecker;
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
     * Ensures this node has published its WRAPS message or proof vote.
     */
    private void publishIfNeeded(
            final long constructionId,
            @NonNull final WrapsPhase phase,
            @NonNull final Bytes targetMetadata,
            @NonNull final Map<Long, Bytes> targetProofKeys,
            @NonNull final TssConfig tssConfig,
            @Nullable final Bytes ledgerId) {
        if (shouldSkipAfterCancellation(constructionId, phase)) {
            return;
        }
        // The library only computes an R3 share for this node's key in the source proof, so with any other key
        // this node's R1 message could only hold up the attempt
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
        // Skip building sourceBook/proofKeyList/chained futures while the WRAPS library is still loading
        if (phase == AGGREGATE && !historyLibrary.wrapsProverReady()) {
            if (isWrapsReadinessRetry) {
                log.debug("Deferring {} output for construction #{}: {}", phase, constructionId, "WRAPS not ready");
            } else {
                log.info(
                        "Deferring {} output for construction #{}: {} (will retry each consensus round until ready)",
                        phase,
                        constructionId,
                        WRAPS_NOT_READY_FAILURE_PREFIX);
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
            } else {
                log.info("Considering publication of WRAPS {} output on construction #{}", phase, constructionId);
            }
            final var sourceBook = sourceBook();
            if (phase == AGGREGATE) {
                consumerOf(phase)
                        .accept(scheduleProofVote(
                                constructionId,
                                tssConfig,
                                ledgerId,
                                sourceBook,
                                targetMetadata,
                                proofKeyListFrom(targetProofKeys)));
            } else {
                consumerOf(phase)
                        .accept(messageFuture(phase, sourceBook)
                                .thenAcceptAsync(
                                        message -> {
                                            if (message == null) {
                                                if (phase == R1) {
                                                    log.warn(
                                                            "Got null output for {} phase, skipping publication",
                                                            phase);
                                                }
                                                return;
                                            }
                                            if (shouldSkipAfterCancellation(constructionId, phase)) {
                                                return;
                                            }
                                            submissions
                                                    .submitWrapsSigningMessage(
                                                            phase, Bytes.wrap(message), constructionId)
                                                    .join();
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
    }

    /**
     * Schedules this node's vote on the proof for the given construction. The node votes congruent with the first
     * valid explicit proof it observes, as soon as it observes it. Only if it has not observed one by the end of a
     * jitter proportional to its rank (see {@link #computeJitterMs(TssConfig, long)}) does it compute its own proof,
     * and then vote for that explicitly. So in the common case only the top-ranked node pays to compute a proof and
     * submit it in full; while lower-ranked nodes still take over if it fails to.
     *
     * @param constructionId the construction ID
     * @param tssConfig the TSS configuration
     * @param ledgerId the ledger id, if known
     * @param sourceBook the source address book
     * @param targetMetadata the target metadata
     * @param proofKeyList the target proof keys
     * @return the future that resolves on submission of this node's vote
     */
    private CompletableFuture<Void> scheduleProofVote(
            final long constructionId,
            @NonNull final TssConfig tssConfig,
            @Nullable final Bytes ledgerId,
            @NonNull final AddressBook sourceBook,
            @NonNull final Bytes targetMetadata,
            @NonNull final List<ProofKey> proofKeyList) {
        final var decisionFuture = new CompletableFuture<VoteDecision>();
        final var submissionFuture = decisionFuture.thenCompose(decision -> switch (decision.choice()) {
            case SKIP -> CompletableFuture.<Void>completedFuture(null);
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
                    yield submissions.submitExplicitProofVote(constructionId, requireNonNull(decision.proof()));
                }
            }
        });
        final Long congruentNodeId;
        synchronized (voteLock) {
            if (constructionCanceled || proofFinalized) {
                log.info("Skipping proof vote scheduling on finalized construction #{}", constructionId);
                return CompletableFuture.completedFuture(null);
            }
            congruentNodeId = validProofNodeId;
            voteDecisionFuture = decisionFuture;
        }
        if (congruentNodeId != null) {
            log.info(
                    "Already observed valid explicit proof from node{}; voting congruent immediately", congruentNodeId);
            decisionFuture.complete(VoteDecision.congruent(congruentNodeId));
            return submissionFuture;
        }
        final long jitterMs = computeJitterMs(tssConfig, constructionId);
        if (jitterMs > 0) {
            log.info(
                    "Will compute a proof for construction #{} in {}ms unless first observing a valid proof",
                    constructionId,
                    jitterMs);
        }
        final var targetBook = requireNonNull(targetAddressBook);
        final var targetBookHash = requireNonNull(targetAddressBookHash);
        // advance() verified this signature before publishing any AGGREGATE output
        final var signature = requireNonNull(aggregateSignature);
        final var proofExecutor = jitterMs > 0 ? delayer.delayedExecutor(jitterMs, MILLISECONDS, executor) : executor;
        CompletableFuture.supplyAsync(
                        () -> decisionFuture.isDone() || constructionCanceled
                                ? null
                                : proofOutput(ledgerId, sourceBook, targetBook, targetMetadata, signature),
                        proofExecutor)
                .thenAcceptAsync(
                        output -> {
                            if (output == null || shouldSkipAfterCancellation(constructionId, AGGREGATE)) {
                                return;
                            }
                            switch (output) {
                                case ProofPhaseOutput proofOutput -> {
                                    final var proof = HistoryProof.newBuilder()
                                            .targetProofKeys(proofKeyList)
                                            .targetHistory(new History(Bytes.wrap(targetBookHash), targetMetadata))
                                            .chainOfTrustProof(ChainOfTrustProof.newBuilder()
                                                    .wrapsProof(Bytes.wrap(proofOutput.compressed())))
                                            .uncompressedWrapsProof(Bytes.wrap(proofOutput.uncompressed()))
                                            .build();
                                    // Unless we observed a valid proof while computing ours, vote for ours
                                    decisionFuture.complete(VoteDecision.explicit(proof));
                                }
                                case NoopOutput noopOutput -> {
                                    if (WRAPS_NOT_READY_FAILURE_PREFIX.equals(noopOutput.reason())) {
                                        // Flag instead of clearing voteFuture inline; we may not be on the
                                        // thread that set it
                                        log.debug(
                                                "Deferring {} output: {} (will retry next round)",
                                                AGGREGATE,
                                                noopOutput.reason());
                                        phaseNeedingWrapsReadinessRetry = AGGREGATE;
                                    } else {
                                        log.info(
                                                "Skipping publication of {} output: {}",
                                                AGGREGATE,
                                                noopOutput.reason());
                                    }
                                }
                            }
                        },
                        executor)
                .exceptionally(e -> {
                    log.error("Failed to compute a WRAPS proof for construction #{}", constructionId, e);
                    return null;
                });
        return submissionFuture;
    }

    private boolean shouldSkipAfterCancellation(final long constructionId, @NonNull final WrapsPhase phase) {
        if (constructionCanceled) {
            log.info("Skipping post-output work for WRAPS {} on canceled construction #{}", phase, constructionId);
            return true;
        }
        return false;
    }

    /**
     * Returns how long this node waits before computing its own proof; that is, the per-rank jitter times this node's
     * rank among the source nodes, which run the protocol. Nodes that published R1 messages for this construction (and
     * so were demonstrably online for it) rank ahead of the rest, so an offline node never holds up the proof; and each
     * group rotates with the construction id, so the work of computing proofs moves around the network.
     *
     * @param tssConfig the TSS configuration
     * @param constructionId the construction id
     * @return the jitter in milliseconds
     */
    private long computeJitterMs(@NonNull final TssConfig tssConfig, final long constructionId) {
        final var r1Publishers =
                phaseMessages.getOrDefault(R1, emptySortedMap()).keySet();
        final List<Long> ranking = new ArrayList<>(rotated(new ArrayList<>(r1Publishers), constructionId));
        ranking.addAll(rotated(
                weights.sourceNodeWeights().keySet().stream()
                        .filter(nodeId -> !r1Publishers.contains(nodeId))
                        .toList(),
                constructionId));
        final int rank = ranking.indexOf(selfId);
        return tssConfig.wrapsVoteJitterPerRank().toMillis() * (rank < 0 ? ranking.size() : rank);
    }

    /**
     * Returns the given node ids rotated left by the construction id, so a different node leads each construction.
     *
     * @param nodeIds the node ids, in ascending order
     * @param constructionId the construction id
     * @return the rotated node ids
     */
    private static List<Long> rotated(@NonNull final List<Long> nodeIds, final long constructionId) {
        if (nodeIds.isEmpty()) {
            return nodeIds;
        }
        final var rotation = new ArrayList<>(nodeIds);
        Collections.rotate(rotation, -Math.floorMod(constructionId, rotation.size()));
        return rotation;
    }

    private CompletableFuture<byte[]> messageFuture(
            @NonNull final WrapsPhase phase, @NonNull final AddressBook sourceBook) {
        final var message = requireNonNull(wrapsMessage);
        return CompletableFuture.supplyAsync(
                () -> switch (phase) {
                    case UNRECOGNIZED, AGGREGATE, POST_AGGREGATION ->
                        throw new IllegalArgumentException("Unexpected phase " + phase);
                    case R1 -> {
                        if (entropy == null) {
                            entropy = new byte[32];
                            new SecureRandom().nextBytes(entropy);
                            yield historyLibrary.runWrapsPhaseR1(
                                    entropy,
                                    message,
                                    schnorrKeyPair.privateKey().toByteArray());
                        }
                        yield null;
                    }
                    case R2 -> {
                        if (entropy != null && phaseMessages.get(R1).containsKey(selfId)) {
                            yield historyLibrary.runWrapsPhaseR2(
                                    entropy,
                                    message,
                                    rawMessagesFor(R1),
                                    schnorrKeyPair.privateKey().toByteArray(),
                                    sourceBook,
                                    phaseMessages.get(R1).keySet());
                        }
                        yield null;
                    }
                    case R3 -> {
                        if (entropy != null && phaseMessages.get(R1).containsKey(selfId)) {
                            yield historyLibrary.runWrapsPhaseR3(
                                    entropy,
                                    message,
                                    rawMessagesFor(R1),
                                    rawMessagesFor(R2),
                                    schnorrKeyPair.privateKey().toByteArray(),
                                    sourceBook,
                                    phaseMessages.get(R1).keySet());
                        }
                        yield null;
                    }
                },
                executor);
    }

    /**
     * Uses the given aggregate signature on the WRAPS message to compute a proof that either grounds a chain of
     * trust whose ledger id is the WRAPS message; or extends the chain of trust of the source proof.
     */
    private ProofOutput proofOutput(
            @Nullable final Bytes ledgerId,
            @NonNull final AddressBook sourceBook,
            @NonNull final AddressBook targetBook,
            @NonNull final Bytes targetMetadata,
            @NonNull final byte[] signature) {
        final var message = requireNonNull(wrapsMessage);
        final var signers = phaseMessages.get(R1).keySet();
        if (!historyLibrary.wrapsProverReady()) {
            return new NoopOutput(WRAPS_NOT_READY_FAILURE_PREFIX);
        }
        final long now = System.nanoTime();
        if (foldsOntoSourceProof) {
            final var foldedProof = requireNonNull(sourceProof);
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
                return new NoopOutput("Incremental WRAPS proof construction returned null");
            }
            final var output = new ProofPhaseOutput(proof.compressed(), proof.uncompressed());
            logElapsed(
                    constructionCanceled
                            ? "constructing canceled incremental WRAPS proof"
                            : "constructing incremental WRAPS proof -> " + output,
                    now);
            return output;
        } else {
            // Sans a proof to fold onto, we are grounding a chain of trust; its ledger id is the message just
            // signed, and the library requires it be anchored in the hash of the very address book that signed it
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
                    Bytes.wrap(message),
                    Bytes.wrap(genesisAddressBookHash),
                    targetMetadata,
                    Bytes.wrap(signature),
                    signers,
                    targetBook);
            final var proof = historyLibrary.constructGenesisWrapsProof(
                    genesisAddressBookHash, targetMetadata.toByteArray(), signature, signers, targetBook);
            if (proof == null) {
                return new NoopOutput("Genesis WRAPS proof construction returned null");
            }
            final var output = new ProofPhaseOutput(proof.compressed(), proof.uncompressed());
            logElapsed(
                    constructionCanceled
                            ? "constructing canceled genesis WRAPS proof"
                            : "constructing genesis WRAPS proof -> " + output,
                    now);
            return output;
        }
    }

    private void logElapsed(@NonNull final String event, final long startNs) {
        final var duration = Duration.ofNanos(System.nanoTime() - startNs);
        log.info(
                "FINISHED {} - took {}m {}.{}s",
                event,
                duration.toMinutes(),
                duration.toSecondsPart(),
                String.format("%03d", duration.toMillisPart()));
    }

    private byte[][] rawMessagesFor(@NonNull final WrapsPhase phase) {
        return phaseMessages.get(phase).values().stream()
                .map(WrapsMessagePublication::message)
                .map(Bytes::toByteArray)
                .toArray(byte[][]::new);
    }

    private CompletableFuture<Void> futureOf(@NonNull final WrapsPhase phase) {
        return switch (phase) {
            case UNRECOGNIZED, POST_AGGREGATION -> throw new IllegalArgumentException("Unexpected phase " + phase);
            case R1 -> r1Future;
            case R2 -> r2Future;
            case R3 -> r3Future;
            case AGGREGATE -> voteFuture;
        };
    }

    private Consumer<CompletableFuture<Void>> consumerOf(@NonNull final WrapsPhase phase) {
        return switch (phase) {
            case UNRECOGNIZED, POST_AGGREGATION -> throw new IllegalArgumentException("Unexpected phase " + phase);
            case R1 -> f -> r1Future = f;
            case R2 -> f -> r2Future = f;
            case R3 -> f -> r3Future = f;
            case AGGREGATE -> this::setVoteFuture;
        };
    }

    private void setVoteFuture(@Nullable final CompletableFuture<Void> future) {
        synchronized (voteLock) {
            voteFuture = future;
        }
    }
}
