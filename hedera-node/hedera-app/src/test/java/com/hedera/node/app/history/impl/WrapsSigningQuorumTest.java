// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.history.impl;

import static com.hedera.node.app.history.HistoryLibrary.MISSING_SCHNORR_KEY;
import static org.junit.jupiter.api.Assertions.*;

import com.hedera.node.app.history.HistoryLibrary.AddressBook;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;

class WrapsSigningQuorumTest {
    @Test
    void sparseHintsMajorityCannotFreezeBeforeCanonicalNativeMajority() {
        final var canonicalWeights = equalWeights(7);
        final var hintsWeights = equalWeights(5);
        final var subject = new WrapsSigningQuorum(realKeysBook(canonicalWeights), hintsWeights, true);

        assertFalse(subject.isSatisfiedBy(Set.of(0L, 1L, 2L)));
        assertTrue(subject.isSatisfiedBy(Set.of(0L, 1L, 2L, 3L)));
        assertFalse(subject.isEligibleSigner(5L));
        assertFalse(subject.isSatisfiedBy(Set.of(0L, 1L, 2L, 5L)));
    }

    @Test
    void sentinelListedWeightsRemainCommittedButDoNotCountForNativeQuorum() {
        final var weights = equalWeights(7);
        weights.put(3L, 1_000L);
        weights.put(4L, 2_000L);
        final var book = AddressBook.from(
                weights, nodeId -> nodeId == 3L || nodeId == 4L ? MISSING_SCHNORR_KEY.toByteArray() : new byte[] {1});
        final var originalWeights = book.weights().clone();
        final var originalNodeIds = book.nodeIds().clone();
        final var subject = new WrapsSigningQuorum(book, weights, false);

        assertTrue(subject.isSatisfiedBy(Set.of(0L, 1L, 5L)));
        assertFalse(subject.isSatisfiedBy(Set.of(0L, 1L)));
        assertFalse(subject.isEligibleSigner(3L));
        assertFalse(subject.isSatisfiedBy(Set.of(0L, 1L, 3L, 5L)));
        assertArrayEquals(originalWeights, book.weights());
        assertArrayEquals(originalNodeIds, book.nodeIds());
        assertArrayEquals(MISSING_SCHNORR_KEY.toByteArray(), book.publicKeys()[3]);
        assertArrayEquals(MISSING_SCHNORR_KEY.toByteArray(), book.publicKeys()[4]);
    }

    @Test
    void identityWithDifferentProofOfKnowledgeStillHasZeroNativeWeight() {
        final var identityKey = MISSING_SCHNORR_KEY.toByteArray();
        identityKey[identityKey.length - 1] = 1;
        final var weights = new TreeMap<>(Map.of(0L, 1L, 1L, 1L, 2L, 1L, 3L, 1_000L));
        final var book = AddressBook.from(weights, nodeId -> nodeId == 3L ? identityKey : new byte[] {1});
        final var subject = new WrapsSigningQuorum(book, weights, false);

        assertFalse(subject.isEligibleSigner(3L));
        assertTrue(subject.isSatisfiedBy(Set.of(0L, 1L)));
        assertTrue(subject.canCompleteWithout(Set.of(2L, 3L)));
        assertFalse(subject.isSatisfiedBy(Set.of(0L, 1L, 3L)));
        assertArrayEquals(identityKey, book.publicKeys()[3]);
        assertArrayEquals(new long[] {1L, 1L, 1L, 1_000L}, book.weights());
    }

    @Test
    void provenanceUsesItsOwnSourceWeightsAndStrictThirdThreshold() {
        final var nativeWeights = new TreeMap<>(Map.of(10L, 10L, 20L, 1L, 30L, 1L));
        final var hintsWeights = Map.of(10L, 4L, 20L, 4L, 30L, 4L);
        final var withProvenance = new WrapsSigningQuorum(realKeysBook(nativeWeights), hintsWeights, true);
        final var withoutProvenance = new WrapsSigningQuorum(realKeysBook(nativeWeights), hintsWeights, false);

        // Node 10 has a native majority, but exactly one third of hinTS weight is insufficient.
        assertFalse(withProvenance.isSatisfiedBy(Set.of(10L)));
        assertTrue(withProvenance.isSatisfiedBy(Set.of(10L, 20L)));
        assertTrue(withoutProvenance.isSatisfiedBy(Set.of(10L)));
    }

    @Test
    void exclusionsDoNotReduceEitherQuorumDenominator() {
        final var subject = new WrapsSigningQuorum(realKeysBook(equalWeights(7)), equalWeights(5), true);

        assertTrue(subject.canCompleteWithout(Set.of(0L)));
        assertFalse(subject.canCompleteWithout(Set.of(0L, 1L)));
        assertTrue(subject.canCompleteWithout(Set.of(5L, 6L)));

        final var nativeWeights = new TreeMap<>(Map.of(10L, 10L, 20L, 1L, 30L, 1L));
        final var separateProvenance =
                new WrapsSigningQuorum(realKeysBook(nativeWeights), Map.of(10L, 4L, 20L, 4L, 30L, 4L), true);
        assertFalse(separateProvenance.canCompleteWithout(Set.of(20L, 30L)));
    }

    @Test
    void quorumSnapshotDoesNotFollowMutableBookOrAuthority() {
        final var weights = equalWeights(3);
        final var book = realKeysBook(weights);
        final var subject = new WrapsSigningQuorum(book, weights, true);

        weights.put(0L, 10_000L);
        book.weights()[0] = 10_000L;
        book.nodeIds()[0] = 42L;
        book.publicKeys()[0] = MISSING_SCHNORR_KEY.toByteArray();

        assertTrue(subject.isEligibleSigner(0L));
        assertFalse(subject.isEligibleSigner(42L));
        assertFalse(subject.isSatisfiedBy(Set.of(0L)));
        assertTrue(subject.isSatisfiedBy(Set.of(0L, 1L)));
        assertThrows(
                UnsupportedOperationException.class,
                () -> subject.eligibleNodeIds().clear());
    }

    @Test
    void strictNativeMajorityDoesNotOverflowWhenWeightTotalExceedsSignedLong() {
        final var weights = new TreeMap<>(Map.of(10L, Long.MAX_VALUE, 30L, Long.MAX_VALUE));
        final var subject = new WrapsSigningQuorum(realKeysBook(weights), weights, false);

        assertFalse(subject.isSatisfiedBy(Set.of(10L)));
        assertTrue(subject.isSatisfiedBy(Set.of(10L, 30L)));
    }

    @Test
    void emptyAndIdentityOnlyBooksHaveNoSigningQuorum() {
        final var empty = new WrapsSigningQuorum(realKeysBook(new TreeMap<>()), Map.of(), false);
        assertFalse(empty.isSatisfiedBy(Set.of()));
        assertFalse(empty.canCompleteWithout(Set.of()));

        final var weights = equalWeights(1);
        final var sentinelOnly = new WrapsSigningQuorum(
                AddressBook.from(weights, nodeId -> MISSING_SCHNORR_KEY.toByteArray()), weights, false);
        assertFalse(sentinelOnly.canCompleteWithout(Set.of()));
        assertFalse(sentinelOnly.isEligibleSigner(0L));
    }

    private static TreeMap<Long, Long> equalWeights(final int count) {
        final var weights = new TreeMap<Long, Long>();
        for (long nodeId = 0; nodeId < count; nodeId++) {
            weights.put(nodeId, 1L);
        }
        return weights;
    }

    private static AddressBook realKeysBook(final TreeMap<Long, Long> weights) {
        return AddressBook.from(weights, nodeId -> new byte[] {1});
    }
}
