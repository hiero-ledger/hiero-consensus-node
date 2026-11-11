// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.history.impl;

import static com.hedera.node.app.history.HistoryLibrary.MISSING_SCHNORR_KEY;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.hedera.hapi.node.state.history.History;
import com.hedera.hapi.node.state.history.HistoryProof;
import com.hedera.hapi.node.state.history.ProofKey;
import com.hedera.hapi.node.state.history.ProofNodeWeight;
import com.hedera.node.app.history.HistoryLibrary;
import com.hedera.node.app.history.HistoryLibrary.AddressBook;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;

class HistoryProofAddressBooksTest {
    private static final Bytes HASH = Bytes.wrap("committed-book-hash");
    private static final Bytes KEY_A = Bytes.wrap("selected-key-a");
    private static final Bytes KEY_C = Bytes.wrap("selected-key-c");
    private static final TreeMap<Long, Long> WEIGHTS = new TreeMap<>(Map.of(2L, 20L, 7L, 30L, 11L, 40L));

    @Test
    void snapshotRoundTripsCompleteMembershipFrozenKeysAndSentinelListedWeight() throws Exception {
        final var proof = proof().copyBuilder()
                .targetNodeWeights(HistoryProofAddressBooks.weightsFrom(WEIGHTS))
                .build();
        final var restored = HistoryProof.PROTOBUF.parse(HistoryProof.PROTOBUF.toBytes(proof));
        final var weights = HistoryProofAddressBooks.targetWeights(restored).orElseThrow();
        assertEquals(WEIGHTS, weights);
        assertThrows(UnsupportedOperationException.class, () -> weights.put(7L, 0L));

        final var book = HistoryProofAddressBooks.targetBook(restored);
        assertArrayEquals(new long[] {2L, 7L, 11L}, book.nodeIds());
        assertArrayEquals(new long[] {20L, 30L, 40L}, book.weights());
        assertArrayEquals(KEY_A.toByteArray(), book.publicKeys()[0]);
        assertArrayEquals(MISSING_SCHNORR_KEY.toByteArray(), book.publicKeys()[1]);
        assertArrayEquals(KEY_C.toByteArray(), book.publicKeys()[2]);
    }

    @Test
    void noSnapshotDoesNotGuessThePlatformOrHintsMembership() {
        assertTrue(HistoryProofAddressBooks.targetWeights(proof()).isEmpty());
        assertThrows(IllegalArgumentException.class, () -> HistoryProofAddressBooks.targetBook(proof()));
    }

    @Test
    void legacyEnrichmentRequiresExactCommitmentAndUsesOnlyHistoricalKeys() {
        final var library = mock(HistoryLibrary.class);
        when(library.hashAddressBook(any(AddressBook.class))).thenAnswer(invocation -> {
            final AddressBook book = invocation.getArgument(0);
            assertArrayEquals(new long[] {2L, 7L, 11L}, book.nodeIds());
            assertArrayEquals(new long[] {20L, 30L, 40L}, book.weights());
            assertArrayEquals(MISSING_SCHNORR_KEY.toByteArray(), book.publicKeys()[1]);
            return HASH.toByteArray();
        });

        final var enriched = HistoryProofAddressBooks.withTargetWeights(proof(), WEIGHTS, library);

        assertEquals(proof().targetProofKeys(), enriched.targetProofKeys());
        assertEquals(proof().targetHistory(), enriched.targetHistory());
        assertEquals(WEIGHTS, HistoryProofAddressBooks.targetWeights(enriched).orElseThrow());
    }

    @Test
    void legacyEnrichmentRefusesDifferentCommitmentOrAnExistingSnapshotChange() {
        final var library = mock(HistoryLibrary.class);
        when(library.hashAddressBook(any(AddressBook.class)))
                .thenReturn(Bytes.wrap("different").toByteArray());
        assertThrows(
                IllegalArgumentException.class,
                () -> HistoryProofAddressBooks.withTargetWeights(proof(), WEIGHTS, library));

        final var alreadyFrozen = proof().copyBuilder()
                .targetNodeWeights(HistoryProofAddressBooks.weightsFrom(WEIGHTS))
                .build();
        final var changedWeights = new TreeMap<>(WEIGHTS);
        changedWeights.put(7L, 0L);
        final var unusedLibrary = mock(HistoryLibrary.class);
        assertThrows(
                IllegalArgumentException.class,
                () -> HistoryProofAddressBooks.withTargetWeights(alreadyFrozen, changedWeights, unusedLibrary));
        verifyNoInteractions(unusedLibrary);
    }

