// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.history.impl;

import static com.hedera.hapi.node.state.history.WrapsPhase.AGGREGATE;
import static com.hedera.hapi.node.state.history.WrapsPhase.R1;
import static com.hedera.hapi.node.state.history.WrapsPhase.R2;
import static com.hedera.hapi.node.state.history.WrapsPhase.R3;
import static com.hedera.hapi.util.HapiUtils.asInstant;
import static com.hedera.node.app.history.HistoryLibrary.EMPTY_PUBLIC_KEY;
import static com.hedera.node.app.history.impl.ProofControllers.isWrapsExtensible;
import static com.hedera.node.app.service.roster.impl.RosterTransitionWeights.moreThanHalfOfTotal;
import static java.util.Collections.emptySortedMap;
import static java.util.Objects.requireNonNull;

import com.hedera.cryptography.wraps.Proof;
import com.hedera.hapi.block.stream.AggregatedNodeSignatures;
import com.hedera.hapi.block.stream.ChainOfTrustProof;
import com.hedera.hapi.node.state.history.History;
import com.hedera.hapi.node.state.history.HistoryProof;
import com.hedera.hapi.node.state.history.HistoryProofConstruction;
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
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
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

    private final long selfId;
    private final SchnorrKeyPair schnorrKeyPair;
    private final Map<Long, Bytes> proofKeys;
    private final RosterTransitionWeights weights;
    private final Executor executor;

    @Nullable
    private final HistoryProof sourceProof;

    private final HistoryLibrary historyLibrary;
    private final HistorySubmissions submissions;
    private final Map<WrapsPhase, SortedMap<Long, WrapsMessagePublication>> phaseMessages =
            new EnumMap<>(WrapsPhase.class);

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
     * If not null, the WRAPS message being signed for the current construction.
     */
    @Nullable
    private byte[] targetAddressBookHash;

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
     * Future that resolves on submission of this node's vote for the aggregate signature.
     */
    @Nullable
    private CompletableFuture<Void> voteFuture;

    /**
     * If non-null, the entropy used to generate the R1 message. (If this node rejoins the network
     * after a restart, having lost its entropy, it cannot continue and the protocol will time out.)
     */
    @Nullable
    private byte[] entropy;

    /**
     * The current WRAPS phase; starts with R1 and advances as messages are received.
     */
    private WrapsPhase wrapsPhase = R1;

    private sealed interface WrapsPhaseOutput permits MessagePhaseOutput, ProofPhaseOutput, AggregatePhaseOutput {}

    private record MessagePhaseOutput(byte[] message) implements WrapsPhaseOutput {}

    private record AggregatePhaseOutput(byte[] signature, List<Long> nodeIds) implements WrapsPhaseOutput {}

    private record ProofPhaseOutput(byte[] compressed, byte[] uncompressed) implements WrapsPhaseOutput {}

    public WrapsHistoryProver(
            final long selfId,
            @NonNull final SchnorrKeyPair schnorrKeyPair,
            @Nullable final HistoryProof sourceProof,
            @NonNull final RosterTransitionWeights weights,
            @NonNull final Map<Long, Bytes> proofKeys,
            @NonNull final Executor executor,
            @NonNull final HistoryLibrary historyLibrary,
            @NonNull final HistorySubmissions submissions) {
        this.selfId = selfId;
        this.sourceProof = sourceProof;
        this.schnorrKeyPair = requireNonNull(schnorrKeyPair);
        this.weights = requireNonNull(weights);
        this.proofKeys = requireNonNull(proofKeys);
        this.executor = requireNonNull(executor);
        this.historyLibrary = requireNonNull(historyLibrary);
        this.submissions = requireNonNull(submissions);
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
            final var submittingNodes = phaseMessages.get(state.phase()).keySet();
            final var missingNodes = phaseMessages.get(R1).keySet().stream()
                    .filter(nodeId -> !submittingNodes.contains(nodeId))
                    .toList();
            return new Outcome.Failed("Still missing messages from R1 nodes " + missingNodes
                    + " after end of grace period for phase " + state.phase());
        } else {
            if (wrapsMessage == null) {
                targetAddressBook = AddressBook.from(weights.targetNodeWeights(), nodeId -> targetProofKeys
                        .getOrDefault(nodeId, EMPTY_PUBLIC_KEY)
                        .toByteArray());
                wrapsMessage = historyLibrary.computeWrapsMessage(targetAddressBook, targetMetadata.toByteArray());
                targetAddressBookHash = historyLibrary.hashAddressBook(targetAddressBook);
            }
            publishIfNeeded(
                    construction.constructionId(), state.phase(), targetMetadata, targetProofKeys, tssConfig, ledgerId);
        }
        return Outcome.InProgress.INSTANCE;
    }

    @Override
    public boolean addWrapsSigningMessage(
            final long constructionId,
            @NonNull final WrapsMessagePublication publication,
            @NonNull final WritableHistoryStore writableHistoryStore,
            @NonNull final TssConfig tssConfig) {
        requireNonNull(publication);
        requireNonNull(writableHistoryStore);
        requireNonNull(tssConfig);
        return receiveWrapsSigningMessage(constructionId, publication, writableHistoryStore, tssConfig);
    }

    @Override
    public void replayWrapsSigningMessage(long constructionId, @NonNull WrapsMessagePublication publication) {
        receiveWrapsSigningMessage(constructionId, publication, null, null);
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
            @Nullable final WritableHistoryStore writableHistoryStore,
            @Nullable final TssConfig tssConfig) {
        log.info(
                "Received {} message from node{} for construction #{} (current phase={})",
                publication.phase(),
                publication.nodeId(),
                constructionId,
                wrapsPhase);
        if (publication.phase() != wrapsPhase) {
            return false;
        }
        final var startPhase = wrapsPhase;
        final var messages = phaseMessages.computeIfAbsent(wrapsPhase, p -> new TreeMap<>());
        if (wrapsPhase == R1) {
            if (messages.putIfAbsent(publication.nodeId(), publication) != null) {
                return false;
            }
            final long r1Weight = messages.values().stream()
                    .mapToLong(p -> weights.sourceWeightOf(p.nodeId()))
                    .sum();
            if (r1Weight >= moreThanHalfOfTotal(weights.sourceNodeWeights())) {
                if (writableHistoryStore != null && tssConfig != null) {
                    writableHistoryStore.advanceWrapsSigningPhase(
                            constructionId, R2, publication.receiptTime().plus(tssConfig.wrapsMessageGracePeriod()));
                }
                wrapsPhase = R2;
            }
        } else if (wrapsPhase == R2) {
            final var r1Nodes = phaseMessages.get(R1).keySet();
            if (!r1Nodes.contains(publication.nodeId())) {
                return false;
            }
            if (messages.putIfAbsent(publication.nodeId(), publication) != null) {
                return false;
            }
            if (messages.keySet().containsAll(r1Nodes)) {
                if (writableHistoryStore != null && tssConfig != null) {
                    writableHistoryStore.advanceWrapsSigningPhase(
                            constructionId, R3, publication.receiptTime().plus(tssConfig.wrapsMessageGracePeriod()));
                }
                wrapsPhase = R3;
            }
        } else {
            final var r1Nodes = phaseMessages.get(R1).keySet();
            if (!r1Nodes.contains(publication.nodeId())) {
                return false;
            }
            if (messages.putIfAbsent(publication.nodeId(), publication) != null) {
                return false;
            }
            if (messages.keySet().containsAll(r1Nodes)) {
                if (writableHistoryStore != null && tssConfig != null) {
                    writableHistoryStore.advanceWrapsSigningPhase(constructionId, AGGREGATE, null);
                }
                wrapsPhase = AGGREGATE;
            }
        }
        if (wrapsPhase != startPhase) {
            log.info("Advanced to {} for construction #{}", wrapsPhase, constructionId);
        }
        return true;
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
            @Nullable final Bytes ledgerId) {
        if (futureOf(phase) == null
                && (phase == AGGREGATE
                        || !phaseMessages.getOrDefault(phase, emptySortedMap()).containsKey(selfId))) {
            log.info("Considering publication of WRAPS {} output on construction #{}", phase, constructionId);
            final var book = requireNonNull(targetAddressBook);
            final var bookHash = requireNonNull(targetAddressBookHash);
            final var proofKeyList = proofKeyListFrom(targetProofKeys);
            consumerOf(phase)
                    .accept(outputFuture(phase, tssConfig, ledgerId, book, targetMetadata)
                            .thenAcceptAsync(
                                    output -> {
                                        if (output == null) {
                                            if (phase == R1 || phase == AGGREGATE) {
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
                                                final var nonRecursiveProof = new AggregatedNodeSignatures(
                                                        Bytes.wrap(aggregatePhaseOutput.signature()),
                                                        new ArrayList<>(phaseMessages
                                                                .get(R1)
                                                                .keySet()));
                                                submissions
                                                        .submitProofVote(
                                                                constructionId,
                                                                HistoryProof.newBuilder()
                                                                        .targetProofKeys(proofKeyList)
                                                                        .targetHistory(new History(
                                                                                Bytes.wrap(bookHash), targetMetadata))
                                                                        .chainOfTrustProof(
                                                                                ChainOfTrustProof.newBuilder()
                                                                                        .aggregatedNodeSignatures(
                                                                                                nonRecursiveProof))
                                                                        .build())
                                                        .join();
                                            }
                                            case ProofPhaseOutput proofOutput -> {
                                                // We have a WRAPS proof
                                                final var recursiveProof = Bytes.wrap(proofOutput.compressed());
                                                final var uncompressedProof = Bytes.wrap(proofOutput.uncompressed());
                                                submissions
                                                        .submitProofVote(
                                                                constructionId,
                                                                HistoryProof.newBuilder()
                                                                        .targetProofKeys(proofKeyList)
                                                                        .targetHistory(new History(
                                                                                Bytes.wrap(bookHash), targetMetadata))
                                                                        .chainOfTrustProof(
                                                                                ChainOfTrustProof.newBuilder()
                                                                                        .wrapsProof(recursiveProof))
                                                                        .uncompressedWrapsProof(uncompressedProof)
                                                                        .build())
                                                        .join();
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

    private CompletableFuture<WrapsPhaseOutput> outputFuture(
            @NonNull final WrapsPhase phase,
            @NonNull final TssConfig tssConfig,
            @Nullable final Bytes ledgerId,
            @NonNull final AddressBook targetBook,
            @NonNull final Bytes targetMetadata) {
        final var message = requireNonNull(wrapsMessage);
        return CompletableFuture.supplyAsync(
                () -> switch (phase) {
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
                                    publicKeysForR1()));
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
                                    publicKeysForR1()));
                        }
                        yield null;
                    }
                    case AGGREGATE -> {
                        if (entropy != null) {
                            final var signature = historyLibrary.runAggregationPhase(
                                    message,
                                    rawMessagesFor(R1),
                                    rawMessagesFor(R2),
                                    rawMessagesFor(R3),
                                    publicKeysForR1());
                            // Sans source proof, we are at genesis and need an aggregate signature proof right away
                            if (sourceProof == null || !tssConfig.wrapsEnabled()) {
                                yield new AggregatePhaseOutput(
                                        signature,
                                        phaseMessages.get(R1).keySet().stream().toList());
                            } else {
                                Proof proof;
                                if (true) {
                                    proof = new Proof(new byte[30331352], new byte[704]);
                                } else {
                                    if (!isWrapsExtensible(sourceProof)) {
                                        proof = historyLibrary.constructGenesisWrapsProof(
                                                requireNonNull(ledgerId).toByteArray(),
                                                signature,
                                                phaseMessages.get(R1).keySet(),
                                                targetBook);
                                    } else {
                                        proof = new Proof(
                                                sourceProof.uncompressedWrapsProof().toByteArray(),
                                                sourceProof
                                                        .chainOfTrustProofOrThrow()
                                                        .wrapsProofOrThrow()
                                                        .toByteArray());
                                    }
                                    final var sourceBook = AddressBook.from(weights.sourceNodeWeights(), nodeId -> proofKeys
                                            .getOrDefault(nodeId, EMPTY_PUBLIC_KEY)
                                            .toByteArray());
                                    proof = historyLibrary.constructIncrementalWrapsProof(
                                            requireNonNull(ledgerId).toByteArray(),
                                            proof.uncompressed(),
                                            sourceBook,
                                            targetBook,
                                            targetMetadata.toByteArray(),
                                            signature,
                                            phaseMessages.get(R1).keySet());
                                }
                                yield new ProofPhaseOutput(proof.compressed(), proof.uncompressed());
                            }
                        }
                        yield null;
                    }
                },
                executor);
    }

    private byte[][] publicKeysForR1() {
        return phaseMessages.get(R1).keySet().stream()
                .map(nodeId -> proofKeys.get(nodeId).toByteArray())
                .toArray(byte[][]::new);
    }

    private byte[][] rawMessagesFor(@NonNull final WrapsPhase phase) {
        return phaseMessages.get(phase).values().stream()
                .map(WrapsMessagePublication::message)
                .map(Bytes::toByteArray)
                .toArray(byte[][]::new);
    }

    private CompletableFuture<Void> futureOf(@NonNull final WrapsPhase phase) {
        return switch (phase) {
            case R1 -> r1Future;
            case R2 -> r2Future;
            case R3 -> r3Future;
            case AGGREGATE -> voteFuture;
        };
    }

    private Consumer<CompletableFuture<Void>> consumerOf(@NonNull final WrapsPhase phase) {
        return switch (phase) {
            case R1 -> f -> r1Future = f;
            case R2 -> f -> r2Future = f;
            case R3 -> f -> r3Future = f;
            case AGGREGATE -> f -> voteFuture = f;
        };
    }
}
