// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.history.impl;

import static com.hedera.hapi.util.HapiUtils.asInstant;
import static java.util.Objects.requireNonNull;
import static java.util.stream.Collectors.groupingBy;
import static java.util.stream.Collectors.summingLong;
import static java.util.stream.Collectors.toMap;

import com.hedera.hapi.node.state.history.History;
import com.hedera.hapi.node.state.history.HistoryProof;
import com.hedera.hapi.node.state.history.HistoryProofConstruction;
import com.hedera.hapi.node.state.history.HistoryProofVote;
import com.hedera.hapi.node.state.history.HistorySignature;
import com.hedera.hapi.node.state.history.ProofKey;
import com.hedera.node.app.history.HistoryLibrary;
import com.hedera.node.app.history.ReadableHistoryStore.HistorySignaturePublication;
import com.hedera.node.app.history.ReadableHistoryStore.ProofKeyPublication;
import com.hedera.node.app.history.WritableHistoryStore;
import com.hedera.node.app.roster.RosterTransitionWeights;
import com.hedera.node.app.tss.TssKeyPair;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.time.Instant;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

/**
 * Default implementation of {@link ProofController}.
 */
public class ProofControllerImpl implements ProofController {
    private static final Comparator<ProofKey> PROOF_KEY_COMPARATOR = Comparator.comparingLong(ProofKey::nodeId);

    private final long selfId;

    /**
     * Null if this controller transitions from the genesis roster.
     */
    @Nullable
    private final Bytes ledgerId;

    private final Executor executor;
    private final TssKeyPair schnorrKeyPair;
    private final HistoryLibrary library;
    private final HistoryLibraryCodec codec;
    private final HistorySubmissions submissions;
    private final RosterTransitionWeights weights;
    private final Consumer<HistoryProof> proofConsumer;
    private final Map<Long, HistoryProofVote> votes = new HashMap<>();
    private final Map<Long, Bytes> targetProofKeys = new HashMap<>();
    private final Set<Long> signingNodeIds = new HashSet<>();
    private final NavigableMap<Instant, CompletableFuture<Verification>> verificationFutures = new TreeMap<>();

    /**
     * Once set, the metadata to be proven as associated to the target address book hash.
     */
    @Nullable
    private Bytes targetMetadata;

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
     * If not null, a future that resolves when this node signs its assembled history.
     */
    @Nullable
    private CompletableFuture<Void> signingFuture;

    /**
     * If not null, a future that resolves when this node finishes assembling its proof that
     * extends the chain of trust, and voting for that proof as the consensus proof.
     */
    @Nullable
    private CompletableFuture<Void> proofFuture;

    /**
     * A party's verified signature on a new piece of {@code (address book hash, metadata)} history.
     *
     * @param nodeId the node's id
     * @param historySignature its history signature
     * @param isValid whether the signature is valid
     */
    private record Verification(long nodeId, @NonNull HistorySignature historySignature, boolean isValid) {
        public @NonNull History history() {
            return historySignature.historyOrThrow();
        }
    }

    /**
     * A summary of the signatures to be used in a proof.
     *
     * @param history the assembly with the signatures
     * @param cutoff the time at which the signatures were sufficient
     */
    private record Signatures(@NonNull History history, @NonNull Instant cutoff) {}

