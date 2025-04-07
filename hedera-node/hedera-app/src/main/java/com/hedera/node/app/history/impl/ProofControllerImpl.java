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
import com.hedera.node.app.history.impl.ProofKeysAccessorImpl.SchnorrKeyPair;
import com.hedera.node.app.roster.RosterTransitionWeights;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.nio.ByteBuffer;
import java.time.Instant;
import java.util.Arrays;
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
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Default implementation of {@link ProofController}.
 */
public class ProofControllerImpl implements ProofController {
    private static final Logger log = LogManager.getLogger(ProofControllerImpl.class);
    private static final Comparator<ProofKey> PROOF_KEY_COMPARATOR = Comparator.comparingLong(ProofKey::nodeId);
    private static final Bytes EMPTY_PUBLIC_KEY = Bytes.wrap(new byte[32]);
    private static final int INSUFFICIENT_SIGNATURES_CHECK_RETRY_SECS = 10;
    private static final String INSUFFICIENT_SIGNATURES_FAILURE_REASON = "insufficient signatures";

    /**
     * Because the native library allocates so much memory for a proof, and cancelling an in-progress
     * {@code proofFuture} doesn't guarantee that memory is released immediately, we have to ensure
     * no two instances of {@link ProofControllerImpl} ever run proofs concurrently; hence a static field.
     */
    @Nullable
    private static CompletableFuture<Void> RUNNING_PROOF_FUTURE = null;

    public static final String PROOF_COMPLETE_MSG = "History proof constructed";

    public static final int ADDRESS_BOOK_HASH_LEN = 32;

    private final long selfId;

    /**
     * Null if this controller transitions from the genesis roster.
     */
    @Nullable
    private final Bytes ledgerId;

    private final Executor executor;
    private final SchnorrKeyPair schnorrKeyPair;
    private final HistoryLibrary library;
    private final HistorySubmissions submissions;
    private final RosterTransitionWeights weights;
    private final Consumer<HistoryProof> proofConsumer;
    private final Map<Long, HistoryProofVote> votes = new TreeMap<>();
    private final Map<Long, Bytes> targetProofKeys = new TreeMap<>();
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
     *
     * @param history the assembly with the signatures
     * @param cutoff  the time at which the signatures were sufficient
     */
    private record Signatures(@NonNull History history, @NonNull Instant cutoff) {}

    public ProofControllerImpl(
            final long selfId,
            @NonNull final SchnorrKeyPair schnorrKeyPair,
            @Nullable final Bytes ledgerId,
            @NonNull final HistoryProofConstruction construction,
            @NonNull final RosterTransitionWeights weights,
            @NonNull final Executor executor,
            @NonNull final HistoryLibrary library,
            @NonNull final HistorySubmissions submissions,
            @NonNull final List<ProofKeyPublication> keyPublications,
            @NonNull final List<HistorySignaturePublication> signaturePublications,
            @NonNull final Map<Long, HistoryProofVote> votes,
            @NonNull final Consumer<HistoryProof> proofConsumer) {
        this.selfId = selfId;
        this.ledgerId = ledgerId;
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
            @NonNull final WritableHistoryStore historyStore,
            final boolean isActive) {
        if (construction.hasTargetProof() || construction.hasFailureReason()) {
            return;
        }
        targetMetadata = metadata;
        if (targetMetadata == null) {
            if (isActive) {
                ensureProofKeyPublished();
            }
        } else if (construction.hasAssemblyStartTime()) {
            boolean stillCollectingSignatures = true;
            final long elapsedSeconds = Math.max(
                    1,
                    now.getEpochSecond()
                            - construction.assemblyStartTimeOrThrow().seconds());
            if (elapsedSeconds % INSUFFICIENT_SIGNATURES_CHECK_RETRY_SECS == 0) {
                stillCollectingSignatures = couldStillGetSufficientSignatures();
            }
            if (stillCollectingSignatures && isActive) {
                if (!votes.containsKey(selfId) && proofFuture == null) {
                    if (hasSufficientSignatures()) {
                        if (RUNNING_PROOF_FUTURE != null && !RUNNING_PROOF_FUTURE.isDone()) {
                            log.warn(
                                    "Proof future for construction #{} must wait until previous finished",
                                    construction.constructionId());
                        }
                        proofFuture = Optional.ofNullable(RUNNING_PROOF_FUTURE)
                                .orElse(CompletableFuture.completedFuture(null))
                                .thenCompose(ignore -> startProofFuture());
                        log.info("Created proof future for construction #{}", construction.constructionId());
                    } else if (!signingNodeIds.contains(selfId) && signingFuture == null) {
                        signingFuture = startSigningFuture();
                        log.info("Started signing future for construction #{}", construction.constructionId());
                    }
                }
            } else if (!stillCollectingSignatures) {
                log.info(
                        "Failed construction #{} due to {}",
                        construction.constructionId(),
                        INSUFFICIENT_SIGNATURES_FAILURE_REASON);
                construction = historyStore.failForReason(
                        construction.constructionId(), INSUFFICIENT_SIGNATURES_FAILURE_REASON);
            }
        } else {
            if (shouldAssemble(now)) {
                log.info("Assembly start time for construction #{} is {}", construction.constructionId(), now);
                construction = historyStore.setAssemblyTime(construction.constructionId(), now);
                if (isActive) {
                    signingFuture = startSigningFuture();
                }
            } else if (isActive) {
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
            log.info("{} (#{})", PROOF_COMPLETE_MSG, construction.constructionId());
            if (historyStore.getActiveConstruction().constructionId() == construction.constructionId()) {
                proofConsumer.accept(proof);
                if (ledgerId == null) {
                    final var ledgerId = concat(proof.sourceAddressBookHash(), library.snarkVerificationKey());
                    historyStore.setLedgerId(ledgerId);
                    log.info("Set ledger id to {}", ledgerId);
                }
            }
        });
    }

