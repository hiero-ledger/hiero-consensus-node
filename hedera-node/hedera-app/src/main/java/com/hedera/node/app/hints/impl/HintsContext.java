// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.hints.impl;

import static java.util.Objects.requireNonNull;
import static java.util.concurrent.TimeUnit.SECONDS;
import static java.util.stream.Collectors.toMap;

import com.hedera.hapi.node.state.hints.HintsConstruction;
import com.hedera.hapi.node.state.hints.NodePartyId;
import com.hedera.hapi.services.auxiliary.hints.HintsPartialSignatureTransactionBody;
import com.hedera.node.app.hints.HintsLibrary;
import com.hedera.node.config.data.TssConfig;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.config.api.Configuration;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import javax.inject.Inject;
import javax.inject.Singleton;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * The hinTS context that can be used to request hinTS signatures using the latest
 * complete construction, if there is one. See {@link #setConstruction(HintsConstruction)}
 * for the ways the context can have a construction set.
 */
@Singleton
public class HintsContext {
    private static final Logger log = LogManager.getLogger(HintsContext.class);

    // For a quiesced network, a hinTS signature could in principle take an entire day to aggregate
    private static final Duration SIGNING_ATTEMPT_TIMEOUT = Duration.ofDays(1);

    public static final String INVALID_AGGREGATE_SIGNATURE_MESSAGE = "Aggregate hinTS signature was invalid";

    private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();

    private final HintsLibrary library;
    private final Supplier<Configuration> configProvider;
    private final HintsSigningMetrics signingMetrics;

    @Nullable
    private HintsConstruction construction;

    @Nullable
    private Map<Long, Integer> nodePartyIds;

    @Inject
    public HintsContext(
            @NonNull final HintsLibrary library,
            @NonNull final Supplier<Configuration> configProvider,
            @NonNull final HintsSigningMetrics signingMetrics) {
        this.library = requireNonNull(library);
        this.configProvider = requireNonNull(configProvider);
        this.signingMetrics = requireNonNull(signingMetrics);
    }

    /**
     * Sets the active hinTS construction as the signing context. Called in three places,
     * <ol>
     *     <li>In the startup phase, when restarting from a state whose active hinTS
     *     construction (and possibly next construction) had complete schemes.</li>
     *     <li>In the runtime phase, on finishing the preprocessing work for a hinTS
     *     construction (either the bootstrap construction or for a roster with
     *     rebalanced weights after a stake period boundary).</li>
     *     <li>In the restart runtime phase, when swapping in a newly adopted roster's
     *     hinTS construction and purging votes for the previous construction.</li>
     * </ol>
     *
     * @param construction the construction to start using for signing
     * @throws IllegalArgumentException if either construction does not have a hinTS scheme
     */
    public void setConstruction(@NonNull final HintsConstruction construction) {
        requireNonNull(construction);
        if (!construction.hasHintsScheme()) {
            throw new IllegalArgumentException(
                    "Given construction #" + construction.constructionId() + " has no hinTS scheme");
        }
        this.construction = requireNonNull(construction);
        nodePartyIds = asNodePartyIds(construction.hintsSchemeOrThrow().nodePartyIds());
    }

    /**
     * Returns true if the signing context is ready.
     * @return true if the context is ready
     */
    public boolean isReady() {
        return construction != null && construction.hasHintsScheme();
    }

    /**
     * Returns the active verification key, or throws if the context is not ready.
     * @return the verification key
     */
    public Bytes verificationKeyOrThrow() {
        throwIfNotReady();
        return requireNonNull(construction)
                .hintsSchemeOrThrow()
                .preprocessedKeysOrThrow()
                .verificationKey();
    }

    /**
     * Returns the active construction ID, or throws if the context is not ready.
     * @return the construction ID
     */
    public long constructionIdOrThrow() {
        throwIfNotReady();
        return requireNonNull(construction).constructionId();
    }

    /**
     * Returns the active construction, or null if none is active (at genesis).
     * @return the active construction
     */
    public @Nullable HintsConstruction activeConstruction() {
        return construction;
    }

    /**
     * Validates a partial signature transaction body under the current hinTS construction.
     * @param nodeId the node ID
     * @param crs the CRS to validate under
     * @param body the transaction body
     * @return true if the body is valid
     */
    public boolean validate(
            final long nodeId, @Nullable final Bytes crs, @NonNull final HintsPartialSignatureTransactionBody body) {
        requireNonNull(crs);
        if (construction == null || nodePartyIds == null) {
            return false;
        }
        if (construction.constructionId() == body.constructionId() && nodePartyIds.containsKey(nodeId)) {
            final var preprocessedKeys = construction.hintsSchemeOrThrow().preprocessedKeysOrThrow();
            final var aggregationKey = preprocessedKeys.aggregationKey();
            final var partyId = nodePartyIds.get(nodeId);
            return library.verifyBls(crs, body.partialSignature(), body.message(), aggregationKey, partyId);
        }
        return false;
    }

    /**
     * Creates a new asynchronous signing process for the given block hash.
     * @param blockHash     the block hash
     * @param onCompletion a callback to run when the signing process completes
     * @return the signing process
     */
    public @NonNull Signing newSigning(@NonNull final Bytes blockHash, @NonNull final Runnable onCompletion) {
        requireNonNull(blockHash);
        requireNonNull(onCompletion);
        throwIfNotReady();
        requireNonNull(construction);
        final var preprocessedKeys = construction.hintsSchemeOrThrow().preprocessedKeysOrThrow();
        final var verificationKey = preprocessedKeys.verificationKey();
        final Map<Long, Long> nodeWeights = new HashMap<>();
        long totalWeight = 0L;
        for (final var nodePartyId : construction.hintsSchemeOrThrow().nodePartyIds()) {
            totalWeight += nodePartyId.partyWeight();
            nodeWeights.put(nodePartyId.nodeId(), nodePartyId.partyWeight());
        }
        final var tssConfig = configProvider.get().getConfigData(TssConfig.class);
        final int divisor = Math.max(1, tssConfig.signingThresholdDivisor());
        final long threshold = totalWeight / divisor;
        return new Signing(
                construction.constructionId(),
                blockHash,
                threshold,
                divisor,
                preprocessedKeys.aggregationKey(),
                requireNonNull(nodePartyIds),
                nodeWeights,
                verificationKey,
                onCompletion,
                tssConfig.validateBlockSignatures());
    }

    /**
     * Returns the party assignments as a map of node IDs to party IDs.
     * @param nodePartyIds the party assignments
     * @return the map of node IDs to party IDs
     */
    private static Map<Long, Integer> asNodePartyIds(@NonNull final List<NodePartyId> nodePartyIds) {
        return nodePartyIds.stream().collect(toMap(NodePartyId::nodeId, NodePartyId::partyId));
    }

    /**
     * Throws an exception if the context is not ready.
     */
    private void throwIfNotReady() {
        if (!isReady()) {
            throw new IllegalStateException("Signing context not ready");
        }
    }

    /**
     * A signing process spawned from this context.
     */
    public class Signing {
        private final long constructionId;
        private final long startNanos;
        private final long thresholdWeight;
        private final long thresholdDenominator;
        private final Bytes blockHash;
        private final Bytes aggregationKey;
        private final Bytes verificationKey;

        @SuppressWarnings("unused")
        private final boolean validateSignature;

        private final Map<Long, Long> nodeWeights;
        private final Map<Long, Integer> partyIds;
        private final CompletableFuture<Bytes> future = new CompletableFuture<>();
        private final ConcurrentMap<Integer, Bytes> signatures = new ConcurrentHashMap<>();
        private final ConcurrentMap<Integer, Long> incorporatedNodeIds = new ConcurrentHashMap<>();
        private final AtomicLong weightOfSignatures = new AtomicLong();
        private final AtomicBoolean completed = new AtomicBoolean();

        public Signing(
                final long constructionId,
                @NonNull final Bytes blockHash,
                final long thresholdWeight,
                final long thresholdDenominator,
                @NonNull final Bytes aggregationKey,
                @NonNull final Map<Long, Integer> partyIds,
                @NonNull final Map<Long, Long> nodeWeights,
                @NonNull final Bytes verificationKey,
                @NonNull final Runnable onCompletion,
                final boolean validateSignature) {
            this.constructionId = constructionId;
            this.startNanos = System.nanoTime();
            this.thresholdWeight = thresholdWeight;
            this.validateSignature = validateSignature;
            this.thresholdDenominator = thresholdDenominator;
            requireNonNull(onCompletion);
            this.blockHash = requireNonNull(blockHash);
            this.aggregationKey = requireNonNull(aggregationKey);
            this.partyIds = requireNonNull(partyIds);
            this.nodeWeights = requireNonNull(nodeWeights);
            this.verificationKey = requireNonNull(verificationKey);
            executor.schedule(
                    () -> {
                        if (!future.isDone()) {
                            log.warn(
                                    "Completing signing attempt on '{}' without obtaining a signature (had {} from parties {} for total weight {}/{} required)",
                                    blockHash,
                                    signatures.size(),
                                    signatures.keySet(),
                                    weightOfSignatures.get(),
                                    thresholdWeight);
                            signingMetrics.recordAttemptCompletedWithoutSignature();
                        }
                        onCompletion.run();
                    },
                    SIGNING_ATTEMPT_TIMEOUT.getSeconds(),
                    SECONDS);
        }

        /**
         * The future that will complete when sufficient partial signatures have been aggregated.
         * @return the future
         */
        public CompletableFuture<Bytes> future() {
            return future;
        }

        /**
         * The verification key of the hinTS scheme being used for the signing attempt.
         */
        public Bytes verificationKey() {
            return verificationKey;
        }

        /**
         * Incorporates a node's pre-validated partial signature into the aggregation. If including this node's
         * weight passes the required threshold, completes the future returned from {@link #future()} with the
         * aggregated signature.
         *
         * @param crs the final CRS used by the network
         * @param nodeId the node ID
         * @param signature the pre-validated partial signature
         * @param bodyConstructionId the construction id the partial signature was produced against
         */
        public void incorporateValid(
                @NonNull final Bytes crs,
                final long nodeId,
                @NonNull final Bytes signature,
                final long bodyConstructionId) {
            requireNonNull(crs);
            requireNonNull(signature);
            if (completed.get()) {
                return;
            }
            // Pin signing context: reject partials that were produced against a different construction
            // than the one this Signing was created under. This prevents a race where the HintsContext
            // current construction swaps mid-aggregation and cross-construction partials get mixed into
            // an aggregate signed under the stale aggregation/verification key.
            if (bodyConstructionId != constructionId) {
                log.warn(
                        "Rejecting stale partial signature for block '{}' from node {} (partial's constructionId={}, signing pinned to constructionId={})",
                        blockHashPrefix(),
                        nodeId,
                        bodyConstructionId,
                        constructionId);
                return;
            }
            final var partyId = partyIds.get(nodeId);
            if (partyId == null) {
                log.warn(
                        "Rejecting partial signature for block '{}' from node {} not in pinned construction #{} party map",
                        blockHashPrefix(),
                        nodeId,
                        constructionId);
                return;
            }
            if (signatures.put(partyId, signature) != null) {
                // Each valid signature should only accumulate weight once, so abort on duplicates
                return;
            }
            incorporatedNodeIds.put(partyId, nodeId);
            final var weight = nodeWeights.getOrDefault(nodeId, 0L);
            final var totalWeight = weightOfSignatures.addAndGet(weight);
            log.debug(
                    "Incorporated partial sig for block '{}' from node {} (partyId={}, construction=#{}, weight={}, total={}/{} req, parties={})",
                    blockHashPrefix(),
                    nodeId,
                    partyId,
                    constructionId,
                    weight,
                    totalWeight,
                    thresholdWeight,
                    signatures.size());
            // For block hash signing, always require strictly greater than threshold
            final boolean reachedThreshold = totalWeight > thresholdWeight;
            if (reachedThreshold && completed.compareAndSet(false, true)) {
                final var aggregatedSignature =
                        library.aggregateSignatures(crs, aggregationKey, verificationKey, signatures);
                // Always self-verify the aggregate at the producing site (belt-and-suspenders). Previously this
                // was gated behind tssConfig.validateBlockSignatures() which defaults to false, allowing bad
                // aggregates to silently escape to the block stream where the validator would catch them ~15s
                // later. Fail loudly here instead.
                final boolean valid = library.verifyAggregate(
                        aggregatedSignature, blockHash, verificationKey, 1L, thresholdDenominator);
                if (valid) {
                    log.info(
                            "Aggregated block signature for '{}' (construction=#{}, vkHash={}, sigPrefix={}, nodeIds={}, weight={}/{})",
                            blockHashPrefix(),
                            constructionId,
                            bytesHashPrefix(verificationKey),
                            bytesPrefix(aggregatedSignature),
                            incorporatedNodeIds.values(),
                            totalWeight,
                            thresholdWeight);
                    future.complete(aggregatedSignature);
                    final long elapsedNanos = System.nanoTime() - startNanos;
                    signingMetrics.recordSignatureProduced(elapsedNanos / 1_000_000L);
                } else {
                    log.error(
                            "Self-verify FAILED for block '{}' before emit (construction=#{}, vkHash={}, sigPrefix={}, aggSigLen={}, nodeIds={}, parties={}, weight={}/{} thresh=1/{})",
                            blockHashPrefix(),
                            constructionId,
                            bytesHashPrefix(verificationKey),
                            bytesPrefix(aggregatedSignature),
                            aggregatedSignature.length(),
                            incorporatedNodeIds.values(),
                            signatures.keySet(),
                            totalWeight,
                            thresholdWeight,
                            thresholdDenominator);
                    future.completeExceptionally(new IllegalStateException(INVALID_AGGREGATE_SIGNATURE_MESSAGE));
                }
            }
        }

        /**
         * @return the construction id this signing attempt is pinned to
         */
        public long constructionId() {
            return constructionId;
        }

        private String blockHashPrefix() {
            return bytesPrefix(blockHash);
        }
    }

    private static String bytesPrefix(@NonNull final Bytes bytes) {
        final int n = Math.min(8, (int) bytes.length());
        final var sb = new StringBuilder(n * 2);
        for (int i = 0; i < n; i++) {
            sb.append(String.format("%02x", bytes.getByte(i) & 0xff));
        }
        return sb.toString();
    }

    private static String bytesHashPrefix(@NonNull final Bytes bytes) {
        try {
            final var md = java.security.MessageDigest.getInstance("SHA-384");
            final byte[] arr = bytes.toByteArray();
            final byte[] digest = md.digest(arr);
            final int n = Math.min(8, digest.length);
            final var sb = new StringBuilder(n * 2);
            for (int i = 0; i < n; i++) {
                sb.append(String.format("%02x", digest[i] & 0xff));
            }
            return sb.toString();
        } catch (final java.security.NoSuchAlgorithmException e) {
            return "n/a";
        }
    }
}