    public ProofControllerImpl(
            final long selfId,
            @NonNull final TssKeyPair schnorrKeyPair,
            @Nullable final Bytes ledgerId,
            @NonNull final HistoryProofConstruction construction,
            @NonNull final RosterTransitionWeights weights,
            @NonNull final Executor executor,
            @NonNull final HistoryLibrary library,
            @NonNull final HistoryLibraryCodec codec,
            @NonNull final HistorySubmissions submissions,
            @NonNull final List<ProofKeyPublication> keyPublications,
            @NonNull final List<HistorySignaturePublication> signaturePublications,
            @NonNull final Map<Long, HistoryProofVote> votes,
            @NonNull final Consumer<HistoryProof> proofConsumer) {
        this.selfId = selfId;
        this.ledgerId = ledgerId;
        this.codec = requireNonNull(codec);
        this.executor = requireNonNull(executor);
        this.library = requireNonNull(library);
        this.submissions = requireNonNull(submissions);
        this.weights = requireNonNull(weights);
        this.construction = requireNonNull(construction);
        this.proofConsumer = requireNonNull(proofConsumer);
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
            signaturePublications.forEach(this::addSignaturePublication);
        }
    }

    @Override
    public long constructionId() {
        return construction.constructionId();
    }

    @Override
    public boolean isStillInProgress() {
        return !construction.hasTargetProof();
    }

    @Override
    public void advanceConstruction(
            @NonNull final Instant now,
            @Nullable final Bytes metadata,
            @NonNull final WritableHistoryStore historyStore) {
        if (construction.hasTargetProof()) {
            return;
        }
        targetMetadata = metadata;
        if (targetMetadata == null) {
            ensureProofKeyPublished();
        } else if (construction.hasAssemblyStartTime()) {
            if (!votes.containsKey(selfId) && proofFuture == null) {
                if (hasSufficientSignatures()) {
                    proofFuture = startProofFuture();
                } else if (!signingNodeIds.contains(selfId) && signingFuture == null) {
                    signingFuture = startSigningFuture();
                }
            }
        } else {
            if (shouldAssemble(now)) {
                construction = historyStore.setAssemblyTime(construction.constructionId(), now);
                signingFuture = startSigningFuture();
            } else {
                ensureProofKeyPublished();
            }
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
    public boolean addSignaturePublication(@NonNull final HistorySignaturePublication publication) {
        requireNonNull(publication);
        final long nodeId = publication.nodeId();
        if (!construction.hasTargetProof() && targetProofKeys.containsKey(nodeId) && !signingNodeIds.contains(nodeId)) {
            verificationFutures.put(
                    publication.at(), verificationFuture(publication.nodeId(), publication.signature()));
            signingNodeIds.add(publication.nodeId());
            return true;
        }
        return false;
    }

    @Override
    public void addProofVote(
            final long nodeId, @NonNull final HistoryProofVote vote, @NonNull final WritableHistoryStore historyStore) {
        requireNonNull(vote);
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
        maybeWinningProof.ifPresent(proof -> {
            construction = historyStore.completeProof(construction.constructionId(), proof);
            if (historyStore.getActiveConstruction().constructionId() == construction.constructionId()) {
                proofConsumer.accept(proof);
                if (ledgerId == null) {
                    requireNonNull(targetMetadata);
                    final var encodedId = codec.encodeLedgerId(proof.sourceAddressBookHash(), targetMetadata);
                    historyStore.setLedgerId(encodedId);
                }
            }
        });
    }

    @Override
    public void cancelPendingWork() {
        if (publicationFuture != null) {
            publicationFuture.cancel(true);
        }
        if (signingFuture != null) {
            signingFuture.cancel(true);
        }
        if (proofFuture != null) {
            proofFuture.cancel(true);
        }
        verificationFutures.values().forEach(future -> future.cancel(true));
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
            publicationFuture = CompletableFuture.runAsync(
                    () -> submissions
                            .submitProofKeyPublication(schnorrKeyPair.publicKey())
                            .join(),
                    executor);
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
     * Returns a future that completes when the node has signed its assembly and submitted
     * the signature.
     */
    private CompletableFuture<Void> startSigningFuture() {
        requireNonNull(targetMetadata);
        final var proofKeys = Map.copyOf(targetProofKeys);
        return CompletableFuture.runAsync(
                () -> {
                    final var targetBook = codec.encodeAddressBook(weights.targetNodeWeights(), proofKeys);
                    final var targetHash = library.hashAddressBook(targetBook);
                    final var history = new History(targetHash, targetMetadata);
                    final var message = codec.encodeHistory(history);
                    final var signature = library.signSchnorr(message, schnorrKeyPair.privateKey());
                    final var historySignature = new HistorySignature(history, signature);
                    submissions
                            .submitAssemblySignature(construction.constructionId(), historySignature)
                            .join();
                },
                executor);
    }

    /**
     * Returns a future that completes when the node has completed its metadata proof and submitted
     * the corresponding vote.
     */
    private CompletableFuture<Void> startProofFuture() {
        final var choice = requireNonNull(firstSufficientSignatures());
        final var signatures = verificationFutures.headMap(choice.cutoff(), true).values().stream()
                .map(CompletableFuture::join)
                .filter(v -> choice.history().equals(v.history()) && v.isValid())
                .collect(toMap(Verification::nodeId, v -> v.historySignature().signature()));
        final Bytes sourceProof;
        final Map<Long, Bytes> sourceProofKeys;
        if (construction.hasSourceProof()) {
            sourceProof = construction.sourceProofOrThrow().proof();
            sourceProofKeys = proofKeyMapFrom(construction.sourceProofOrThrow());
        } else {
            sourceProof = null;
            sourceProofKeys = Map.copyOf(targetProofKeys);
        }
        final var targetMetadata = requireNonNull(this.targetMetadata);
        return CompletableFuture.runAsync(
                () -> {
                    final var sourceBook = codec.encodeAddressBook(weights.sourceNodeWeights(), sourceProofKeys);
                    final var sourceHash = library.hashAddressBook(sourceBook);
                    final var targetBook = codec.encodeAddressBook(weights.targetNodeWeights(), targetProofKeys);
                    final var targetHash = library.hashAddressBook(targetBook);
                    final var proof = library.proveChainOfTrust(
                            Optional.ofNullable(ledgerId).orElseGet(() -> library.hashAddressBook(sourceBook)),
                            sourceProof,
                            sourceBook,
                            signatures,
                            targetHash,
                            targetMetadata);
                    final var metadataProof = HistoryProof.newBuilder()
                            .sourceAddressBookHash(sourceHash)
                            .targetProofKeys(proofKeyListFrom(targetProofKeys))
                            .targetHistory(new History(targetHash, targetMetadata))
                            .proof(proof)
                            .build();
                    submissions
                            .submitProofVote(construction.constructionId(), metadataProof)
                            .join();
                },
                executor);
    }

    /**
     * Whether the construction has sufficient verified signatures to initiate a proof.
     */
    private boolean hasSufficientSignatures() {
        return firstSufficientSignatures() != null;
    }

    /**
     * Returns the first time at which this construction had sufficient verified signatures to
     * initiate a proof. Blocks until verifications are ready; this is acceptable because these
     * verification future will generally have already been completed async in the interval
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
     * Returns the weight of the nodes in the target roster that have published their proof keys.
     */
    private long publishedWeight() {
        return targetProofKeys.keySet().stream()
                .mapToLong(weights::targetWeightOf)
                .sum();
    }

    /**
     * Returns the proof keys used for the given proof.
     *
     * @param proof the proof
     * @return the proof keys
     */
    private static Map<Long, Bytes> proofKeyMapFrom(@NonNull final HistoryProof proof) {
        return proof.targetProofKeys().stream().collect(toMap(ProofKey::nodeId, ProofKey::key));
    }

    /**
     * Returns a list of proof keys from the given map.
     *
     * @param proofKeys the proof keys in a map
     * @return the list of proof keys
     */
    private static List<ProofKey> proofKeyListFrom(@NonNull final Map<Long, Bytes> proofKeys) {
        return proofKeys.entrySet().stream()
                .map(entry -> new ProofKey(entry.getKey(), entry.getValue()))
                .sorted(PROOF_KEY_COMPARATOR)
                .toList();
    }

    /**
     * Returns a future that completes to a verification of the given assembly signature.
     *
     * @param nodeId the node ID
     * @return the future
     */
    private CompletableFuture<Verification> verificationFuture(
            final long nodeId, @NonNull final HistorySignature historySignature) {
        return CompletableFuture.supplyAsync(
                () -> {
                    final var message = codec.encodeHistory(historySignature.historyOrThrow());
                    final var proofKey = requireNonNull(targetProofKeys.get(nodeId));
                    final var isValid = library.verifySchnorr(historySignature.signature(), proofKey, message);
                    return new Verification(nodeId, historySignature, isValid);
                },
                executor);
    }
}