    @Override
    public void cancelPendingWork() {
        final var sb =
                new StringBuilder("Cancelled work on proof construction #").append(construction.constructionId());
        if (publicationFuture != null && !publicationFuture.isDone()) {
            sb.append("\n  * In-flight publication");
            publicationFuture.cancel(true);
        }
        if (signingFuture != null && !signingFuture.isDone()) {
            sb.append("\n  * In-flight signing");
            signingFuture.cancel(true);
        }
        if (proofFuture != null && !proofFuture.isDone()) {
            sb.append("\n  * In-flight proof");
            proofFuture.cancel(true);
        }
        final var numCancelledVerifications = new AtomicInteger();
        verificationFutures.values().forEach(future -> {
            if (!future.isDone()) {
                numCancelledVerifications.incrementAndGet();
                future.cancel(true);
            }
        });
        if (numCancelledVerifications.get() > 0) {
            sb.append("\n  * ").append(numCancelledVerifications.get()).append(" in-flight verifications");
        }
        log.info(sb.toString());
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
     *
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
        final var nodeIds = weights.targetNodeWeights().keySet().stream()
                .mapToLong(Long::longValue)
                .toArray();
        final var targetWeights =
                Arrays.stream(nodeIds).map(weights::targetWeightOf).toArray();
        final var proofKeysArray = Arrays.stream(nodeIds)
                .mapToObj(id -> proofKeys.getOrDefault(id, EMPTY_PUBLIC_KEY).toByteArray())
                .toArray(byte[][]::new);
        return CompletableFuture.runAsync(
                        () -> {
                            final var targetHash = library.hashAddressBook(targetWeights, proofKeysArray);
                            final var history = new History(targetHash, targetMetadata);
                            final var message = encodeHistoryForSigning(history);
                            final var signature = library.signSchnorr(message, schnorrKeyPair.privateKey());
                            final var historySignature = new HistorySignature(history, signature);
                            submissions
                                    .submitAssemblySignature(construction.constructionId(), historySignature)
                                    .join();
                        },
                        executor)
                .exceptionally(e -> {
                    log.error("Error submitting signature", e);
                    return null;
                });
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
                .collect(toMap(Verification::nodeId, v -> v.historySignature().signature(), (a, b) -> a, TreeMap::new));
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
        final long[] sourceNodeIds = weights.sourceNodeWeights().keySet().stream()
                .mapToLong(Long::longValue)
                .toArray();
        final long[] targetNodeIds = weights.targetNodeWeights().keySet().stream()
                .mapToLong(Long::longValue)
                .toArray();
        final var sourceWeights =
                Arrays.stream(sourceNodeIds).map(weights::sourceWeightOf).toArray();
        final var targetWeights =
                Arrays.stream(targetNodeIds).map(weights::targetWeightOf).toArray();
        final var sourceProofKeysArray = Arrays.stream(sourceNodeIds)
                .mapToObj(
                        id -> sourceProofKeys.getOrDefault(id, EMPTY_PUBLIC_KEY).toByteArray())
                .toArray(byte[][]::new);
        final var targetProofKeysArray = Arrays.stream(targetNodeIds)
                .mapToObj(
                        id -> targetProofKeys.getOrDefault(id, EMPTY_PUBLIC_KEY).toByteArray())
                .toArray(byte[][]::new);
        final long inProgressId = construction.constructionId();
        final var proofKeyList = proofKeyListFrom(targetProofKeys);
        RUNNING_PROOF_FUTURE = CompletableFuture.runAsync(
                        () -> {
                            final var sourceHash = library.hashAddressBook(sourceWeights, sourceProofKeysArray);
                            final var targetHash = library.hashAddressBook(targetWeights, targetProofKeysArray);
                            // Nodes that did not submit signatures have null in their array index here
                            final var verifyingSignatures = Arrays.stream(sourceNodeIds)
                                    .mapToObj(i -> Optional.ofNullable(signatures.get(i))
                                            .map(Bytes::toByteArray)
                                            .orElse(null))
                                    .toArray(byte[][]::new);
                            log.info("Starting chain-of-trust proof for construction #{}", inProgressId);
                            // If the ledger id is set, its first 32 bytes is the genesis book hash
                            final var genesisAddressBookHash = Optional.ofNullable(ledgerId)
                                    .map(l -> Bytes.wrap(l.toByteArray(0, ADDRESS_BOOK_HASH_LEN)))
                                    .orElse(sourceHash);
                            final var targetMetadataHash = library.hashHintsVerificationKey(targetMetadata);
                            final var proof = library.proveChainOfTrust(
                                    genesisAddressBookHash,
                                    sourceProof,
                                    sourceWeights,
                                    sourceProofKeysArray,
                                    targetWeights,
                                    targetProofKeysArray,
                                    verifyingSignatures,
                                    targetMetadataHash);
                            log.info("Finished chain-of-trust proof for construction #{}", inProgressId);
                            final var metadataProof = HistoryProof.newBuilder()
                                    .sourceAddressBookHash(sourceHash)
                                    .targetProofKeys(proofKeyList)
                                    .targetHistory(new History(targetHash, targetMetadata))
                                    .proof(proof)
                                    .build();
                            submissions
                                    .submitProofVote(inProgressId, metadataProof)
                                    .join();
                        },
                        executor)
                .exceptionally(e -> {
                    log.error("Error submitting proof vote", e);
                    return null;
                });
        return RUNNING_PROOF_FUTURE;
    }

