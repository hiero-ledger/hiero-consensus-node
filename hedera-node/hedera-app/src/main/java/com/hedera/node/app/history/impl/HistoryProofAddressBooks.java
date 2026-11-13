// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.history.impl;

import static com.hedera.node.app.history.HistoryLibrary.isIdentityKey;
import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.state.history.HistoryProof;
import com.hedera.hapi.node.state.history.ProofNodeWeight;
import com.hedera.node.app.history.HistoryLibrary;
import com.hedera.node.app.history.HistoryLibrary.AddressBook;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.SortedMap;
import java.util.TreeMap;

/**
 * Reconstructs immutable WRAPS address books from the generation a history proof commits to.
 *
 * <p>The complete weighted membership is separate from the sparse hinTS participant set and from
 * the selected proof keys. In particular, a member without a selected key retains both its seat and
 * its original listed weight, even though its sentinel key contributes no native signing weight.
 */
public final class HistoryProofAddressBooks {
    /**
     * The length of the compressed public point that begins every serialized attested Schnorr key.
     */
    private static final int PUBLIC_POINT_LENGTH = 32;

    private HistoryProofAddressBooks() {}

    /**
     * Returns the complete, naturally ordered weighted membership of the proof's target book.
     *
     * @param proof the proof containing the frozen target snapshot
     * @return the immutable weights, or empty if this legacy proof has no membership snapshot
     * @throws IllegalArgumentException if the snapshot is not strictly ordered or contains a negative id or weight
     */
    public static @NonNull Optional<SortedMap<Long, Long>> targetWeights(@NonNull final HistoryProof proof) {
        requireNonNull(proof);
        if (proof.targetNodeWeights().isEmpty()) {
            return Optional.empty();
        }
        final var weights = new TreeMap<Long, Long>();
        long previousId = -1;
        for (final var entry : proof.targetNodeWeights()) {
            if (entry.nodeId() <= previousId || entry.weight() < 0) {
                throw new IllegalArgumentException(
                        "Canonical WRAPS membership must have ordered nonnegative ids and weights");
            }
            weights.put(entry.nodeId(), entry.weight());
            previousId = entry.nodeId();
        }
        return Optional.of(Collections.unmodifiableSortedMap(weights));
    }

    /**
     * Encodes the complete weighted membership in canonical node-id order without changing listed weights.
     *
     * @param weights the complete book's listed weights
     * @return the immutable canonical snapshot
     * @throws IllegalArgumentException if any id or weight is negative
     */
    public static @NonNull List<ProofNodeWeight> weightsFrom(@NonNull final SortedMap<Long, Long> weights) {
        requireNonNull(weights);
        final var ordered = new TreeMap<Long, Long>();
        weights.forEach((nodeId, weight) -> {
            if (nodeId < 0 || weight < 0) {
                throw new IllegalArgumentException("Canonical WRAPS membership cannot have negative ids or weights");
            }
            ordered.put(nodeId, weight);
        });
        return ordered.entrySet().stream()
                .map(entry -> new ProofNodeWeight(entry.getKey(), entry.getValue()))
                .toList();
    }

    /**
     * Reconstructs the target book using only the proof's frozen membership and selected historical keys.
     *
     * @param proof the proof containing the frozen target snapshot
     * @return the canonical target book
     * @throws IllegalArgumentException if the snapshot is absent or its selected-key records are not canonical
     */
    public static @NonNull AddressBook targetBook(@NonNull final HistoryProof proof) {
        final var weights = targetWeights(proof)
                .orElseThrow(
                        () -> new IllegalArgumentException("History proof has no canonical WRAPS membership snapshot"));
        final var keys = new TreeMap<Long, byte[]>();
        final var selectedPoints = new HashSet<Bytes>();
        long previousId = -1;
        for (final var key : proof.targetProofKeys()) {
            if (key.nodeId() <= previousId
                    || !weights.containsKey(key.nodeId())
                    || key.key().length() == 0) {
                throw new IllegalArgumentException(
                        "Selected WRAPS keys must have ordered ids in the canonical membership");
            }
            // Multiple sentinel seats are valid, but the native book rejects a repeated non-identity public point,
            // whatever proof of knowledge accompanies it
            if (!isIdentityKey(key.key()) && !selectedPoints.add(publicPointOf(key.key()))) {
                throw new IllegalArgumentException("Selected WRAPS keys contain a duplicate non-sentinel key");
            }
            keys.put(key.nodeId(), key.key().toByteArray());
            previousId = key.nodeId();
        }
        return AddressBook.from(weights, keys);
    }

    private static Bytes publicPointOf(@NonNull final Bytes key) {
        return key.length() > PUBLIC_POINT_LENGTH ? key.slice(0, PUBLIC_POINT_LENGTH) : key;
    }

    /**
     * Enriches a legacy proof from known complete generation weights only after checking its committed hash.
     * The weights may come from any source, since they are used only if they reproduce the committed hash
     * together with the proof's own frozen keys. No current key publications are consulted.
     *
     * @param proof the proof to enrich
     * @param weights candidate complete listed weights, used only if they reproduce the proof's commitment
     * @param library the library used to validate the reconstructed commitment
     * @return the proof with a validated canonical membership snapshot
     * @throws IllegalArgumentException if an existing snapshot differs or the supplied weights do not reproduce the commitment
     */
    public static @NonNull HistoryProof withTargetWeights(
            @NonNull final HistoryProof proof,
            @NonNull final SortedMap<Long, Long> weights,
            @NonNull final HistoryLibrary library) {
        requireNonNull(proof);
        requireNonNull(library);
        final var snapshot = weightsFrom(weights);
        if (!proof.targetNodeWeights().isEmpty() && !proof.targetNodeWeights().equals(snapshot)) {
            throw new IllegalArgumentException("Cannot replace a history proof's frozen WRAPS membership");
        }
        final var enriched = proof.copyBuilder().targetNodeWeights(snapshot).build();
        if (!proof.hasTargetHistory()
                || !Bytes.wrap(library.hashAddressBook(targetBook(enriched)))
                        .equals(proof.targetHistoryOrThrow().addressBookHash())) {
            throw new IllegalArgumentException(
                    "Canonical WRAPS membership does not reproduce the proof's target commitment");
        }
        return enriched;
    }
}
