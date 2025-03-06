// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.hints.impl;

import static com.hedera.node.app.roster.RosterTransitionWeights.atLeastOneThirdOfTotal;
import static java.util.Objects.requireNonNull;
import static java.util.stream.Collectors.toMap;

import com.hedera.hapi.node.state.hints.HintsConstruction;
import com.hedera.hapi.node.state.hints.NodePartyId;
import com.hedera.hapi.node.state.roster.Roster;
import com.hedera.hapi.node.state.roster.RosterEntry;
import com.hedera.node.app.hints.HintsLibrary;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;
import javax.inject.Inject;
import javax.inject.Singleton;

/**
 * The hinTS context that can be used to request hinTS signatures using the latest
 * complete construction, if there is one. See {@link #setConstruction(HintsConstruction)}
 * for the ways the context can have a construction set.
 */
@Singleton
public class HintsContext {
    private final HintsLibrary library;

    @Nullable
    private HintsConstruction construction;

    @Nullable
    private Map<Long, Integer> nodePartyIds;

    @Inject
    public HintsContext(@NonNull final HintsLibrary library) {
        this.library = requireNonNull(library);
    }

    /**
     * Sets the active hinTS construction as the signing context. Called in three places,
     * <ol>
     *     <li>In the startup phase, when initializing from a state whose active hinTS
     *     construction had already finished its preprocessing work.</li>
     *     <li>In the bootstrap runtime phase, on finishing the preprocessing work for
     *     the genesis hinTS construction.</li>
     *     <li>In the normal runtime phase, in the first round after an upgrade, when
     *     swapping in a newly adopted roster's hinTS construction and purging votes for
     *     the previous construction.</li>
     * </ol>
     * @param construction the construction
     */
    public void setConstruction(@NonNull final HintsConstruction construction) {
        this.construction = requireNonNull(construction);
        if (!construction.hasHintsScheme()) {
            throw new IllegalArgumentException("Construction has no hints scheme");
        }
        this.nodePartyIds = asNodePartyIds(construction.hintsSchemeOrThrow().nodePartyIds());
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

    public long constructionIdOrThrow() {
        throwIfNotReady();
        return requireNonNull(construction).constructionId();
    }

    /**
     * Creates a new asynchronous signing process for the given block hash.
     *
     * @param blockHash     the block hash
     * @param currentRoster the current roster
     * @return the signing process
     */
    public @NonNull Signing newSigning(@NonNull final Bytes blockHash, final Roster currentRoster) {
        requireNonNull(blockHash);
        throwIfNotReady();
        final var preprocessedKeys =
                requireNonNull(construction).hintsSchemeOrThrow().preprocessedKeysOrThrow();
        final var verificationKey = preprocessedKeys.verificationKey();
        final long totalWeight = currentRoster.rosterEntries().stream()
                .mapToLong(RosterEntry::weight)
                .sum();
        return new Signing(
                construction.constructionId(),
                atLeastOneThirdOfTotal(totalWeight),
                blockHash,
                preprocessedKeys.aggregationKey(),
                requireNonNull(nodePartyIds),
                verificationKey,
                currentRoster);
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
        private final long thresholdWeight;
        private final Bytes message;
        private final Bytes aggregationKey;
        private final Bytes verificationKey;
        private final Map<Long, Integer> partyIds;
        private final CompletableFuture<Bytes> future = new CompletableFuture<>();
        private final ConcurrentMap<Integer, Bytes> signatures = new ConcurrentHashMap<>();
        private final AtomicLong weightOfSignatures = new AtomicLong();
        private final Roster currentRoster;

        public Signing(
                final long constructionId,
                final long thresholdWeight,
                @NonNull final Bytes message,
                @NonNull final Bytes aggregationKey,
                @NonNull final Map<Long, Integer> partyIds,
                @NonNull final Bytes verificationKey,
                final Roster currentRoster) {
            this.constructionId = constructionId;
            this.thresholdWeight = thresholdWeight;
            this.message = requireNonNull(message);
            this.aggregationKey = requireNonNull(aggregationKey);
            this.partyIds = requireNonNull(partyIds);
            this.verificationKey = requireNonNull(verificationKey);
            this.currentRoster = requireNonNull(currentRoster);
        }

        /**
         * The future that will complete when sufficient partial signatures have been aggregated.
         * @return the future
         */
        public CompletableFuture<Bytes> future() {
            return future;
        }

        /**
         * Incorporates a node's partial signature into the aggregation. If the signature is valid, and
         * including this node's weight passes the required threshold, completes the future returned from
         * {@link #future()} with the aggregated signature.
         *
         * @param crs the final CRS used by the network
         * @param constructionId the construction ID
         * @param nodeId the node ID
         * @param signature the partial signature
         */
        public void incorporate(
                @NonNull final Bytes crs,
                final long constructionId,
                final long nodeId,
                @NonNull final Bytes signature) {
            requireNonNull(signature);
            if (this.constructionId == constructionId && partyIds.containsKey(nodeId)) {
                final int partyId = partyIds.get(nodeId);
                if (library.verifyBls(crs, signature, message, aggregationKey, partyId)) {
                    signatures.put(partyId, signature);
                    final var weight = currentRoster.rosterEntries().stream()
                            .filter(e -> e.nodeId() == nodeId)
                            .mapToLong(RosterEntry::weight)
                            .findFirst()
                            .orElse(0L);
                    if (weightOfSignatures.addAndGet(weight) >= thresholdWeight) {
                        future.complete(library.aggregateSignatures(crs, aggregationKey, verificationKey, signatures));
                    }
                }
            }
        }
    }
}
