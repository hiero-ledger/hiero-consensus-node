// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.history.impl;

import static com.hedera.node.app.history.HistoryLibrary.EMPTY_PUBLIC_KEY;
import static java.util.Objects.requireNonNull;
import static java.util.stream.Collectors.toMap;

import com.hedera.hapi.block.stream.ChainOfTrustProof;
import com.hedera.hapi.block.stream.NodeSignature;
import com.hedera.hapi.block.stream.NodeSignatures;
import com.hedera.hapi.node.state.history.History;
import com.hedera.hapi.node.state.history.HistoryProof;
import com.hedera.hapi.node.state.history.HistoryProofConstruction;
import com.hedera.hapi.node.state.history.HistorySignature;
import com.hedera.node.app.history.HistoryLibrary;
import com.hedera.node.app.history.ReadableHistoryStore.HistorySignaturePublication;
import com.hedera.node.app.history.impl.ProofKeysAccessorImpl.SchnorrKeyPair;
import com.hedera.node.app.service.roster.impl.RosterTransitionWeights;
import com.hedera.node.config.data.TssConfig;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.nio.ByteBuffer;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * A genesis-only {@link HistoryProver} that,
 * <ul>
 *   <li>Submits its own and verifies Schnorr signatures from other nodes;</li>
 *   <li>Tracks weight per (history) assembly; and,</li>
 *   <li>Once some assembly has >= threshold weight, converts the signatures whose verifications
 *   before a cutoff time into a list-of-signatures {@link ChainOfTrustProof}; and,</li>
 *   <li>Build a {@link HistoryProof} using the canonical address-book hash of the target roster
 *   and the supplied metadata.</li>
 * </ul>
 * Since it is extremely cheap to verify signatures, this prover does not need any asynchronous behavior beyond
 * the inherently asynchronous step of submitting its signature.
 */
public class ListOfSignaturesHistoryProver implements HistoryProver {
    private static final Logger log = LogManager.getLogger(ListOfSignaturesHistoryProver.class);

    private static final int INSUFFICIENT_SIGNATURES_CHECK_RETRY_SECS = 10;
    private static final String INSUFFICIENT_SIGNATURES_FAILURE_REASON = "insufficient signatures";

    private final long selfId;
    private final SchnorrKeyPair schnorrKeyPair;
    private final RosterTransitionWeights weights;
    private final Executor executor;
    private final HistoryLibrary library;
    private final HistorySubmissions submissions;

    /**
     * Node ids we have already started verifying signatures for.
     */
    private final Set<Long> signingNodeIds = new HashSet<>();

    /**
     * Verification futures indexed by the consensus time of the signature publication.
     */
    private final NavigableMap<Instant, CompletableFuture<Verification>> verificationFutures = new TreeMap<>();

    /**
     * Future that submits this node's own assembly signature (if any).
     */
    @Nullable
    private CompletableFuture<Void> signingFuture;

    /**
     * A party's verified signature on a new piece of {@code (address book hash, metadata)} history.
     * @param nodeId           the node's id
     * @param historySignature its history signature
     * @param isValid          whether the signature is valid
     */
    private record Verification(long nodeId, @NonNull HistorySignature historySignature, boolean isValid) {
        public @NonNull History history() {
            return historySignature.historyOrThrow();
        }
    }

    /**
     * A summary of the signatures to be used in a proof.
     * @param history the assembly with the signatures
     * @param cutoff  the time at which the signatures were sufficient
     */
    private record Signatures(@NonNull History history, @NonNull Instant cutoff) {}

    public ListOfSignaturesHistoryProver(
            final long selfId,
            @NonNull final Map<Long, Bytes> sourceProofKeys,
            @NonNull final SchnorrKeyPair schnorrKeyPair,
            @NonNull final RosterTransitionWeights weights,
            @NonNull final Executor executor,
            @NonNull final HistoryLibrary library,
            @NonNull final HistorySubmissions submissions) {
        requireNonNull(sourceProofKeys);
        this.selfId = selfId;
        this.schnorrKeyPair = requireNonNull(schnorrKeyPair);
        this.weights = requireNonNull(weights);
        this.executor = requireNonNull(executor);
        this.library = requireNonNull(library);
        this.submissions = requireNonNull(submissions);
    }

