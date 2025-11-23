// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.history.impl;

import static com.hedera.hapi.node.state.history.WrapsPhase.AGGREGATE;
import static com.hedera.hapi.node.state.history.WrapsPhase.R1;
import static com.hedera.hapi.node.state.history.WrapsPhase.R2;
import static com.hedera.hapi.node.state.history.WrapsPhase.R3;
import static com.hedera.hapi.util.HapiUtils.asInstant;
import static com.hedera.node.app.history.HistoryLibrary.EMPTY_PUBLIC_KEY;
import static com.hedera.node.app.service.roster.impl.RosterTransitionWeights.moreThanHalfOfTotal;
import static java.util.Collections.emptySortedMap;
import static java.util.Objects.requireNonNull;

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
import java.util.EnumMap;
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
    private final Map<Long, Bytes> sourcePublicKeys;
    private final RosterTransitionWeights weights;
    private final Executor executor;
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

    public WrapsHistoryProver(
            final long selfId,
            @NonNull final Map<Long, Bytes> sourceProofKeys,
            @NonNull final SchnorrKeyPair schnorrKeyPair,
            @NonNull final RosterTransitionWeights weights,
            @NonNull final Executor executor,
            @NonNull final HistoryLibrary historyLibrary,
            @NonNull final HistorySubmissions submissions) {
        this.selfId = selfId;
        this.schnorrKeyPair = requireNonNull(schnorrKeyPair);
        this.weights = requireNonNull(weights);
        this.sourcePublicKeys = requireNonNull(sourceProofKeys);
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
            @NonNull final Map<Long, Bytes> targetProofKeys) {
        requireNonNull(now);
        requireNonNull(construction);
        requireNonNull(targetMetadata);
        requireNonNull(targetProofKeys);
        final var state = construction.wrapsSigningStateOrElse(WrapsSigningState.DEFAULT);
        if (state.hasGracePeriodEndTime() && now.isAfter(asInstant(state.gracePeriodEndTimeOrThrow()))) {
            final var submittingNodes = phaseMessages.get(state.phase()).keySet();
            final var missingNodes = phaseMessages.get(R1).keySet().stream()
                    .filter(nodeId -> !submittingNodes.contains(nodeId))
                    .toList();
            return new Outcome.Failed("Still missing messages from R1 nodes " + missingNodes
                    + " after end of grace period for phase " + state.phase());
        } else {
            if (wrapsMessage == null) {
                final var targetAddressBook = AddressBook.from(weights.targetNodeWeights(), nodeId -> targetProofKeys
                        .getOrDefault(nodeId, EMPTY_PUBLIC_KEY)
                        .toByteArray());
                wrapsMessage = historyLibrary.computeWrapsMessage(targetAddressBook, targetMetadata.toByteArray());
                targetAddressBookHash = historyLibrary.hashAddressBook(targetAddressBook);
            }
            ensureProtocolOutputPublished(
                    construction.constructionId(), state.phase(), targetMetadata, targetProofKeys);
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

    private boolean receiveWrapsSigningMessage(
            final long constructionId,
            @NonNull final WrapsMessagePublication publication,
            @Nullable final WritableHistoryStore writableHistoryStore,
            @Nullable final TssConfig tssConfig) {
        if (publication.phase() != wrapsPhase) {
            return false;
        }
        final var messages = phaseMessages.computeIfAbsent(publication.phase(), p -> new TreeMap<>());
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
            final var r2NodesSoFar = phaseMessages.get(R2).keySet();
            if (r2NodesSoFar.containsAll(r1Nodes)) {
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
            final var r3NodesSoFar = phaseMessages.get(R3).keySet();
            if (r3NodesSoFar.containsAll(r1Nodes)) {
                if (writableHistoryStore != null && tssConfig != null) {
                    writableHistoryStore.advanceWrapsSigningPhase(constructionId, AGGREGATE, null);
                }
                wrapsPhase = AGGREGATE;
            }
        }
        return true;
    }

    @Override
    public boolean cancelPendingWork() {
        throw new AssertionError("Not implemented");
    }

    /**
     * Ensures this node has published its WRAPS message or aggregate signature vote.
     */
    private void ensureProtocolOutputPublished(
            final long constructionId,
            @NonNull final WrapsPhase phase,
            @NonNull final Bytes targetMetadata,
            @NonNull final Map<Long, Bytes> targetProofKeys) {
        requireNonNull(phase);
        requireNonNull(targetMetadata);
        requireNonNull(targetProofKeys);
        if (futureOf(phase) == null
                && (phase == AGGREGATE
                        || !phaseMessages.getOrDefault(phase, emptySortedMap()).containsKey(selfId))) {
            log.info("Publishing {} protocol output for WRAPS on construction #{}", phase, constructionId);
            final var bookHash = requireNonNull(targetAddressBookHash);
            final var proofKeyList = proofKeyListFrom(targetProofKeys);
            consumerOf(phase)
                    .accept(outputFuture(phase)
                            .thenAcceptAsync(
                                    output -> {
                                        if (phase != AGGREGATE) {
                                            submissions
                                                    .submitWrapsSigningMessage(phase, Bytes.wrap(output))
                                                    .join();
                                            log.info(
                                                    "Published {} message for WRAPS signature on construction #{}",
                                                    phase,
                                                    constructionId);
                                        } else {
                                            // This output is the concise WRAPS proof, so vote for it
                                            submissions
                                                    .submitProofVote(
                                                            constructionId,
                                                            HistoryProof.newBuilder()
                                                                    .targetProofKeys(proofKeyList)
                                                                    .targetHistory(new History(
                                                                            Bytes.wrap(bookHash), targetMetadata))
                                                                    .chainOfTrustProof(ChainOfTrustProof.newBuilder()
                                                                            .wrapsProof(Bytes.wrap(output)))
                                                                    .build())
                                                    .join();
                                        }
                                    },
                                    executor)
                            .exceptionally(e -> {
                                log.error(
                                        "Failed to publish {} message for WRAPS signature on construction #{}",
                                        phase,
                                        constructionId,
                                        e);
                                return null;
                            }));
        }
    }

    private CompletableFuture<byte[]> outputFuture(@NonNull final WrapsPhase phase) {
        final var message = requireNonNull(wrapsMessage);
        return CompletableFuture.supplyAsync(
                () -> switch (phase) {
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
                        if (entropy != null) {
                            yield historyLibrary.runWrapsPhaseR2(
                                    entropy,
                                    message,
                                    rawMessagesFor(R1),
                                    schnorrKeyPair.privateKey().toByteArray(),
                                    publicKeysForR1());
                        }
                        yield null;
                    }
                    case R3 -> {
                        if (entropy != null) {
                            yield historyLibrary.runWrapsPhaseR3(
                                    entropy,
                                    message,
                                    rawMessagesFor(R1),
                                    rawMessagesFor(R2),
                                    schnorrKeyPair.privateKey().toByteArray(),
                                    publicKeysForR1());
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
                            // TODO - block on the long-running proof using this signature, yield that instead
                            yield signature;
                        }
                        yield null;
                    }
                },
                executor);
    }

    private byte[][] publicKeysForR1() {
        return phaseMessages.get(R1).keySet().stream()
                .map(nodeId -> sourcePublicKeys.get(nodeId).toByteArray())
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
