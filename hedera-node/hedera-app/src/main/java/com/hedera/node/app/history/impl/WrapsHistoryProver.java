// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.history.impl;

import static com.hedera.hapi.node.state.history.WrapsPhase.AGGREGATE;
import static com.hedera.hapi.node.state.history.WrapsPhase.POST_AGGREGATION;
import static com.hedera.hapi.node.state.history.WrapsPhase.R1;
import static com.hedera.hapi.node.state.history.WrapsPhase.R2;
import static com.hedera.hapi.node.state.history.WrapsPhase.R3;
import static com.hedera.hapi.util.HapiUtils.asInstant;
import static com.hedera.node.app.hapi.utils.CommonUtils.noThrowSha384HashOf;
import static com.hedera.node.app.history.HistoryLibrary.EMPTY_PUBLIC_KEY;
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

    private final Map<WrapsPhase, SortedMap<Long, WrapsMessagePublication>> phaseMessages =
            new EnumMap<>(WrapsPhase.class);
    private final Map<Long, Bytes> explicitHistoryProofHashes = new HashMap<>();

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
    private CompletableFuture<Void> voteFuture;

    /**
     * The current WRAPS phase; starts with R1 and advances as messages are received.
     */
    private WrapsPhase wrapsPhase = R1;

    private sealed interface WrapsPhaseOutput
            permits NoopOutput, MessagePhaseOutput, ProofPhaseOutput, AggregatePhaseOutput {}

    private record NoopOutput(String reason) implements WrapsPhaseOutput {}

    private record MessagePhaseOutput(byte[] message) implements WrapsPhaseOutput {}

    private record AggregatePhaseOutput(byte[] signature, List<Long> nodeIds) implements WrapsPhaseOutput {}

    private record ProofPhaseOutput(byte[] compressed, byte[] uncompressed) implements WrapsPhaseOutput {}

    private enum VoteChoice {
        SUBMIT,
        SKIP
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
            @Nullable final Bytes ledgerId) {
        requireNonNull(now);
        requireNonNull(construction);
        requireNonNull(targetMetadata);
        requireNonNull(targetProofKeys);
        requireNonNull(tssConfig);
        if (ledgerId == null && sourceProof != null) {
            return new Outcome.Failed("Only genesis WRAPS proofs are allowed to not have a ledger id");
        }
        final var state = construction.wrapsSigningStateOrElse(WrapsSigningState.DEFAULT);
        if (state.phase() != AGGREGATE
                && state.hasGracePeriodEndTime()
                && now.isAfter(asInstant(state.gracePeriodEndTimeOrThrow()))) {
            final var submittingNodes =
                    phaseMessages.getOrDefault(state.phase(), emptySortedMap()).keySet();
            // If we reached a stage with a grace period, we must have at least one R1 message, so no getOrDefault()
            final var missingNodes = phaseMessages.get(R1).keySet().stream()
                    .filter(nodeId -> !submittingNodes.contains(nodeId))
                    .toList();
            return new Outcome.Failed(MISSING_MESSAGES_FAILURE_PREFIX + missingNodes
                    + " after end of grace period for phase " + state.phase());
        } else {
            if (wrapsMessage == null) {
                targetAddressBook = AddressBook.from(weights.targetNodeWeights(), nodeId -> targetProofKeys
                        .getOrDefault(nodeId, EMPTY_PUBLIC_KEY)
                        .toByteArray());
                wrapsMessage = historyLibrary.computeWrapsMessage(targetAddressBook, targetMetadata.toByteArray());
                targetAddressBookHash = historyLibrary.hashAddressBook(targetAddressBook);
            }
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
        return reason.startsWith(MISSING_MESSAGES_FAILURE_PREFIX);
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
            final long nodeId, @NonNull final HistoryProofVote vote, final boolean proofFinalized) {
        requireNonNull(vote);
        if (proofFinalized) {
            log.info("Observed finalized proof via node{}; skipping vote", nodeId);
            tryCompleteVoteDecision(VoteDecision.skip());
            // Null these out in case we need to repeat voting in a post-aggregation phase at genesis
            voteDecisionFuture = null;
            voteFuture = null;
            return;
        }
        // If we’ve already decided & sent our vote, nothing to do
        if (voteDecisionFuture == null || voteDecisionFuture.isDone()) {
            return;
        }
        // Explicit vote case
        if (vote.hasProof()) {
            final var proof = vote.proofOrElse(HistoryProof.DEFAULT);
            // Always store a hash – useful if we haven't finished our own proof yet.
            final var hash = hashOf(proof);
            explicitHistoryProofHashes.put(nodeId, hash);
            // If we already have our proof, see if it matches.
            if (historyProof != null && selfProofHashOrThrow().equals(hash)) {
                log.info("Observed matching explicit proof from node{}; voting congruent instead", nodeId);
                tryCompleteVoteDecision(VoteDecision.congruent(nodeId));
            }
        }
    }

    @Override
    public boolean cancelPendingWork() {
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
        if (futureOf(phase) == null
                && (POST_MPC_PHASES.contains(phase)
                        || !phaseMessages.getOrDefault(phase, emptySortedMap()).containsKey(selfId))) {
            if (phase == POST_AGGREGATION) {
                log.info("Considering publication of vote for genesis WRAPS proof on construction #{}", constructionId);
            } else {
                log.info("Considering publication of WRAPS {} output on construction #{}", phase, constructionId);
            }
            final var sourceBook = AddressBook.from(
                    weights.sourceNodeWeights(),
                    nodeId -> proofKeys.getOrDefault(nodeId, EMPTY_PUBLIC_KEY).toByteArray());
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
                                        switch (output) {
                                            case MessagePhaseOutput messageOutput -> {
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
                                            case NoopOutput noopOutput ->
                                                log.info(
                                                        "Skipping publication of {} output: {}",
                                                        phase,
                                                        noopOutput.reason());
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
        this.historyProof = proof;

        final var selfProofHash = hashOf(proof);
        for (final var entry : explicitHistoryProofHashes.entrySet()) {
            if (selfProofHash.equals(entry.getValue())) {
                log.info("Already observed explicit proof from node{}; voting congruent immediately", entry.getKey());
                this.voteDecisionFuture = CompletableFuture.completedFuture(VoteDecision.congruent(entry.getKey()));
                this.voteFuture = submissions.submitCongruentProofVote(constructionId, entry.getKey());
                return;
            }
        }

        this.voteDecisionFuture = new CompletableFuture<>();

        final long jitterMs = computeJitterMs(tssConfig, constructionId);
        final var delayed = delayer.delayedExecutor(jitterMs, MILLISECONDS, executor);

        // If this is the first thread to complete the vote decision, we submit an explicit vote
        CompletableFuture.runAsync(() -> tryCompleteVoteDecision(VoteDecision.explicit()), delayed);

        this.voteFuture = voteDecisionFuture.thenCompose(decision -> switch (decision.choice()) {
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
    }

    private void tryCompleteVoteDecision(VoteDecision decision) {
        final var f = this.voteDecisionFuture;
        if (f != null && !f.isDone()) {
            f.complete(decision);
        }
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
                        final var signature = historyLibrary.runAggregationPhase(
                                message,
                                rawMessagesFor(R1),
                                rawMessagesFor(R2),
                                rawMessagesFor(R3),
                                sourceBook,
                                phaseMessages.get(R1).keySet());
                        // Sans source proof, we are at genesis and need an aggregate signature proof right away
                        if (sourceProof == null || !tssConfig.wrapsEnabled()) {
                            final var isValid = historyLibrary.verifyAggregateSignature(
                                    message,
                                    sourceBook.nodeIds(),
                                    sourceBook.publicKeys(),
                                    sourceBook.weights(),
                                    signature);
                            if (!isValid) {
                                throw new IllegalStateException("Invalid aggregate signature using nodes "
                                        + phaseMessages.get(R1).keySet());
                            }
                            yield new AggregatePhaseOutput(
                                    signature,
                                    phaseMessages.get(R1).keySet().stream().toList());
                        } else {
                            if (!historyLibrary.wrapsProverReady()) {
                                yield new NoopOutput("WRAPS library is not ready");
                            }
                            final var isValid = historyLibrary.verifyAggregateSignature(
                                    message,
                                    sourceBook.nodeIds(),
                                    sourceBook.publicKeys(),
                                    sourceBook.weights(),
                                    signature);
                            final var signers = phaseMessages.get(R1).keySet();
                            if (!isValid) {
                                throw new IllegalStateException("Invalid aggregate signature using nodes " + signers);
                            }
                            final long now = System.nanoTime();
                            log.info("Constructing incremental WRAPS proof...");
                            final var proof = historyLibrary.constructIncrementalWrapsProof(
                                    requireNonNull(ledgerId).toByteArray(),
                                    sourceProof.uncompressedWrapsProof().toByteArray(),
                                    sourceBook,
                                    targetBook,
                                    targetMetadata.toByteArray(),
                                    signature,
                                    signers);
                            logElapsed("constructing incremental WRAPS proof", now);
                            yield new ProofPhaseOutput(proof.compressed(), proof.uncompressed());
                        }
                    }
                    case POST_AGGREGATION -> {
                        if (!historyLibrary.wrapsProverReady()) {
                            yield new NoopOutput("WRAPS library is not ready");
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
                        log.info("Constructing genesis WRAPS proof...");
                        final var proof = historyLibrary.constructGenesisWrapsProof(
                                requireNonNull(ledgerId).toByteArray(),
                                targetMetadata.toByteArray(),
                                signature,
                                signers,
                                targetBook);
                        logElapsed("constructing genesis WRAPS proof", now);
                        yield new ProofPhaseOutput(proof.compressed(), proof.uncompressed());
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
            case AGGREGATE, POST_AGGREGATION -> f -> voteFuture = f;
        };
    }

    private Bytes selfProofHashOrThrow() {
        return explicitHistoryProofHashes.computeIfAbsent(selfId, k -> hashOf(requireNonNull(historyProof)));
    }

    private static Bytes hashOf(@NonNull final HistoryProof proof) {
        return noThrowSha384HashOf(HistoryProof.PROTOBUF.toBytes(proof));
    }
}