    @Override
    public @NonNull Outcome advance(
            @NonNull final Instant now,
            @NonNull final HistoryProofConstruction construction,
            @NonNull final Bytes targetMetadata,
            @NonNull final Map<Long, Bytes> targetProofKeys,
            @NonNull final TssConfig tssConfig,
            Bytes ledgerId) {
        requireNonNull(now);
        requireNonNull(construction);
        requireNonNull(targetMetadata);
        requireNonNull(targetProofKeys);
        requireNonNull(tssConfig);
        if (!construction.hasAssemblyStartTime()) {
            // Controller called us too early; nothing to do without an assembled history extension
            return Outcome.InProgress.INSTANCE;
        }
        // Occasionally check whether it is still possible to gather enough valid signatures.
        boolean stillCollectingSignatures = true;
        final long elapsedSeconds = Math.max(
                1,
                now.getEpochSecond() - construction.assemblyStartTimeOrThrow().seconds());
        if (elapsedSeconds % INSUFFICIENT_SIGNATURES_CHECK_RETRY_SECS == 0) {
            stillCollectingSignatures = couldStillGetSufficientSignatures();
        }
        if (!stillCollectingSignatures) {
            log.info("Concluding construction #{} cannot obtain sufficient signatures", construction.constructionId());
            return new Outcome.Failed(INSUFFICIENT_SIGNATURES_FAILURE_REASON);
        }
        // Make sure we eventually add our own signature to the pool
        if (!signingNodeIds.contains(selfId) && signingFuture == null) {
            signingFuture = startSigningFuture(construction.constructionId(), targetMetadata, targetProofKeys);
            log.info("Started signing future for construction #{}", construction.constructionId());
        }
        if (!hasSufficientSignatures()) {
            return Outcome.InProgress.INSTANCE;
        }
        final var choice = requireNonNull(firstSufficientSignatures());
        // These are the witnesses for the prover: all valid signatures on that arrived before the cutoff time.
        final var signatures = verificationFutures.headMap(choice.cutoff(), true).values().stream()
                .map(CompletableFuture::join)
                .filter(v -> choice.history().equals(v.history()) && v.isValid())
                .collect(toMap(Verification::nodeId, v -> v.historySignature().signature(), (a, b) -> a, TreeMap::new));
        // Convert the ordered set of signatures into a list-of-signatures proof
        final var chainOfTrustProof = ChainOfTrustProof.newBuilder()
                .nodeSignatures(new NodeSignatures(signatures.entrySet().stream()
                        .map(e -> new NodeSignature(e.getKey(), e.getValue()))
                        .toList()))
                .build();
        // Compute the canonical target address book hash from the target roster & proof keys
        final var targetHash = HistoryLibrary.computeHash(
                library,
                weights.targetNodeIds(),
                weights::targetWeightOf,
                id -> targetProofKeys.getOrDefault(id, EMPTY_PUBLIC_KEY));
        // Build the history proof
        final var proof = HistoryProof.newBuilder()
                .targetProofKeys(proofKeyListFrom(targetProofKeys))
                .targetHistory(new History(targetHash, targetMetadata))
                .chainOfTrustProof(chainOfTrustProof)
                .build();
        return new Outcome.Completed(proof);
    }

    @Override
    public boolean addSignaturePublication(
            @NonNull final HistorySignaturePublication publication, @NonNull final Map<Long, Bytes> sourceProofKeys) {
        requireNonNull(publication);
        requireNonNull(sourceProofKeys);
        final long nodeId = publication.nodeId();
        if (!sourceProofKeys.containsKey(nodeId) || signingNodeIds.contains(nodeId)) {
            return false;
        }
        verificationFutures.put(publication.at(), verificationFuture(nodeId, publication.signature(), sourceProofKeys));
        signingNodeIds.add(nodeId);
        return true;
    }

    @Override
    public boolean cancelPendingWork() {
        boolean cancelled = false;
        if (signingFuture != null && !signingFuture.isDone()) {
            signingFuture.cancel(true);
            cancelled = true;
        }
        final var numCancelledVerifications = new AtomicInteger();
        verificationFutures.values().forEach(future -> {
            if (!future.isDone()) {
                future.cancel(true);
                numCancelledVerifications.incrementAndGet();
            }
        });
        if (numCancelledVerifications.get() > 0) {
            cancelled = true;
            log.info("Cancelled {} in-flight signature verifications", numCancelledVerifications.get());
        }
        return cancelled;
    }

    /**
     * Whether there are enough verified signatures to form a list-of-signatures proof.
     * @return true if there are enough verified signatures
     */
    private boolean hasSufficientSignatures() {
        return firstSufficientSignatures() != null;
    }

