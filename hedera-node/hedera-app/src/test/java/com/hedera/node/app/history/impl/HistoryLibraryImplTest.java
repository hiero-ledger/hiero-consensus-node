// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.history.impl;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

import com.hedera.cryptography.wraps.SchnorrKeys;
import com.hedera.hapi.node.state.history.History;
import com.hedera.node.app.history.HistoryLibrary;
import com.hedera.node.config.data.NodesConfig;
import com.hedera.node.config.testfixtures.HederaTestConfigBuilder;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.SortedMap;
import java.util.SplittableRandom;
import java.util.TreeMap;
import java.util.stream.IntStream;
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
    void defaultMaxNodesFitInOneWrapsAddressBook() {
        final long maxNodes = HederaTestConfigBuilder.createConfig()
                .getConfigData(NodesConfig.class)
                .maxNumber();
        assertTrue(
                maxNodes <= HistoryLibrary.MAX_ADDRESS_BOOK_SIZE,
                "nodes.maxNumber defaults to " + maxNodes + ", but a WRAPS address book holds at most "
                        + HistoryLibrary.MAX_ADDRESS_BOOK_SIZE + " nodes");
    }

    @Test
    void sentinelPublicKeyProducesNonNullAddressBookHash() {
        final var libraryImpl = new HistoryLibraryImpl();
        final var availableKey = libraryImpl.newSchnorrKeyPair().publicKey();
        assertArrayEquals(
                HistoryLibraryImpl.WRAPS.provideSentinelPublicKey(), HistoryLibrary.MISSING_SCHNORR_KEY.toByteArray());
        // Ensure we can still hash an address book when a node fails to gossip its Schnorr key in time
        final var hash = HistoryLibraryImpl.WRAPS.hashAddressBook(
                new byte[][] {HistoryLibrary.MISSING_SCHNORR_KEY.toByteArray(), availableKey},
                new long[] {1L, 1L},
                new long[] {1L, 2L});
        assertNotNull(hash);
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
                HistoryLibrary.MISSING_SCHNORR_KEY.toByteArray(), addressBook.publicKeys()[1]);
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
    void sentinelPublicKeyMatchesGeneratedSchnorrKeyLength() {
        final var generatedPublicKey = subject.newSchnorrKeyPair().publicKey();

        assertEquals(generatedPublicKey.length, HistoryLibrary.MISSING_SCHNORR_KEY.length());
    }

    @Test
    void verifyCompressedProofReturnsFalseForMalformedInput() {
        assertFalse(subject.verifyCompressedProof(new byte[] {1}, new byte[] {2}, new byte[] {3}));
    }

    @Test
    void hashAddressBookThrowsNullPointerExceptionForNullAddressBook() {
        assertThrows(NullPointerException.class, () -> subject.hashAddressBook(null));
    }

    @Test
    void hashAddressBookThrowsDetailedIllegalArgumentExceptionWhenWrapsReturnsNull() {
        final var addressBook = new HistoryLibrary.AddressBook(
                new long[] {1L, -2L}, new byte[][] {new byte[191], null}, new long[] {7L});

        final var exception = assertThrows(IllegalArgumentException.class, () -> subject.hashAddressBook(addressBook));
        final var message = exception.getMessage();

        assertNotNull(message);
        assertTrue(message.startsWith("WRAPS.hashAddressBook() returned null. Validation details: "));
        assertTrue(message.contains("schnorrPublicKeys.length=2"));
        assertTrue(message.contains("weights.length=2"));
        assertTrue(message.contains("nodeIds.length=1"));
        assertTrue(message.contains("schnorrPublicKeys.length==weights.length=true"));
        assertTrue(message.contains("schnorrPublicKeys.length==nodeIds.length=false"));
        assertTrue(message.contains("validateWeightsSum=false"));
        assertTrue(message.contains("negativeWeights=[#1=-2]"));
        assertTrue(message.contains("sumOverflowed=false"));
        assertTrue(message.contains("validateSchnorrPublicKeys=false"));
        assertTrue(message.contains("#0(nonNull=true, length=191, length==128=false)"));
        assertTrue(message.contains("#1(nonNull=false, length=null, length==128=false)"));
        assertTrue(message.contains("bridgePrechecksPassed=false"));
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
    void computeWrapsMessageThrowsDetailedIllegalArgumentExceptionWhenWrapsReturnsNull() {
        final var addressBook =
                new HistoryLibrary.AddressBook(new long[] {1L}, new byte[][] {new byte[127]}, new long[] {7L});

        final var exception = assertThrows(
                IllegalArgumentException.class, () -> subject.computeWrapsMessage(addressBook, new byte[VK_LENGTH]));

        assertTrue(exception.getMessage().startsWith("WRAPS.computeNetworkID() returned null"));
        assertTrue(exception.getMessage().contains("#0(nonNull=true, length=127, length==128=false)"));
    }

    @Test
    void hashHintsVerificationKeyThrowsIllegalArgumentExceptionForEmptyKey() {
        assertThrows(IllegalArgumentException.class, () -> subject.hashHintsVerificationKey(new byte[0]));
    }

    @Test
    void wrapsProverIsReadyWithEmbeddedPublicParameters() {
        assertTrue(subject.wrapsProverReady());
    }

    @Test
    void genesisAddressBookHashIsPrefixOfLedgerId() {
        final var ledgerId = Bytes.wrap(sequentialBytes(64));

        assertEquals(Bytes.wrap(sequentialBytes(32)), HistoryLibrary.genesisAddressBookHashOf(ledgerId));
    }

    @Test
    void ledgerIdOfHistoryIsTheMessageItsAddressBookSignsToGroundTheChainOfTrust() {
        final var network = TestNetwork.withWeights(1L, 2L, 3L);
        final var hintsVerificationKey = randomBytes(VK_LENGTH);

        final var message = subject.computeWrapsMessage(network.addressBook(), hintsVerificationKey);
        final var history = new History(
                Bytes.wrap(subject.hashAddressBook(network.addressBook())), Bytes.wrap(hintsVerificationKey));

        assertEquals(2 * HistoryLibrary.ADDRESS_BOOK_HASH_LENGTH, message.length);
        assertEquals(Bytes.wrap(message), subject.ledgerIdOf(history));
        assertEquals(history.addressBookHash(), HistoryLibrary.genesisAddressBookHashOf(Bytes.wrap(message)));
    }

    @Test
    void constructsAndVerifiesGenesisAndIncrementalWrapsProofs() {
        // Ground a chain of trust in a genesis address book and its hinTS verification key
        final var genesis = TestNetwork.withWeights(10L, 20L, 30L);
        final var genesisVerificationKey = randomBytes(VK_LENGTH);
        final var genesisBookHash = subject.hashAddressBook(genesis.addressBook());
        final var ledgerId = subject.computeWrapsMessage(genesis.addressBook(), genesisVerificationKey);
        final var genesisSignature = genesis.sign(ledgerId, Set.of(1L, 2L));
        final var genesisProof = subject.constructGenesisWrapsProof(
                genesisBookHash, genesisVerificationKey, genesisSignature, Set.of(1L, 2L), genesis.addressBook());
        assertNotNull(genesisProof);
        assertTrue(subject.verifyCompressedProof(genesisProof.compressed(), ledgerId, genesisVerificationKey));
        // The ledger id is the one implied by the grounding history
        assertEquals(
                Bytes.wrap(ledgerId),
                subject.ledgerIdOf(new History(Bytes.wrap(genesisBookHash), Bytes.wrap(genesisVerificationKey))));
        // A proof only establishes the hinTS verification key it was constructed for
        assertFalse(subject.verifyCompressedProof(genesisProof.compressed(), ledgerId, randomBytes(VK_LENGTH)));

        // Extend the chain of trust to a new address book and its hinTS verification key
        final var target = TestNetwork.withWeights(10L, 20L, 30L, 40L);
        final var targetVerificationKey = randomBytes(VK_LENGTH);
        final var message = subject.computeWrapsMessage(target.addressBook(), targetVerificationKey);
        final var signature = genesis.sign(message, Set.of(0L, 2L));
        final var incrementalProof = subject.constructIncrementalWrapsProof(
                HistoryLibrary.genesisAddressBookHashOf(Bytes.wrap(ledgerId)).toByteArray(),
                genesisProof.uncompressed(),
                genesis.addressBook(),
                target.addressBook(),
                targetVerificationKey,
                signature,
                Set.of(0L, 2L));
        assertNotNull(incrementalProof);
        assertTrue(subject.verifyCompressedProof(incrementalProof.compressed(), ledgerId, targetVerificationKey));
        assertFalse(subject.verifyCompressedProof(incrementalProof.compressed(), ledgerId, genesisVerificationKey));
        // But not in the chain of trust of any other ledger id
        final var otherLedgerId = subject.computeWrapsMessage(target.addressBook(), targetVerificationKey);
        assertFalse(subject.verifyCompressedProof(incrementalProof.compressed(), otherLedgerId, targetVerificationKey));
    }

    /**
     * A network of nodes with Schnorr keys and the given weights, indexed by node id from zero.
     */
    private record TestNetwork(List<SchnorrKeys> keys, HistoryLibrary.AddressBook addressBook) {
        static TestNetwork withWeights(final long... weights) {
            final var library = new HistoryLibraryImpl();
            final var keys = Arrays.stream(weights)
                    .mapToObj(ignore -> library.newSchnorrKeyPair())
                    .toList();
            final var addressBook = new HistoryLibrary.AddressBook(
                    weights,
                    keys.stream().map(SchnorrKeys::publicKey).toArray(byte[][]::new),
                    IntStream.range(0, weights.length).asLongStream().toArray());
            return new TestNetwork(keys, addressBook);
        }

        /**
         * Runs the WRAPS signing protocol among the given signers to produce an aggregate signature on the message.
         */
        byte[] sign(final byte[] message, final Set<Long> signers) {
            final var library = new HistoryLibraryImpl();
            final var sortedSigners = signers.stream().sorted().toList();
            final var entropies =
                    sortedSigners.stream().map(ignore -> randomBytes(32)).toList();
            final var r1Messages = IntStream.range(0, sortedSigners.size())
                    .mapToObj(i -> library.runWrapsPhaseR1(entropies.get(i), message, privateKeyOf(sortedSigners, i)))
                    .toArray(byte[][]::new);
            final var r2Messages = IntStream.range(0, sortedSigners.size())
                    .mapToObj(i -> library.runWrapsPhaseR2(
                            entropies.get(i),
                            message,
                            r1Messages,
                            privateKeyOf(sortedSigners, i),
                            addressBook,
                            signers))
                    .toArray(byte[][]::new);
            final var r3Messages = IntStream.range(0, sortedSigners.size())
                    .mapToObj(i -> library.runWrapsPhaseR3(
                            entropies.get(i),
                            message,
                            r1Messages,
                            r2Messages,
                            privateKeyOf(sortedSigners, i),
                            addressBook,
                            signers))
                    .toArray(byte[][]::new);
            final var signature =
                    library.runAggregationPhase(message, r1Messages, r2Messages, r3Messages, addressBook, signers);
            assertNotNull(signature);
            assertTrue(library.verifyAggregateSignature(
                    message, addressBook.nodeIds(), addressBook.publicKeys(), addressBook.weights(), signature));
            return signature;
        }

        private byte[] privateKeyOf(final List<Long> sortedSigners, final int i) {
            return keys.get(sortedSigners.get(i).intValue()).privateKey();
        }
    }

    private static final int VK_LENGTH = 1096;
    private static final SplittableRandom RANDOM = new SplittableRandom(1_234_567L);

    private static byte[] randomBytes(final int n) {
        final var bytes = new byte[n];
        RANDOM.nextBytes(bytes);
        return bytes;
    }

    private static byte[] sequentialBytes(final int n) {
        final var bytes = new byte[n];
        for (int i = 0; i < n; i++) {
            bytes[i] = (byte) i;
        }
        return bytes;
    }
}