    /**
     * Whether the construction has sufficient verified signatures to initiate a proof.
     */
    private boolean hasSufficientSignatures() {
        return firstSufficientSignatures() != null;
    }

    /**
     * Whether there is no hope of collecting enough valid signatures to initiate a proof.
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
        long unassignedWeight = weights.totalSourceWeight()
                - invalidWeight
                - historyWeights.values().stream().mapToLong(Long::longValue).sum();
        return maxValidWeight + unassignedWeight >= weights.sourceWeightThreshold();
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
                            final var message = encodeHistoryForSigning(historySignature.historyOrThrow());
                            final var proofKey = requireNonNull(targetProofKeys.get(nodeId));
                            final var isValid = library.verifySchnorr(historySignature.signature(), message, proofKey);
                            return new Verification(nodeId, historySignature, isValid);
                        },
                        executor)
                .exceptionally(e -> {
                    log.error("Error verifying signature", e);
                    return new Verification(nodeId, historySignature, false);
                });
    }

    /**
     * Encodes the given history as a message for signing.
     * @param history the history
     * @return the message
     */
    private @NonNull Bytes encodeHistoryForSigning(@NonNull final History history) {
        requireNonNull(history);
        return concat(history.addressBookHash(), library.hashHintsVerificationKey(history.metadata()));
    }

    /**
     * Concatenates the given bytes.
     * @param a the first bytes
     * @param b the second bytes
     * @return the concatenation
     */
    private @NonNull Bytes concat(@NonNull final Bytes a, @NonNull final Bytes b) {
        final var c = ByteBuffer.wrap(new byte[(int) a.length() + (int) b.length()]);
        a.writeTo(c);
        b.writeTo(c);
        return Bytes.wrap(c.array());
    }
}
