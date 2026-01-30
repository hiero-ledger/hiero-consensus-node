// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.history.impl;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

import com.hedera.cryptography.wraps.WRAPSVerificationKey;
import com.hedera.node.app.history.HistoryLibrary;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;
import org.hiero.base.utility.CommonUtils;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class HistoryLibraryImplTest {

    @Mock
    private HistoryLibrary library;

    private final HistoryLibraryImpl subject = new HistoryLibraryImpl();

    @Test
    void wrapsVerificationKeyIsTbd() {
        assertArrayEquals(WRAPSVerificationKey.getCurrentKey(), subject.wrapsVerificationKey());
    }

    @Test
    void computeHashBuildsCanonicalAddressBookAndWrapsResult() {
        final var nodeIds = Set.of(3L, 1L, 2L);
        final var expectedHash = new byte[] {42};
        given(library.hashAddressBook(any())).willReturn(expectedHash);

        final var result =
                HistoryLibrary.computeHash(library, nodeIds, id -> id * 10L, id -> Bytes.wrap(new byte[] {(byte) id}));

        assertEquals(Bytes.wrap(expectedHash), result);

        final ArgumentCaptor<HistoryLibrary.AddressBook> captor =
                ArgumentCaptor.forClass(HistoryLibrary.AddressBook.class);
        verify(library).hashAddressBook(captor.capture());

        final var addressBook = captor.getValue();
        assertArrayEquals(new long[] {10L, 20L, 30L}, addressBook.weights());
        assertArrayEquals(new long[] {1L, 2L, 3L}, addressBook.nodeIds());
        assertArrayEquals(new byte[] {1}, addressBook.publicKeys()[0]);
        assertArrayEquals(new byte[] {2}, addressBook.publicKeys()[1]);
        assertArrayEquals(new byte[] {3}, addressBook.publicKeys()[2]);
    }

    @Test
    void addressBookFromUsesSortedWeightsAndDefaultsMissingPublicKeys() {
        final SortedMap<Long, Long> weights = new TreeMap<>();
        weights.put(2L, 20L);
        weights.put(1L, 10L);

        final SortedMap<Long, byte[]> publicKeys = new TreeMap<>();
        publicKeys.put(1L, new byte[] {0x01});

        final var addressBook = HistoryLibrary.AddressBook.from(weights, publicKeys);

        assertArrayEquals(new long[] {10L, 20L}, addressBook.weights());
        assertArrayEquals(new long[] {1L, 2L}, addressBook.nodeIds());
        assertArrayEquals(new byte[] {0x01}, addressBook.publicKeys()[0]);
        assertArrayEquals(
                HistoryLibrary.EMPTY_PUBLIC_KEY.toByteArray(), addressBook.publicKeys()[1]);
    }

    @Test
    void addressBookFromUsesFunctionForPublicKeys() {
        final SortedMap<Long, Long> weights = new TreeMap<>();
        weights.put(2L, 20L);
        weights.put(1L, 10L);

        final var addressBook = HistoryLibrary.AddressBook.from(weights, nodeId -> new byte[] {(byte) (nodeId + 40)});

        assertArrayEquals(new long[] {10L, 20L}, addressBook.weights());
        assertArrayEquals(new long[] {1L, 2L}, addressBook.nodeIds());
        assertArrayEquals(new byte[] {41}, addressBook.publicKeys()[0]);
        assertArrayEquals(new byte[] {42}, addressBook.publicKeys()[1]);
    }

    @Test
    void signersMaskMarksOnlySignerNodeIds() {
        final var weights = new long[] {1L, 1L, 1L};
        final var publicKeys = new byte[][] {new byte[] {0}, new byte[] {1}, new byte[] {2}};
        final var nodeIds = new long[] {10L, 20L, 30L};
        final var addressBook = new HistoryLibrary.AddressBook(weights, publicKeys, nodeIds);

        final var mask = addressBook.signersMask(Set.of(10L, 30L, 40L));

        assertArrayEquals(new boolean[] {true, false, true}, mask);
    }

    @Test
    void toStringIncludesIndexWeightAndHexKey() {
        final var weights = new long[] {5L};
        final var publicKeys = new byte[][] {new byte[] {0x01, 0x02}};
        final var nodeIds = new long[] {123L};
        final var addressBook = new HistoryLibrary.AddressBook(weights, publicKeys, nodeIds);

        final var expected = "AddressBook[(#0 :: weight=5 :: public_key=" + CommonUtils.hex(publicKeys[0]) + ")]";

        assertEquals(expected, addressBook.toString());
    }

    @Test
    void newSchnorrKeyPairReturnsNonNull() {
        final var keys = subject.newSchnorrKeyPair();
        assertNotNull(keys);
    }

    @Test
    void wrapsPhasesAndVerificationCoverAllMethods() {
        final var keys = subject.newSchnorrKeyPair();

        final var weights = new long[] {1L};
        final var publicKeys = new byte[1][];
        publicKeys[0] = keys.publicKey();
        final var nodeIds = new long[] {123L};
        final var addressBook = new HistoryLibrary.AddressBook(weights, publicKeys, nodeIds);

        final var hash = subject.hashAddressBook(addressBook);
        assertNotNull(hash);

        final var hintsKey = new byte[32];
        final var message = subject.computeWrapsMessage(addressBook, hintsKey);
        assertNotNull(message);

        final var entropy = new byte[32];
        final var privateKey = keys.privateKey();

        final var r1 = subject.runWrapsPhaseR1(entropy, message, privateKey);
        assertNotNull(r1);

        final var signers = Set.of(123L);
        final var r1Messages = new byte[][] {r1};
        final var r2 = subject.runWrapsPhaseR2(entropy, message, r1Messages, privateKey, addressBook, signers);
        assertNotNull(r2);

        final var r2Messages = new byte[][] {r2};
        final var r3 =
                subject.runWrapsPhaseR3(entropy, message, r1Messages, r2Messages, privateKey, addressBook, signers);
        assertNotNull(r3);

        final var r3Messages = new byte[][] {r3};
        final var signature =
                subject.runAggregationPhase(message, r1Messages, r2Messages, r3Messages, addressBook, signers);
        assertNotNull(signature);

        assertDoesNotThrow(() -> subject.verifyAggregateSignature(message, nodeIds, publicKeys, weights, signature));
    }

    @Test
    void wrapsLibraryBridgeIsNotReady() {
        assertFalse(subject.wrapsProverReady());
    }
}
