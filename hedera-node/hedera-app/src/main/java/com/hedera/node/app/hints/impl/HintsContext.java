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
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantReadWriteLock;
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

    private static final long NO_CONSTRUCTION_ID = Long.MIN_VALUE;
    private static final long NO_BLOCK_STARTED = -1L;
    private static final long MIXED_MODE_BLOCKS = 3L;

    private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();

    private final HintsLibrary library;
    private final Supplier<Configuration> configProvider;
    private final HintsSigningMetrics signingMetrics;

    private final ReentrantReadWriteLock akCacheLock = new ReentrantReadWriteLock(true);

    private volatile ConstructionSnapshots constructionSnapshots = ConstructionSnapshots.EMPTY;
    private volatile long lastStartedBlock = NO_BLOCK_STARTED;
    private volatile long mixedModePreviousConstructionId = NO_CONSTRUCTION_ID;
    private volatile long mixedModeActiveConstructionId = NO_CONSTRUCTION_ID;
    private volatile long mixedModeExpiresAtBlock = NO_BLOCK_STARTED;

    /**
     * The construction id whose aggregation key was last allowed to populate the native hinTS AK cache.
     * Guarded by {@link #akCacheLock}'s write lock.
     */
    private long lastAkCacheConstructionId = NO_CONSTRUCTION_ID;

    /**
     * The active and, during mixed mode, immediately previous construction snapshots.
     */
    private record ConstructionSnapshots(
            @Nullable ConstructionSnapshot active, @Nullable ConstructionSnapshot previous) {
        private static final ConstructionSnapshots EMPTY = new ConstructionSnapshots(null, null);

        private @Nullable ConstructionSnapshot activeIf(final long constructionId) {
            return active != null && active.constructionId() == constructionId ? active : null;
        }
    }

    /**
     * Immutable view of all construction data needed to validate partial signatures and aggregate a block signature.
     * A signing attempt closes over one of these snapshots so later handoffs cannot change its weights or keys.
     */
    private record ConstructionSnapshot(
            long constructionId,
            @NonNull HintsConstruction construction,
            @NonNull Bytes aggregationKey,
            @NonNull Bytes verificationKey,
            @NonNull Map<Long, Integer> nodePartyIds,
            @NonNull Map<Long, Long> nodeWeights,
            long totalWeight) {
        private ConstructionSnapshot {
            requireNonNull(construction);
            requireNonNull(aggregationKey);
            requireNonNull(verificationKey);
            requireNonNull(nodePartyIds);
            requireNonNull(nodeWeights);
        }
    }

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
    public synchronized void setConstruction(@NonNull final HintsConstruction construction) {
        requireNonNull(construction);
        if (!construction.hasHintsScheme()) {
            throw new IllegalArgumentException(
                    "Given construction #" + construction.constructionId() + " has no hinTS scheme");
        }
        final var newSnapshot = snapshotOf(construction);
        final var writeLock = akCacheLock.writeLock();
        writeLock.lock();
        try {
            final var current = constructionSnapshots;
            final var currentActive = current.active();
            final boolean activeChanged =
                    currentActive != null && currentActive.constructionId() != newSnapshot.constructionId();
            final boolean canEnterMixedMode = activeChanged && lastStartedBlock != NO_BLOCK_STARTED;
            constructionSnapshots = new ConstructionSnapshots(newSnapshot, canEnterMixedMode ? currentActive : null);
            if (activeChanged) {
                resetAkCache();
                if (canEnterMixedMode) {
                    beginMixedMode(currentActive.constructionId(), newSnapshot.constructionId());
                } else {
                    clearMixedMode();
                }
            }
        } finally {
            writeLock.unlock();
        }
    }

    /**
     * Notifies the context that a block has started.
     * <p>
     * The first three blocks that overlap a construction handoff may still admit partial signatures from the previous
     * construction. Starting with the fourth block, only the active construction is accepted again.
     *
     * @param blockNumber the block number being started
     */
    public synchronized void onBlockStarted(final long blockNumber) {
        lastStartedBlock = blockNumber;
        if (isMixedModeActive() && blockNumber >= mixedModeExpiresAtBlock) {
            final var writeLock = akCacheLock.writeLock();
            writeLock.lock();
            try {
                resetAkCache();
                clearMixedMode();
                final var snapshots = constructionSnapshots;
                constructionSnapshots = new ConstructionSnapshots(snapshots.active(), null);
            } finally {
                writeLock.unlock();
            }
        }
    }

    /**
     * Whether the given construction id can currently be used for hinTS partial signatures.
     *
     * @param constructionId the construction id
     * @return true if the construction is active, or still in the mixed handoff window
     */
    public boolean acceptsConstruction(final long constructionId) {
        return acceptedSnapshot(constructionId) != null;
    }

    /**
     * Returns true if the signing context is ready.
     * @return true if the context is ready
     */
    public boolean isReady() {
        return constructionSnapshots.active() != null;
    }

    /**
     * Returns the active verification key, or throws if the context is not ready.
     * @return the verification key
     */
    public Bytes verificationKeyOrThrow() {
        return activeSnapshotOrThrow().verificationKey();
    }

    /**
     * Returns the active construction ID, or throws if the context is not ready.
     * @return the construction ID
     */
    public long constructionIdOrThrow() {
        return activeSnapshotOrThrow().constructionId();
    }

    /**
     * Returns the active construction, or null if none is active (at genesis).
     * @return the active construction
     */
    public @Nullable HintsConstruction activeConstruction() {
        final var snapshot = constructionSnapshots.active();
        return snapshot == null ? null : snapshot.construction();
    }

    private @Nullable ConstructionSnapshot acceptedSnapshot(final long constructionId) {
        final var snapshots = constructionSnapshots;
        final var active = snapshots.activeIf(constructionId);
        if (active != null) {
            return active;
        }
        final var previous = snapshots.previous();
        return previous != null && previous.constructionId() == constructionId && isAcceptedPreviousConstruction()
                ? previous
                : null;
    }

    private boolean isAcceptedPreviousConstruction() {
        return isMixedModeActive() && lastStartedBlock < mixedModeExpiresAtBlock;
    }

    private boolean isMixedModeActive() {
        return mixedModePreviousConstructionId != NO_CONSTRUCTION_ID;
    }

    private boolean requiresExclusiveAkCacheAccess(final long constructionId) {
        return isAcceptedPreviousConstruction()
                && (constructionId == mixedModePreviousConstructionId
                        || constructionId == mixedModeActiveConstructionId);
    }

    private void beginMixedMode(final long previousConstructionId, final long activeConstructionId) {
        mixedModePreviousConstructionId = previousConstructionId;
        mixedModeActiveConstructionId = activeConstructionId;
        mixedModeExpiresAtBlock = lastStartedBlock + MIXED_MODE_BLOCKS;
        log.info(
                "Entering hinTS mixed construction mode for #{} and #{} through block #{}",
                previousConstructionId,
                activeConstructionId,
                mixedModeExpiresAtBlock - 1);
    }

    private void clearMixedMode() {
        if (isMixedModeActive()) {
            log.info("Exiting hinTS mixed construction mode");
        }
        mixedModePreviousConstructionId = NO_CONSTRUCTION_ID;
        mixedModeActiveConstructionId = NO_CONSTRUCTION_ID;
        mixedModeExpiresAtBlock = NO_BLOCK_STARTED;
    }

    /**
     * Validates a partial signature transaction body under an accepted hinTS construction.
     * @param nodeId the node ID
     * @param crs the CRS to validate under
     * @param body the transaction body
     * @return true if the body is valid
     */
    public boolean validate(
            final long nodeId, @Nullable final Bytes crs, @NonNull final HintsPartialSignatureTransactionBody body) {
        requireNonNull(crs);
        final var snapshot = acceptedSnapshot(body.constructionId());
        final var partyId = snapshot == null ? null : snapshot.nodePartyIds().get(nodeId);
        if (snapshot == null || partyId == null) {
            return false;
        }
        return verifyBls(
                snapshot.constructionId(),
                crs,
                body.partialSignature(),
                body.message(),
                snapshot.aggregationKey(),
                partyId);
    }

    /**
     * Creates a new asynchronous signing process for the given block hash under the active construction.
     * <p>
     * This is only used when synchronously opening the local node's signing attempt at block close; incoming partial
     * signature transactions must instead name the construction id they were produced for.
     *
     * @param blockHash     the block hash
     * @param onCompletion a callback to run when the signing process completes
     * @return the signing process
     */
    public @NonNull Signing newSigningForActiveConstruction(
            @NonNull final Bytes blockHash, @NonNull final Runnable onCompletion) {
        requireNonNull(blockHash);
        requireNonNull(onCompletion);
        return newSigningFrom(activeSnapshotOrThrow(), blockHash, onCompletion);
    }

    /**
     * Creates a new asynchronous signing process for the given block hash under a known recent construction.
     * <p>
     * This is the handoff-safe path for partial signatures that reach consensus after the active construction changes:
     * the transaction's construction id selects the snapshot to close over.
     *
     * @param blockHash the block hash
     * @param constructionId the construction id to sign under
     * @param onCompletion a callback to run when the signing process completes
     * @return the signing process, or null if the construction is no longer known
     */
    public @Nullable Signing newSigningForConstruction(
            @NonNull final Bytes blockHash, final long constructionId, @NonNull final Runnable onCompletion) {
        requireNonNull(blockHash);
        requireNonNull(onCompletion);
        final var snapshot = acceptedSnapshot(constructionId);
        return snapshot == null ? null : newSigningFrom(snapshot, blockHash, onCompletion);
    }

    private @NonNull Signing newSigningFrom(
            @NonNull final ConstructionSnapshot snapshot,
            @NonNull final Bytes blockHash,
            @NonNull final Runnable onCompletion) {
        final var tssConfig = configProvider.get().getConfigData(TssConfig.class);
        final int divisor = Math.max(1, tssConfig.signingThresholdDivisor());
        final long threshold = snapshot.totalWeight() / divisor;
        return new Signing(
                snapshot.constructionId(),
                blockHash,
                threshold,
                divisor,
                snapshot.aggregationKey(),
                snapshot.nodePartyIds(),
                snapshot.nodeWeights(),
                snapshot.verificationKey(),
                onCompletion,
                tssConfig.validateBlockSignatures());
    }

    private @NonNull ConstructionSnapshot snapshotOf(@NonNull final HintsConstruction construction) {
        final var scheme = construction.hintsSchemeOrThrow();
        final var preprocessedKeys = scheme.preprocessedKeysOrThrow();
        final Map<Long, Long> nodeWeights = new HashMap<>();
        long totalWeight = 0L;
        for (final var nodePartyId : scheme.nodePartyIds()) {
            totalWeight += nodePartyId.partyWeight();
            nodeWeights.put(nodePartyId.nodeId(), nodePartyId.partyWeight());
        }
        return new ConstructionSnapshot(
                construction.constructionId(),
                construction,
                preprocessedKeys.aggregationKey(),
                preprocessedKeys.verificationKey(),
                Map.copyOf(asNodePartyIds(scheme.nodePartyIds())),
                Map.copyOf(nodeWeights),
                totalWeight);
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
     * Returns the current construction snapshot, or throws if the context is not ready.
     */
    private @NonNull ConstructionSnapshot activeSnapshotOrThrow() {
        final var snapshot = constructionSnapshots.active();
        if (snapshot == null) {
            throw new IllegalStateException("Signing context not ready");
        }
        return snapshot;
    }

    private boolean verifyBls(
            final long constructionId,
            @NonNull final Bytes crs,
            @NonNull final Bytes signature,
            @NonNull final Bytes message,
            @NonNull final Bytes aggregationKey,
            final int partyId) {
        requireNonNull(crs);
        requireNonNull(signature);
        requireNonNull(message);
        requireNonNull(aggregationKey);
        return withAkCacheProtection(
                constructionId,
                () -> acceptsConstruction(constructionId)
                        && library.verifyBls(crs, signature, message, aggregationKey, partyId));
    }

    private @Nullable Bytes aggregateSignatures(
            final long constructionId,
            @NonNull final Bytes crs,
            @NonNull final Bytes aggregationKey,
            @NonNull final Bytes verificationKey,
            @NonNull final Map<Integer, Bytes> partialSignatures) {
        requireNonNull(crs);
        requireNonNull(aggregationKey);
        requireNonNull(verificationKey);
        requireNonNull(partialSignatures);
        return withAkCacheProtection(
                constructionId,
                () -> acceptsConstruction(constructionId)
                        ? library.aggregateSignatures(crs, aggregationKey, verificationKey, partialSignatures)
                        : null);
    }

    private <T> T withAkCacheProtection(final long constructionId, @NonNull final Supplier<T> operation) {
        requireNonNull(operation);
        if (requiresExclusiveAkCacheAccess(constructionId)) {
            return withExclusiveAkCacheAccess(constructionId, operation);
        }
        final var readLock = akCacheLock.readLock();
        readLock.lock();
        try {
            if (!requiresExclusiveAkCacheAccess(constructionId)) {
                return operation.get();
            }
        } finally {
            readLock.unlock();
        }
        return withExclusiveAkCacheAccess(constructionId, operation);
    }

    private <T> T withExclusiveAkCacheAccess(final long constructionId, @NonNull final Supplier<T> operation) {
        requireNonNull(operation);
        final var writeLock = akCacheLock.writeLock();
        writeLock.lock();
        try {
            useAkCacheFor(constructionId);
            return operation.get();
        } finally {
            writeLock.unlock();
        }
    }

    private void useAkCacheFor(final long constructionId) {
        if (lastAkCacheConstructionId != constructionId) {
            resetAkCache();
            lastAkCacheConstructionId = constructionId;
        }
    }

    private void resetAkCache() {
        library.resetCache();
        lastAkCacheConstructionId = NO_CONSTRUCTION_ID;
    }

    /**
     * A signing process spawned from this context.
     */
    public non-sealed class Signing implements BlockHashSigning {
        private final long startNanos;
        private final long constructionId;
        private final long thresholdWeight;
        private final long thresholdDenominator;
        private final Bytes blockHash;
        private final Bytes aggregationKey;
        private final Bytes verificationKey;
        private final boolean validateSignature;
        private final Map<Long, Long> nodeWeights;
        private final Map<Long, Integer> partyIds;
        private final CompletableFuture<Bytes> future = new CompletableFuture<>();
        private final ConcurrentMap<Integer, Bytes> signatures = new ConcurrentHashMap<>();
        private final AtomicLong weightOfSignatures = new AtomicLong();
        private final AtomicBoolean completed = new AtomicBoolean();
        private final ScheduledFuture<?> timeoutFuture;

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
            this.startNanos = System.nanoTime();
            this.constructionId = constructionId;
            this.thresholdWeight = thresholdWeight;
            this.validateSignature = validateSignature;
            this.thresholdDenominator = thresholdDenominator;
            requireNonNull(onCompletion);
            this.blockHash = requireNonNull(blockHash);
            this.aggregationKey = requireNonNull(aggregationKey);
            this.partyIds = requireNonNull(partyIds);
            this.nodeWeights = requireNonNull(nodeWeights);
            this.verificationKey = requireNonNull(verificationKey);
            timeoutFuture = executor.schedule(
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
         * The construction id of the hinTS scheme being used for the signing attempt.
         * This identifies the snapshot captured when the signing was opened, even if a later handoff updates the
         * context's active construction.
         */
        public long constructionId() {
            return constructionId;
        }

        /**
         * Validates a partial signature against this signing attempt's hinTS scheme.
         * This rejects signatures for a different construction id or block hash so a handoff cannot feed the attempt
         * with otherwise valid partial signatures from the adjacent construction.
         *
         * @param nodeId the node ID
         * @param crs the final CRS used by the network
         * @param body the partial signature body
         * @return true if the partial signature is valid under this signing attempt
         */
        public boolean validatePartial(
                final long nodeId, @NonNull final Bytes crs, @NonNull final HintsPartialSignatureTransactionBody body) {
            requireNonNull(crs);
            requireNonNull(body);
            if (body.constructionId() != constructionId
                    || !body.message().equals(blockHash)
                    || !partyIds.containsKey(nodeId)
                    || !acceptsConstruction(constructionId)) {
                return false;
            }
            return verifyBls(
                    constructionId, crs, body.partialSignature(), body.message(), aggregationKey, partyIds.get(nodeId));
        }

        /**
         * Cancels this signing process.
         */
        @Override
        public void cancel() {
            timeoutFuture.cancel(false);
            if (completed.compareAndSet(false, true)) {
                future.cancel(false);
            }
        }

        /**
         * Incorporates a node's pre-validated partial signature into the aggregation. If including this node's
         * weight passes the required threshold, completes the future returned from {@link #future()} with the
         * aggregated signature.
         *
         * @param crs the final CRS used by the network
         * @param nodeId the node ID
         * @param signature the pre-validated partial signature
         */
        @Override
        public void incorporateValid(@NonNull final Bytes crs, final long nodeId, @NonNull final Bytes signature) {
            requireNonNull(crs);
            requireNonNull(signature);
            if (completed.get() || !acceptsConstruction(constructionId)) {
                return;
            }
            final var partyId = partyIds.get(nodeId);
            if (partyId == null) {
                return;
            }
            if (signatures.put(partyId, signature) != null) {
                // Each valid signature should only accumulate weight once, so abort on duplicates
                return;
            }
            final var weight = nodeWeights.getOrDefault(nodeId, 0L);
            final var totalWeight = weightOfSignatures.addAndGet(weight);
            // For block hash signing, always require strictly greater than threshold
            final boolean reachedThreshold = totalWeight > thresholdWeight;
            if (reachedThreshold && completed.compareAndSet(false, true)) {
                final var aggregatedSignature =
                        aggregateSignatures(constructionId, crs, aggregationKey, verificationKey, signatures);
                final boolean valid = aggregatedSignature != null
                        && (!validateSignature
                                || library.verifyAggregate(
                                        aggregatedSignature, blockHash, verificationKey, 1L, thresholdDenominator));
                if (valid) {
                    future.complete(aggregatedSignature);
                    final long elapsedNanos = System.nanoTime() - startNanos;
                    signingMetrics.recordSignatureProduced(elapsedNanos / 1_000_000L);
                } else {
                    future.completeExceptionally(new IllegalStateException(INVALID_AGGREGATE_SIGNATURE_MESSAGE));
                }
            }
        }
    }
}
