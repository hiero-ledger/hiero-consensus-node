// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.hints.impl;

import static com.hedera.node.app.roster.RosterTransitionWeights.atLeastOneThirdOfTotal;
import static java.util.Objects.requireNonNull;
import static java.util.concurrent.TimeUnit.SECONDS;
import static java.util.stream.Collectors.toMap;

import com.hedera.hapi.node.state.hints.HintsConstruction;
import com.hedera.hapi.node.state.hints.NodePartyId;
import com.hedera.hapi.node.state.roster.Roster;
import com.hedera.hapi.node.state.roster.RosterEntry;
import com.hedera.hapi.services.auxiliary.hints.HintsPartialSignatureTransactionBody;
import com.hedera.node.app.hints.HintsLibrary;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
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

    private static final Duration SIGNING_ATTEMPT_TIMEOUT = Duration.ofSeconds(10);

    private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();

    private final HintsLibrary library;

    @Nullable
    private Bytes crs;

    @Nullable
    private HintsConstruction construction;

    @Nullable
    private Map<Long, Integer> nodePartyIds;

    private long schemeId;

    @Inject
    public HintsContext(@NonNull final HintsLibrary library) {
        this.library = requireNonNull(library);
    }

    /**
     * Set the CRS in use for this signing context.
     * @param crs the CRS to use
     */
    public void setCrs(@NonNull final Bytes crs) {
        this.crs = requireNonNull(crs);
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
        schemeId = this.construction.constructionId();
    }

    /**
     * Returns true if the signing context is ready.
     * @return true if the context is ready
     */
    public boolean isReady() {
        return construction != null && construction.hasHintsScheme();
    }

    /**
     * Returns the current scheme ids, or throws if they are unset.
     * @return the active scheme id
     * @throws IllegalStateException if the scheme id is unset
     */
    public long activeSchemeIdOrThrow() {
        if (schemeId == 0) {
            throw new IllegalStateException("No scheme id set");
        }
        return schemeId;
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
     * Validates a partial signature transaction body under the current hinTS construction.
     * @param nodeId the node ID
     * @param crs the CRS to validate under
     * @param body the transaction body
     * @return true if the body is valid
     */
    public boolean validate(
            final long nodeId, @Nullable final Bytes crs, @NonNull final HintsPartialSignatureTransactionBody body) {
        if (crs == null || construction == null || nodePartyIds == null) {
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
     * @param currentRoster the current roster
     * @return the signing process
     */
    public @NonNull Signing newSigning(
            @NonNull final Bytes blockHash, final Roster currentRoster, Runnable onCompletion) {
        requireNonNull(blockHash);
        throwIfNotReady();
        final var preprocessedKeys =
                requireNonNull(construction).hintsSchemeOrThrow().preprocessedKeysOrThrow();
        final var verificationKey = preprocessedKeys.verificationKey();
        final long totalWeight = currentRoster.rosterEntries().stream()
                .mapToLong(RosterEntry::weight)
                .sum();
        return new Signing(
                blockHash,
                atLeastOneThirdOfTotal(totalWeight),
                preprocessedKeys.aggregationKey(),
                requireNonNull(nodePartyIds),
                verificationKey,
                currentRoster,
                onCompletion);
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
        private final long thresholdWeight;
        private final Bytes blockHash;
        private final Bytes aggregationKey;
        private final Bytes verificationKey;
        private final Map<Long, Integer> partyIds;
        private final CompletableFuture<Bytes> future = new CompletableFuture<>();
        private final ConcurrentMap<Integer, Bytes> signatures = new ConcurrentHashMap<>();
        private final AtomicLong weightOfSignatures = new AtomicLong();
        private final Roster currentRoster;
        private final AtomicBoolean completed = new AtomicBoolean();

        public Signing(
                @NonNull final Bytes blockHash,
                final long thresholdWeight,
                @NonNull final Bytes aggregationKey,
                @NonNull final Map<Long, Integer> partyIds,
                @NonNull final Bytes verificationKey,
                @NonNull final Roster currentRoster,
                @NonNull final Runnable onCompletion) {
            this.thresholdWeight = thresholdWeight;
            requireNonNull(onCompletion);
            this.blockHash = requireNonNull(blockHash);
            this.aggregationKey = requireNonNull(aggregationKey);
            this.partyIds = requireNonNull(partyIds);
            this.verificationKey = requireNonNull(verificationKey);
            this.currentRoster = requireNonNull(currentRoster);
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
         * Incorporates a node's pre-validated partial signature into the aggregation. If including this node's
         * weight passes the required threshold, completes the future returned from {@link #future()} with the
         * aggregated signature.
         *
         * @param crs the final CRS used by the network
         * @param nodeId the node ID
         * @param signature the pre-validated partial signature
         */
        public void incorporateValid(@NonNull final Bytes crs, final long nodeId, @NonNull final Bytes signature) {
            requireNonNull(crs);
            requireNonNull(signature);
            if (completed.get()) {
                return;
            }
            final var partyId = partyIds.get(nodeId);
            signatures.put(partyId, signature);
            final var weight = currentRoster.rosterEntries().stream()
                    .filter(e -> e.nodeId() == nodeId)
                    .mapToLong(RosterEntry::weight)
                    .findFirst()
                    .orElse(0L);
            final var totalWeight = weightOfSignatures.addAndGet(weight);
            if (totalWeight >= thresholdWeight && completed.compareAndSet(false, true)) {
                final var aggregatedSignature =
                        library.aggregateSignatures(crs, aggregationKey, verificationKey, signatures);
                future.complete(aggregatedSignature);
            }
        }
    }
}
