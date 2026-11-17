// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.hints;

import static com.hedera.node.app.hints.HintsLibrary.isValidPartyId;
import static com.hedera.node.app.hints.impl.HintsLibraryImpl.SIGNATURE_LENGTH;
import static com.hedera.node.app.hints.impl.HintsLibraryImpl.VK_LENGTH;
import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.state.hints.CRSStage;
import com.hedera.hapi.node.state.hints.CRSState;
import com.hedera.hapi.node.state.hints.HintsConstruction;
import com.hedera.hapi.node.state.hints.NodePartyId;
import com.hedera.node.app.hints.impl.HintsKeyAccessor;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/**
 * Signs a successor's exact verification-key bytes using an immutable snapshot of the persisted source scheme.
 * This does not open a block signing attempt, gossip a transaction, or consult the mutable signing context.
 * Signing and aggregation run on the caller's background executor. Bounded partial and aggregate verification
 * can run during consensus handling so acceptance depends on the transaction and state rather than task timing.
 */
public final class KeyProvenanceSigner {
    // The pinned hinTS library serializes its BLS partial signature as an uncompressed BLS12-381 G2 point.
    public static final int PARTIAL_SIGNATURE_LENGTH = 192;

    private final HintsLibrary library;
    private final HintsKeyAccessor keyAccessor;
    private final long constructionId;
    private final Bytes crs;
    private final Bytes aggregationKey;
    private final Bytes verificationKey;
    private final Map<Long, NodePartyId> parties;
    private final long totalWeight;

    /**
     * Captures a completed persisted source construction and its bound CRS. Malformed or unbound schemes fail closed.
     */
    public KeyProvenanceSigner(
            @NonNull final HintsLibrary library,
            @NonNull final HintsKeyAccessor keyAccessor,
            @NonNull final HintsConstruction construction,
            @NonNull final CRSState crsState) {
        this.library = requireNonNull(library);
        this.keyAccessor = requireNonNull(keyAccessor);
        requireNonNull(construction);
        requireNonNull(crsState);
        if (construction.constructionId() <= 0
                || !construction.hasHintsScheme()
                || construction.crsId() <= 0
                || construction.crsId() != crsState.ceremonyId()
                || construction.numParties() != crsState.numParties()
                || crsState.stage() != CRSStage.COMPLETED
                || HintsLibrary.crsPartySizeFrom(crsState.crs()) != construction.numParties()) {
            throw new IllegalArgumentException("Key provenance requires a completed construction bound to its CRS");
        }
        final var scheme = construction.hintsSchemeOrThrow();
        final var keys = scheme.preprocessedKeysOrThrow();
        if (keys.aggregationKey().length() == 0 || keys.verificationKey().length() != VK_LENGTH) {
            throw new IllegalArgumentException("Key provenance requires complete hinTS keys");
        }
        final var byNode = new TreeMap<Long, NodePartyId>();
        final var partyIds = new HashSet<Integer>();
        long weight = 0;
        for (final var party : scheme.nodePartyIds()) {
            if (!isValidPartyId(party.partyId(), construction.numParties())
                    || party.partyWeight() < 0
                    || byNode.putIfAbsent(party.nodeId(), party) != null
                    || !partyIds.add(party.partyId())) {
                throw new IllegalArgumentException(
                        "Key provenance requires unique valid parties and nonnegative weights");
            }
            weight = Math.addExact(weight, party.partyWeight());
        }
        if (weight <= 0) {
            throw new IllegalArgumentException("Key provenance requires positive total source weight");
        }
        this.constructionId = construction.constructionId();
        this.crs = crsState.crs();
        this.aggregationKey = keys.aggregationKey();
        this.verificationKey = keys.verificationKey();
        this.parties = Map.copyOf(byNode);
        this.totalWeight = weight;
    }

    /** Returns the persisted source hinTS construction ID, independent of the history construction ID. */
    public long constructionId() {
        return constructionId;
    }

    /** Returns the source verification key under which the successor's provenance must verify. */
    public @NonNull Bytes verificationKey() {
        return verificationKey;
    }

    /**
     * Whether this node has positive weight in the captured source scheme, independent of local key availability.
     */
    public boolean isEligibleSigner(final long nodeId) {
        final var party = parties.get(nodeId);
        return party != null && party.partyWeight() > 0;
    }

    /**
     * Signs the exact successor verification-key bytes, or returns null if this node cannot contribute a valid share.
     * Locally verifying the share also catches a missing or mismatched private key before it is gossiped.
     */
    public @Nullable Bytes sign(final long nodeId, @NonNull final Bytes nextVerificationKey) {
        requireNonNull(nextVerificationKey);
        if (nextVerificationKey.length() != VK_LENGTH || !isEligibleSigner(nodeId)) {
            return null;
        }
        final var signature = keyAccessor.signWithBlsPrivateKey(constructionId, nextVerificationKey);
        return signature != null && verify(nodeId, constructionId, nextVerificationKey, signature) ? signature : null;
    }

    /** Verifies a creator's share against the frozen source party assignment and the exact successor key. */
    public boolean verify(
            final long nodeId,
            final long sourceConstructionId,
            @NonNull final Bytes nextVerificationKey,
            @NonNull final Bytes signature) {
        requireNonNull(nextVerificationKey);
        requireNonNull(signature);
        final var party = parties.get(nodeId);
        return sourceConstructionId == constructionId
                && nextVerificationKey.length() == VK_LENGTH
                && signature.length() == PARTIAL_SIGNATURE_LENGTH
                && party != null
                && party.partyWeight() > 0
                && library.verifyBls(crs, signature, nextVerificationKey, aggregationKey, party.partyId());
    }

    /**
     * Aggregates a consensus-defined set of shares once its frozen source weight strictly exceeds one third.
     * Every share is revalidated; the caller must not select the input set based on asynchronous completion order.
     * The result is always checked under the source key and the fixed aBFT threshold, regardless of signing config.
     */
    public @NonNull Optional<Bytes> aggregate(
            @NonNull final Bytes nextVerificationKey, @NonNull final Map<Long, Bytes> partialSignatures) {
        requireNonNull(nextVerificationKey);
        requireNonNull(partialSignatures);
        if (nextVerificationKey.length() != VK_LENGTH || partialSignatures.size() > parties.size()) {
            return Optional.empty();
        }
        final var byParty = new TreeMap<Integer, Bytes>();
        long weight = 0;
        for (final var entry : new TreeMap<>(partialSignatures).entrySet()) {
            if (!verify(entry.getKey(), constructionId, nextVerificationKey, entry.getValue())) {
                return Optional.empty();
            }
            final var party = parties.get(entry.getKey());
            byParty.put(party.partyId(), entry.getValue());
            weight = Math.addExact(weight, party.partyWeight());
        }
        if (weight <= totalWeight / 3) {
            return Optional.empty();
        }
        final var signature = library.aggregateSignatures(crs, aggregationKey, verificationKey, byParty);
        return signature != null && verifyAggregate(nextVerificationKey, signature)
                ? Optional.of(signature)
                : Optional.empty();
    }

    /** Verifies an aggregate under the source epoch's key at the strict greater-than-one-third threshold. */
    public boolean verifyAggregate(@NonNull final Bytes nextVerificationKey, @NonNull final Bytes signature) {
        requireNonNull(nextVerificationKey);
        requireNonNull(signature);
        return nextVerificationKey.length() == VK_LENGTH
                && signature.length() == SIGNATURE_LENGTH
                && library.verifyAggregate(signature, nextVerificationKey, verificationKey, 1, 3);
    }
}
