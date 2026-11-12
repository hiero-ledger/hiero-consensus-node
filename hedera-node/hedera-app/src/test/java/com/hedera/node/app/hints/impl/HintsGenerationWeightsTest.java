// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.hints.impl;

import static org.junit.jupiter.api.Assertions.*;

import com.hedera.hapi.node.state.hints.HintsConstruction;
import com.hedera.hapi.node.state.hints.HintsScheme;
import com.hedera.hapi.node.state.hints.NodePartyId;
import com.hedera.hapi.node.state.hints.PreprocessedKeys;
import com.hedera.hapi.node.state.history.HintsKeyProvenance;
import com.hedera.hapi.node.state.history.History;
import com.hedera.hapi.node.state.history.HistoryProof;
import com.hedera.hapi.node.state.history.HistoryProofConstruction;
import com.hedera.hapi.node.state.history.ProofNodeWeight;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import java.math.BigInteger;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class HintsGenerationWeightsTest {
    private static final long CONSTRUCTION_ID = 7;
    private static final Bytes TARGET_HASH = Bytes.wrap("committed-generation");
    private static final Bytes VK = Bytes.wrap("generation-vk");
    private static final HintsConstruction HINTS = hints(List.of(new NodePartyId(0, 1, 7)));
    private static final HistoryProofConstruction HISTORY =
            history(List.of(new ProofNodeWeight(0, 7), new ProofNodeWeight(1, 3)));

    @Test
    void resolvesCompleteGenerationWithOnlySchemeMembersEligible() {
        final var weights = HintsGenerationWeights.from(HINTS, HISTORY);
        assertEquals(CONSTRUCTION_ID, weights.constructionId());
        assertEquals(TARGET_HASH, weights.targetRosterHash());
        assertEquals(VK, weights.verificationKey());
        assertEquals(Map.of(0L, 7L, 1L, 3L), weights.fullWeights());
        assertEquals(Map.of(0L, 7L), weights.schemeWeights());
        assertEquals(10, weights.totalWeight());
        assertEquals(7, weights.schemeWeight());
        assertTrue(weights.matches(HINTS));
        // Legacy proofs need not carry provenance, but present provenance must bind the same hinTS id.
        assertEquals(
                weights,
                HintsGenerationWeights.from(
                        HINTS,
                        HISTORY.copyBuilder()
                                .keyProvenance(HintsKeyProvenance.newBuilder()
                                        .targetHintsConstructionId(CONSTRUCTION_ID)
                                        .build())
                                .build()));
    }

    @Test
    void rejectsMissingOrMismatchedGenerationBindings() {
        assertThrows(
                IllegalArgumentException.class, () -> HintsGenerationWeights.from(HintsConstruction.DEFAULT, HISTORY));
        assertThrows(
                IllegalArgumentException.class,
                () -> HintsGenerationWeights.from(
                        HINTS.copyBuilder().hintsScheme(HintsScheme.DEFAULT).build(), HISTORY));
        assertThrows(
                IllegalArgumentException.class,
                () -> HintsGenerationWeights.from(HINTS, HistoryProofConstruction.DEFAULT));
        assertThrows(
                IllegalArgumentException.class,
                () -> HintsGenerationWeights.from(
                        HINTS,
                        HISTORY.copyBuilder()
                                .targetRosterHash(Bytes.wrap("other-generation"))
                                .build()));
        assertThrows(
                IllegalArgumentException.class,
                () -> HintsGenerationWeights.from(
                        HINTS.copyBuilder().targetRosterHash(Bytes.EMPTY).build(),
                        HISTORY.copyBuilder().targetRosterHash(Bytes.EMPTY).build()));
        assertThrows(
                IllegalArgumentException.class,
                () -> HintsGenerationWeights.from(
                        HINTS,
                        HISTORY.copyBuilder()
                                .keyProvenance(HintsKeyProvenance.newBuilder()
                                        .targetHintsConstructionId(CONSTRUCTION_ID + 1)
                                        .build())
                                .build()));
    }

    @Test
    void rejectsAbsentOrDifferentCertifiedVerificationKey() {
        assertThrows(
                IllegalArgumentException.class,
                () -> HintsGenerationWeights.from(
                        HINTS.copyBuilder()
                                .hintsScheme(new HintsScheme(
                                        PreprocessedKeys.DEFAULT,
                                        HINTS.hintsSchemeOrThrow().nodePartyIds()))
                                .build(),
                        HISTORY));
        assertThrows(
                IllegalArgumentException.class,
                () -> HintsGenerationWeights.from(
                        HINTS,
                        HISTORY.copyBuilder()
                                .targetProof(HistoryProof.newBuilder()
                                        .targetNodeWeights(
                                                HISTORY.targetProofOrThrow().targetNodeWeights())
                                        .build())
                                .build()));
        assertThrows(
                IllegalArgumentException.class,
                () -> HintsGenerationWeights.from(
                        HINTS,
                        HISTORY.copyBuilder()
                                .targetProof(HISTORY.targetProofOrThrow()
                                        .copyBuilder()
                                        .targetHistory(new History(Bytes.EMPTY, Bytes.wrap("other-vk")))
                                        .build())
                                .build()));
    }

    @ParameterizedTest
    @MethodSource("malformedSnapshots")
    void rejectsMissingOrMalformedSnapshots(final List<ProofNodeWeight> snapshot) {
        assertThrows(IllegalArgumentException.class, () -> HintsGenerationWeights.from(HINTS, history(snapshot)));
    }

    private static Stream<List<ProofNodeWeight>> malformedSnapshots() {
        return Stream.of(
                List.of(),
                List.of(new ProofNodeWeight(1, 3), new ProofNodeWeight(0, 7)),
                List.of(new ProofNodeWeight(0, 7), new ProofNodeWeight(0, 7)),
                List.of(new ProofNodeWeight(-1, 3), new ProofNodeWeight(0, 7)),
                List.of(new ProofNodeWeight(0, -1), new ProofNodeWeight(1, 3)));
    }

    @ParameterizedTest
    @MethodSource("invalidParties")
    void rejectsPartiesOutsideOrDifferingFromTheirCommittedMembership(final List<NodePartyId> parties) {
        assertThrows(IllegalArgumentException.class, () -> HintsGenerationWeights.from(hints(parties), HISTORY));
    }

    private static Stream<List<NodePartyId>> invalidParties() {
        return Stream.of(
                List.of(),
                List.of(new NodePartyId(0, 1, 6)),
                List.of(new NodePartyId(2, 1, 7)),
                List.of(new NodePartyId(0, 1, 7), new NodePartyId(0, 2, 7)),
                List.of(new NodePartyId(-1, 1, 7)),
                List.of(new NodePartyId(0, 1, -7)));
    }

    @Test
    void rejectsZeroWeightGenerationsAndChecksTotalOverflow() {
        assertThrows(
                IllegalArgumentException.class,
                () -> HintsGenerationWeights.from(
                        hints(List.of(new NodePartyId(0, 1, 0))), history(List.of(new ProofNodeWeight(0, 0)))));
        assertThrows(
                IllegalArgumentException.class,
                () -> HintsGenerationWeights.from(
                        hints(List.of(new NodePartyId(0, 1, 0))),
                        history(List.of(new ProofNodeWeight(0, 0), new ProofNodeWeight(1, 10)))));
        assertThrows(
                ArithmeticException.class,
                () -> HintsGenerationWeights.from(
                        hints(List.of(new NodePartyId(0, 1, Long.MAX_VALUE))),
                        history(List.of(new ProofNodeWeight(0, Long.MAX_VALUE), new ProofNodeWeight(1, 1)))));
    }

    @Test
    void returnedMapsAreImmutableAndDefensivelyCopied() {
        final var resolved = HintsGenerationWeights.from(HINTS, HISTORY);
        assertThrows(
                UnsupportedOperationException.class,
                () -> resolved.fullWeights().put(2L, 5L));
        assertThrows(
                UnsupportedOperationException.class,
                () -> resolved.schemeWeights().put(1L, 3L));
        final var full = new TreeMap<>(resolved.fullWeights());
        final var eligible = new TreeMap<>(resolved.schemeWeights());
        final var copied = new HintsGenerationWeights(CONSTRUCTION_ID, TARGET_HASH, VK, full, eligible, 10, 7);
        full.put(1L, 100L);
        eligible.put(0L, 100L);
        assertEquals(resolved, copied);
    }

    @Test
    void matchesOnlyTheExactSigningGeneration() {
        final var weights = HintsGenerationWeights.from(HINTS, HISTORY);
        assertFalse(weights.matches(
                HINTS.copyBuilder().constructionId(CONSTRUCTION_ID + 1).build()));
        assertFalse(weights.matches(HINTS.copyBuilder()
                .targetRosterHash(Bytes.wrap("other-generation"))
                .build()));
        assertFalse(weights.matches(HINTS.copyBuilder()
                .hintsScheme(new HintsScheme(new PreprocessedKeys(Bytes.EMPTY, Bytes.wrap("other-vk")), List.of()))
                .build()));
        assertFalse(weights.matches(HintsConstruction.DEFAULT));
        assertFalse(weights.matches(
                HINTS.copyBuilder().hintsScheme(HintsScheme.DEFAULT).build()));
    }

    @ParameterizedTest
    @MethodSource("verificationFractions")
    void expressesFullGenerationThresholdRelativeToTheScheme(
            final long schemeWeight,
            final long omittedWeight,
            final long divisor,
            final long expectedNumerator,
            final long expectedDenominator) {
        final var weights = HintsGenerationWeights.from(
                hints(List.of(new NodePartyId(0, 1, schemeWeight))),
                history(List.of(new ProofNodeWeight(0, schemeWeight), new ProofNodeWeight(1, omittedWeight))));
        assertEquals(
                new HintsGenerationWeights.ThresholdFraction(expectedNumerator, expectedDenominator),
                weights.verificationFraction(divisor));
    }

    private static Stream<Arguments> verificationFractions() {
        final long halfMaximum = (Long.MAX_VALUE - 1) / 2;
        return Stream.of(
                Arguments.of(7L, 3L, 2L, 5L, 7L),
                Arguments.of(7L, 4L, 2L, 11L, 14L),
                Arguments.of(7L, 3L, 3L, 10L, 21L),
                Arguments.of(10L, 0L, 2L, 1L, 2L),
                Arguments.of(Long.MAX_VALUE, 0L, 2L, 1L, 2L),
                Arguments.of(halfMaximum, halfMaximum, Long.MAX_VALUE, 2L, Long.MAX_VALUE));
    }

    @Test
    void rejectsNonpositiveDivisors() {
        final var weights = HintsGenerationWeights.from(HINTS, HISTORY);
        assertThrows(IllegalArgumentException.class, () -> weights.verificationFraction(0));
        assertThrows(IllegalArgumentException.class, () -> weights.verificationFraction(-1));
    }

    @Test
    void preservesTheStrictIntegralCutoffWhenTheLiteralFractionExceedsLongRange() {
        final var weights = HintsGenerationWeights.from(
                hints(List.of(new NodePartyId(0, 1, Long.MAX_VALUE - 1))),
                history(List.of(new ProofNodeWeight(0, Long.MAX_VALUE - 1), new ProofNodeWeight(1, 1))));
        final var fraction = weights.verificationFraction(2);
        assertEquals(new HintsGenerationWeights.ThresholdFraction(1, 2), fraction);
        final long cutoff = weights.totalWeight() / 2;
        assertFalse(exceedsNativeFraction(cutoff, weights.schemeWeight(), fraction));
        assertTrue(exceedsNativeFraction(cutoff + 1, weights.schemeWeight(), fraction));
        assertTrue(exceedsNativeFraction(weights.schemeWeight(), weights.schemeWeight(), fraction));
    }

    @Test
    void representsAZeroCutoffWithoutAllowingZeroSignerWeight() {
        final var weights = HintsGenerationWeights.from(
                hints(List.of(new NodePartyId(0, 1, 2))),
                history(List.of(new ProofNodeWeight(0, 2), new ProofNodeWeight(1, 1))));
        final var fraction = weights.verificationFraction(Long.MAX_VALUE);
        assertEquals(new HintsGenerationWeights.ThresholdFraction(1, 3), fraction);
        assertFalse(exceedsNativeFraction(0, weights.schemeWeight(), fraction));
        assertTrue(exceedsNativeFraction(1, weights.schemeWeight(), fraction));
    }

    private static boolean exceedsNativeFraction(
            final long signerWeight, final long schemeWeight, final HintsGenerationWeights.ThresholdFraction fraction) {
        return BigInteger.valueOf(signerWeight)
                        .multiply(BigInteger.valueOf(fraction.denominator()))
                        .compareTo(BigInteger.valueOf(schemeWeight).multiply(BigInteger.valueOf(fraction.numerator())))
                > 0;
    }

    private static HintsConstruction hints(final List<NodePartyId> parties) {
        return HintsConstruction.newBuilder()
                .constructionId(CONSTRUCTION_ID)
                .targetRosterHash(TARGET_HASH)
                .hintsScheme(new HintsScheme(new PreprocessedKeys(Bytes.wrap("generation-ak"), VK), parties))
                .build();
    }

    private static HistoryProofConstruction history(final List<ProofNodeWeight> weights) {
        return HistoryProofConstruction.newBuilder()
                .targetRosterHash(TARGET_HASH)
                .targetProof(HistoryProof.newBuilder()
                        .targetHistory(new History(Bytes.EMPTY, VK))
                        .targetNodeWeights(weights)
                        .build())
                .build();
    }
}
