// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.history.impl;

import static com.hedera.node.app.history.HistoryLibrary.isIdentityKey;
import static java.util.Objects.requireNonNull;

import com.hedera.node.app.history.HistoryLibrary.AddressBook;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.math.BigInteger;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Frozen signing requirements for a canonical WRAPS book and its source hinTS authority.
 *
 * <p>The book's listed weights belong to its commitment and must not be rewritten. Native WRAPS
 * quorum weight excludes identity-key entries from both sums, while hinTS provenance uses the
 * independently captured source scheme. Excluding a signer from an attempt changes neither total.
 */
public final class WrapsSigningQuorum {
    private final Map<Long, Long> nativeWeights;
    private final Map<Long, Long> sourceHintsWeights;
    private final Set<Long> eligibleNodeIds;
    private final BigInteger nativeHalfWeight;
    private final BigInteger hintsThirdWeight;
    private final boolean requiresProvenance;

    /**
     * Captures the immutable requirements for this signing generation.
     *
     * @param canonicalBook the exact source book used by native signing and proving
     * @param sourceHintsWeights the permitted source authority, including its complete hinTS weight total;
     *     for a genesis proof without a predecessor, this is the target book's authority
     * @param requiresProvenance whether the frozen set must also exceed one third of source hinTS weight
     */
    public WrapsSigningQuorum(
            @NonNull final AddressBook canonicalBook,
            @NonNull final Map<Long, Long> sourceHintsWeights,
            final boolean requiresProvenance) {
        requireNonNull(canonicalBook);
        requireNonNull(sourceHintsWeights);
        if (canonicalBook.nodeIds().length != canonicalBook.weights().length
                || canonicalBook.nodeIds().length != canonicalBook.publicKeys().length) {
            throw new IllegalArgumentException("Canonical WRAPS book arrays must have equal lengths");
        }
        final var listedWeights = new HashMap<Long, Long>();
        final var effectiveWeights = new HashMap<Long, Long>();
        for (int i = 0; i < canonicalBook.nodeIds().length; i++) {
            final long nodeId = canonicalBook.nodeIds()[i];
            final long weight = canonicalBook.weights()[i];
            if (nodeId < 0 || weight < 0 || listedWeights.putIfAbsent(nodeId, weight) != null) {
                throw new IllegalArgumentException(
                        "Canonical WRAPS book requires unique node IDs and nonnegative weights");
            }
            final var key = requireNonNull(canonicalBook.publicKeys()[i]);
            effectiveWeights.put(nodeId, isIdentityKey(key) ? 0L : weight);
        }
        for (final var entry : sourceHintsWeights.entrySet()) {
            if (requireNonNull(entry.getKey()) < 0 || requireNonNull(entry.getValue()) < 0) {
                throw new IllegalArgumentException("Source hinTS authority requires nonnegative node IDs and weights");
            }
        }
        this.nativeWeights = Map.copyOf(effectiveWeights);
        this.sourceHintsWeights = Map.copyOf(sourceHintsWeights);
        this.requiresProvenance = requiresProvenance;
        this.nativeHalfWeight = totalOf(this.nativeWeights).divide(BigInteger.TWO);
        this.hintsThirdWeight = totalOf(this.sourceHintsWeights).divide(BigInteger.valueOf(3));
        this.eligibleNodeIds = this.nativeWeights.entrySet().stream()
                .filter(entry -> entry.getValue() > 0 && this.sourceHintsWeights.getOrDefault(entry.getKey(), 0L) > 0)
                .map(Map.Entry::getKey)
                .collect(Collectors.toUnmodifiableSet());
    }

    /** Returns whether this node can contribute to this attempt's WRAPS signature. */
    public boolean isEligibleSigner(final long nodeId) {
        return eligibleNodeIds.contains(nodeId);
    }

    /** Returns the immutable set of nodes eligible in this source generation, before attempt exclusions. */
    public @NonNull Set<Long> eligibleNodeIds() {
        return eligibleNodeIds;
    }

    /**
     * Whether every selected node is eligible and the selected set meets both applicable authorities.
     * Actual signature/share validation remains the caller's responsibility.
     */
    public boolean isSatisfiedBy(@NonNull final Set<Long> signerIds) {
        requireNonNull(signerIds);
        if (!eligibleNodeIds.containsAll(signerIds)) {
            return false;
        }
        BigInteger nativeSignedWeight = BigInteger.ZERO;
        BigInteger hintsSignedWeight = BigInteger.ZERO;
        for (final long nodeId : signerIds) {
            nativeSignedWeight = nativeSignedWeight.add(BigInteger.valueOf(nativeWeights.get(nodeId)));
            hintsSignedWeight = hintsSignedWeight.add(BigInteger.valueOf(sourceHintsWeights.get(nodeId)));
        }
        return nativeSignedWeight.compareTo(nativeHalfWeight) > 0
                && (!requiresProvenance || hintsSignedWeight.compareTo(hintsThirdWeight) > 0);
    }

    /**
     * Whether the eligible nodes remaining after exclusions can meet the original generation's quorums.
     * Neither the canonical WRAPS denominator nor the source hinTS denominator is reduced by exclusions.
     */
    public boolean canCompleteWithout(@NonNull final Set<Long> excludedNodeIds) {
        requireNonNull(excludedNodeIds);
        return isSatisfiedBy(eligibleNodeIds.stream()
                .filter(nodeId -> !excludedNodeIds.contains(nodeId))
                .collect(Collectors.toSet()));
    }

    private static BigInteger totalOf(final Map<Long, Long> weights) {
        return weights.values().stream().map(BigInteger::valueOf).reduce(BigInteger.ZERO, BigInteger::add);
    }
}