    /**
     * Whether there is still hope of collecting enough valid signatures to initiate a proof.
     */
    private boolean couldStillGetSufficientSignatures() {
        final Map<History, Long> historyWeights = new HashMap<>();
        long invalidWeight = 0;
        long maxValidWeight = 0;
        for (final var entry : verificationFutures.entrySet()) {
            final var verification = entry.getValue().join();
            if (verification.isValid()) {
                maxValidWeight = Math.max(
                        maxValidWeight,
                        historyWeights.merge(
                                verification.history(), weights.sourceWeightOf(verification.nodeId()), Long::sum));
            } else {
                invalidWeight += weights.sourceWeightOf(verification.nodeId());
            }
        }
        final long unassignedWeight = weights.totalSourceWeight()
                - invalidWeight
                - historyWeights.values().stream().mapToLong(Long::longValue).sum();
        return maxValidWeight + unassignedWeight >= weights.sourceWeightThreshold();
    }

    /**
     * Returns the first time at which this construction had sufficient verified signatures to
     * initiate a proof. Blocks until verifications are ready; this is acceptable because these
     * verification futures will generally have already been completed async in the interval
     * between the time the signature reaches consensus and the time the next round starts.
     */
    @Nullable
    private Signatures firstSufficientSignatures() {
        final Map<History, Long> historyWeights = new HashMap<>();
        for (final var entry : verificationFutures.entrySet()) {
            final var verification = entry.getValue().join();
            if (verification.isValid()) {
                final long weight = historyWeights.merge(
                        verification.history(), weights.sourceWeightOf(verification.nodeId()), Long::sum);
                if (weight >= weights.sourceWeightThreshold()) {
                    return new Signatures(verification.history(), entry.getKey());
                }
            }
        }
        return null;
    }

    /**
     * Returns a future that completes to a verification of the given assembly signature.
     */
    private CompletableFuture<Verification> verificationFuture(
            final long nodeId,
            @NonNull final HistorySignature historySignature,
            @NonNull final Map<Long, Bytes> genesisProofKeys) {
        requireNonNull(historySignature);
        requireNonNull(genesisProofKeys);
        return CompletableFuture.supplyAsync(
                        () -> {
                            final var message = encodeHistoryForSigning(historySignature.historyOrThrow());
                            final var proofKey = requireNonNull(
                                    genesisProofKeys.get(nodeId), () -> "Missing proof key for node " + nodeId);
                            final var isValid = library.verifySchnorr(historySignature.signature(), message, proofKey);
                            return new Verification(nodeId, historySignature, isValid);
                        },
                        executor)
                .exceptionally(e -> {
                    log.error("Error verifying signature from node {}", nodeId, e);
                    return new Verification(nodeId, historySignature, false);
                });
    }

    /**
     * Returns a future that completes when the node has signed its assembly and submitted
     * the signature.
     */
    private CompletableFuture<Void> startSigningFuture(
            final long constructionId,
            @NonNull final Bytes targetMetadata,
            @NonNull final Map<Long, Bytes> targetProofKeys) {
        requireNonNull(targetMetadata);
        requireNonNull(targetProofKeys);
        final var proofKeysSnapshot = Map.copyOf(targetProofKeys);
        return CompletableFuture.runAsync(
                        () -> {
                            final var targetHash = HistoryLibrary.computeHash(
                                    library,
                                    weights.targetNodeWeights().keySet(),
                                    weights::targetWeightOf,
                                    id -> proofKeysSnapshot.getOrDefault(id, EMPTY_PUBLIC_KEY));
                            final var history = new History(targetHash, targetMetadata);
                            final var message = encodeHistoryForSigning(history);
                            final var signature = library.signSchnorr(message, schnorrKeyPair.privateKey());
                            final var historySignature = new HistorySignature(history, signature);
                            submissions
                                    .submitAssemblySignature(constructionId, historySignature)
                                    .join();
                        },
                        executor)
                .exceptionally(e -> {
                    log.error("Error submitting signature for construction #{}", constructionId, e);
                    return null;
                });
    }

    private @NonNull Bytes encodeHistoryForSigning(@NonNull final History history) {
        requireNonNull(history);
        return concat(history.addressBookHash(), library.hashHintsVerificationKey(history.metadata()));
    }

    private @NonNull Bytes concat(@NonNull final Bytes a, @NonNull final Bytes b) {
        final var buffer = ByteBuffer.wrap(new byte[(int) a.length() + (int) b.length()]);
        a.writeTo(buffer);
        b.writeTo(buffer);
        return Bytes.wrap(buffer.array());
    }
}