    @Test
    void rejectsAmbiguousOrNegativeMembershipInsteadOfResortingIt() {
        for (final var malformed : List.of(
                List.of(new ProofNodeWeight(7L, 30L), new ProofNodeWeight(2L, 20L)),
                List.of(new ProofNodeWeight(2L, 20L), new ProofNodeWeight(2L, 30L)),
                List.of(new ProofNodeWeight(-1L, 20L)),
                List.of(new ProofNodeWeight(2L, -1L)))) {
            final var proof = proof().copyBuilder().targetNodeWeights(malformed).build();
            assertThrows(IllegalArgumentException.class, () -> HistoryProofAddressBooks.targetWeights(proof));
        }
    }

    @Test
    void snapshotEncodingUsesNaturalNodeOrderAndRejectsNegativeValues() {
        final var reverseWeights = new TreeMap<Long, Long>(Comparator.reverseOrder());
        reverseWeights.putAll(WEIGHTS);
        assertEquals(
                List.of(new ProofNodeWeight(2L, 20L), new ProofNodeWeight(7L, 30L), new ProofNodeWeight(11L, 40L)),
                HistoryProofAddressBooks.weightsFrom(reverseWeights));
        assertThrows(
                IllegalArgumentException.class,
                () -> HistoryProofAddressBooks.weightsFrom(new TreeMap<>(Map.of(-1L, 10L))));
        assertThrows(
                IllegalArgumentException.class,
                () -> HistoryProofAddressBooks.weightsFrom(new TreeMap<>(Map.of(1L, -10L))));
    }

    @Test
    void rejectsReorderedDuplicateUnlistedOrEmptySelectedKeys() {
        for (final var malformed : List.of(
                List.of(new ProofKey(11L, KEY_C), new ProofKey(2L, KEY_A)),
                List.of(new ProofKey(2L, KEY_A), new ProofKey(2L, KEY_C)),
                List.of(new ProofKey(3L, KEY_A)),
                List.of(new ProofKey(2L, Bytes.EMPTY)),
                List.of(new ProofKey(2L, KEY_A), new ProofKey(11L, KEY_A)))) {
            final var proof = proof().copyBuilder()
                    .targetNodeWeights(HistoryProofAddressBooks.weightsFrom(WEIGHTS))
                    .targetProofKeys(malformed)
                    .build();
            assertThrows(IllegalArgumentException.class, () -> HistoryProofAddressBooks.targetBook(proof));
        }
    }

    @Test
    void permitsMultipleExplicitSentinelSeatsWithoutZeroingTheirListedWeights() {
        final var proof = proof().copyBuilder()
                .targetNodeWeights(HistoryProofAddressBooks.weightsFrom(WEIGHTS))
                .targetProofKeys(new ProofKey(2L, MISSING_SCHNORR_KEY), new ProofKey(7L, MISSING_SCHNORR_KEY))
                .build();
        assertArrayEquals(
                new long[] {20L, 30L, 40L},
                HistoryProofAddressBooks.targetBook(proof).weights());
    }

    @Test
    void preservesRepeatedHistoricalIdentityKeysWithDifferentAttestationBytes() {
        final var identityKey = MISSING_SCHNORR_KEY.toByteArray();
        identityKey[identityKey.length - 1] = 1;
        final var attestedIdentity = Bytes.wrap(identityKey);
        final var proof = proof().copyBuilder()
                .targetNodeWeights(HistoryProofAddressBooks.weightsFrom(WEIGHTS))
                .targetProofKeys(new ProofKey(2L, attestedIdentity), new ProofKey(7L, attestedIdentity))
                .build();

        final var restored = HistoryProofAddressBooks.targetBook(proof);
        assertArrayEquals(identityKey, restored.publicKeys()[0]);
        assertArrayEquals(identityKey, restored.publicKeys()[1]);
        assertArrayEquals(new long[] {20L, 30L, 40L}, restored.weights());
    }

    private static HistoryProof proof() {
        return HistoryProof.newBuilder()
                .targetProofKeys(new ProofKey(2L, KEY_A), new ProofKey(11L, KEY_C))
                .targetHistory(new History(HASH, Bytes.wrap("verification-key")))
                .build();
    }
}
