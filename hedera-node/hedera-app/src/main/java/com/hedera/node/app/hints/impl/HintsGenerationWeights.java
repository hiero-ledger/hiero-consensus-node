// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.hints.impl;

import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.state.hints.HintsConstruction;
import com.hedera.hapi.node.state.history.HistoryProofConstruction;
import com.hedera.node.app.history.impl.HistoryProofAddressBooks;
import com.hedera.node.config.data.TssConfig;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.math.BigInteger;
import java.util.Collections;
import java.util.SortedMap;
import java.util.TreeMap;

/** Complete listed weights and eligible party weights from one committed TSS generation. */
public record HintsGenerationWeights(
        long constructionId,
        @NonNull Bytes targetRosterHash,
        @NonNull Bytes verificationKey,
        @NonNull SortedMap<Long, Long> fullWeights,
        @NonNull SortedMap<Long, Long> schemeWeights,
        long totalWeight,
        long schemeWeight) {
    public HintsGenerationWeights {
        requireNonNull(targetRosterHash);
        requireNonNull(verificationKey);
        fullWeights = Collections.unmodifiableSortedMap(new TreeMap<>(requireNonNull(fullWeights)));
        schemeWeights = Collections.unmodifiableSortedMap(new TreeMap<>(requireNonNull(schemeWeights)));
        final boolean zeroWeightCompatibility =
                totalWeight == 0 && schemeWeight == 0 && fullWeights.equals(schemeWeights);
        if ((!zeroWeightCompatibility && (totalWeight <= 0 || schemeWeight <= 0))
                || sum(fullWeights) != totalWeight
                || sum(schemeWeights) != schemeWeight) {
            throw new IllegalArgumentException("A hinTS generation must have positive, consistent weight totals");
        }
        for (final var entry : schemeWeights.entrySet()) {
            if (!entry.getValue().equals(fullWeights.get(entry.getKey()))) {
                throw new IllegalArgumentException("hinTS party weights must match their committed generation");
            }
        }
    }

    /** Full membership is required only for real history-backed hinTS signatures. */
    public static boolean requiresHistory(@NonNull final TssConfig config) {
        requireNonNull(config);
        return config.historyEnabled() && !config.forceMockSignatures();
    }

    /** Scheme-relative compatibility for history-disabled or mocked signing, including legacy mock identities. */
    public static @NonNull HintsGenerationWeights forScheme(@NonNull final HintsConstruction hints) {
        requireNonNull(hints);
        try {
            if (!hints.hasHintsScheme() || !hints.hintsSchemeOrThrow().hasPreprocessedKeys()) {
                throw new IllegalArgumentException("Compatibility signing requires a completed scheme");
            }
            final var weights = schemeWeights(hints);
            final long total = sum(weights);
            return new HintsGenerationWeights(
                    hints.constructionId(),
                    hints.targetRosterHash(),
                    hints.hintsSchemeOrThrow().preprocessedKeysOrThrow().verificationKey(),
                    weights,
                    weights,
                    total,
                    total);
        } catch (IllegalArgumentException | ArithmeticException e) {
            throw new IllegalArgumentException(hintsIdentity(hints) + ": " + e.getMessage(), e);
        }
    }

    /** A strict threshold snapshot is unavailable or incompatible with the requested signing generation. */
    public static final class InvalidGenerationException extends IllegalArgumentException {
        private static final long serialVersionUID = 1L;

        private InvalidGenerationException(final String message, final Throwable cause) {
            super(message, cause);
        }
    }

    /**
     * Resolves a complete generation without consulting the platform roster. A history proof from another
     * target or verification key cannot supply the denominator, even if its participating nodes overlap.
     *
     * @throws IllegalArgumentException if the generation bindings or canonical membership do not match
     */
    public static @NonNull HintsGenerationWeights from(
            @NonNull final HintsConstruction hints, @Nullable final HistoryProofConstruction history) {
        requireNonNull(hints);
        final String identity = hintsIdentity(hints)
                + (history == null
                        ? "; history construction is absent"
                        : "; history construction #" + history.constructionId() + " (targetRosterHash="
                                + history.targetRosterHash().toHex() + ")");
        try {
            if (!hints.hasHintsScheme() || !hints.hintsSchemeOrThrow().hasPreprocessedKeys()) {
                throw new IllegalArgumentException("The hinTS scheme is incomplete");
            }
            if (history == null || !history.hasTargetProof()) {
                throw new IllegalArgumentException("Strict thresholds require the matching completed history proof");
            }
            if (hints.targetRosterHash().length() == 0
                    || !hints.targetRosterHash().equals(history.targetRosterHash())) {
                throw new IllegalArgumentException("The target roster hashes are missing or do not match");
            }
            final var proof = history.targetProofOrThrow();
            final var key = hints.hintsSchemeOrThrow().preprocessedKeysOrThrow().verificationKey();
            if (key.length() == 0
                    || !proof.hasTargetHistory()
                    || !proof.targetHistoryOrThrow().metadata().equals(key)) {
                throw new IllegalArgumentException("The history metadata does not certify the hinTS verification key");
            }
            if (history.hasKeyProvenance()
                    && history.keyProvenanceOrThrow().targetHintsConstructionId() != hints.constructionId()) {
                throw new IllegalArgumentException("The history provenance targets hinTS construction #"
                        + history.keyProvenanceOrThrow().targetHintsConstructionId());
            }
            final var fullWeights = HistoryProofAddressBooks.targetWeights(proof)
                    .orElseThrow(() -> new IllegalArgumentException(
                            proof.uncompressedWrapsProof().length() == 0
                                    ? "This legacy non-WRAPS proof has no canonical membership snapshot; strict thresholds "
                                            + "require a supported WRAPS generation or a fresh-genesis migration"
                                    : "The WRAPS membership snapshot is absent; recover the hash-verified book or complete "
                                            + "a fresh genesis proof before using strict thresholds"));
            final var schemeWeights = schemeWeights(hints);
            final long total = sum(fullWeights);
            final long eligibleTotal = sum(schemeWeights);
            if (total <= 0 || eligibleTotal <= 0) {
                throw new IllegalArgumentException(
                        "A strict hinTS generation requires positive full and eligible totals");
            }
            return new HintsGenerationWeights(
                    hints.constructionId(),
                    hints.targetRosterHash(),
                    key,
                    fullWeights,
                    schemeWeights,
                    total,
                    eligibleTotal);
        } catch (IllegalArgumentException | ArithmeticException e) {
            throw new InvalidGenerationException(identity + ": " + e.getMessage(), e);
        }
    }

    /** Whether these weights belong to this exact signing generation. */
    public boolean matches(@NonNull final HintsConstruction hints) {
        return hints.constructionId() == constructionId
                && hints.targetRosterHash().equals(targetRosterHash)
                && hints.hasHintsScheme()
                && hints.hintsSchemeOrThrow().hasPreprocessedKeys()
                && hints.hintsSchemeOrThrow()
                        .preprocessedKeysOrThrow()
                        .verificationKey()
                        .equals(verificationKey);
    }

    /** A native verification fraction is relative to the scheme weight committed inside the verification key. */
    public record ThresholdFraction(long numerator, long denominator) {}

    /**
     * Requires a signature to exceed {@code totalWeight / divisor}, expressed as a fraction of scheme weight.
     * Reduction precedes conversion to native integer arguments so an intermediate product cannot overflow.
     */
    public @NonNull ThresholdFraction verificationFraction(final long divisor) {
        if (divisor <= 0) {
            throw new IllegalArgumentException("A signing threshold divisor must be positive");
        }
        if (totalWeight == 0 && schemeWeight == 0) {
            return new ThresholdFraction(1, divisor);
        }
        final var numerator = BigInteger.valueOf(totalWeight);
        final var denominator = BigInteger.valueOf(schemeWeight).multiply(BigInteger.valueOf(divisor));
        final var gcd = numerator.gcd(denominator);
        final var reducedNumerator = numerator.divide(gcd);
        final var reducedDenominator = denominator.divide(gcd);
        if (reducedDenominator.bitLength() <= 63) {
            return new ThresholdFraction(reducedNumerator.longValueExact(), reducedDenominator.longValueExact());
        }
        // Native signer weight is integral: W > T/d is exactly W > floor(T/d). This alternate
        // fraction preserves the predicate when the literal reduced denominator exceeds a Java long.
        final long cutoff = totalWeight / divisor;
        if (cutoff == 0) {
            return new ThresholdFraction(1, Math.addExact(schemeWeight, 1));
        }
        final long cutoffGcd =
                BigInteger.valueOf(cutoff).gcd(BigInteger.valueOf(schemeWeight)).longValueExact();
        return new ThresholdFraction(cutoff / cutoffGcd, schemeWeight / cutoffGcd);
    }

    private static long sum(final SortedMap<Long, Long> weights) {
        long total = 0;
        for (final var entry : weights.entrySet()) {
            if (entry.getKey() < 0 || entry.getValue() < 0) {
                throw new IllegalArgumentException("hinTS generation weights and node ids must be nonnegative");
            }
            total = Math.addExact(total, entry.getValue());
        }
        return total;
    }

    private static SortedMap<Long, Long> schemeWeights(final HintsConstruction hints) {
        final var weights = new TreeMap<Long, Long>();
        for (final var party : hints.hintsSchemeOrThrow().nodePartyIds()) {
            if (weights.putIfAbsent(party.nodeId(), party.partyWeight()) != null) {
                throw new IllegalArgumentException("Duplicate hinTS participant node " + party.nodeId());
            }
        }
        return weights;
    }

    private static String hintsIdentity(final HintsConstruction hints) {
        return "hinTS construction #" + hints.constructionId() + " (targetRosterHash="
                + hints.targetRosterHash().toHex() + ")";
    }
}
