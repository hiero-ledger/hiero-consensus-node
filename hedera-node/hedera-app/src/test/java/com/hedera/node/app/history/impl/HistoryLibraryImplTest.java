// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.history.impl;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

import com.hedera.cryptography.wraps.SchnorrKeys;
import com.hedera.hapi.node.state.history.History;
import com.hedera.node.app.hints.HintsService;
import com.hedera.node.app.history.HistoryLibrary;
import com.hedera.node.config.data.NodesConfig;
import com.hedera.node.config.testfixtures.HederaTestConfigBuilder;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SortedMap;
import java.util.SplittableRandom;
import java.util.TreeMap;
import java.util.stream.IntStream;
import org.hiero.base.utility.CommonUtils;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
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
    void defaultMaxNodesFitTheCurrentHintsPartyAllocation() {
        final long maxNodes = HederaTestConfigBuilder.createConfig()
                .getConfigData(NodesConfig.class)
                .maxNumber();

        assertEquals(62L, maxNodes);
        assertEquals(64, HintsService.partySizeForRosterNodeCount(Math.toIntExact(maxNodes)));
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
    void addressBookCanRepeatTheSentinelKeyButNoOtherKey() {
        // ProofControllerImpl relies on this to keep invalid and copied proof keys out of a target address book
        final var key = subject.newSchnorrKeyPair().publicKey();
        final var otherKey = subject.newSchnorrKeyPair().publicKey();
        final var sentinel = HistoryLibrary.MISSING_SCHNORR_KEY.toByteArray();

        assertDoesNotThrow(() -> subject.hashAddressBook(addressBookWith(key, otherKey, sentinel, sentinel)));
        assertThrows(IllegalArgumentException.class, () -> subject.hashAddressBook(addressBookWith(key, key)));
        assertThrows(
                IllegalArgumentException.class,
                () -> subject.hashAddressBook(addressBookWith(key, new byte[key.length])));
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

    @ParameterizedTest
    @ValueSource(ints = {0, 31, 32, 63, 65})
    void incrementalProofConstructionRejectsIncompleteOrOversizedLedgerIds(final int length) {
        final var book = TestNetwork.withWeights(1L).addressBook();

        final var exception = assertThrows(
                IllegalArgumentException.class,
                () -> subject.constructIncrementalWrapsProof(
                        new byte[length], new byte[0], book, book, new byte[VK_LENGTH], new byte[0], Set.of(0L)));

        assertEquals("The genesis ledger id must be 64 bytes", exception.getMessage());
    }

    @Test
    void ledgerIdOfHistoryIsTheMessageItsAddressBookSignsToGroundTheChainOfTrust() {
        final var network = TestNetwork.withWeights(1L, 2L, 3L);
        final var hintsVerificationKey = randomBytes(VK_LENGTH);

        final var message = subject.computeWrapsMessage(network.addressBook(), hintsVerificationKey);
        final var history = new History(
                Bytes.wrap(subject.hashAddressBook(network.addressBook())), Bytes.wrap(hintsVerificationKey));

        assertEquals(64, message.length);
        assertEquals(Bytes.wrap(message), subject.ledgerIdOf(history));
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
        // Keep the genesis address book identical, changing only the genesis hinTS-key hash in the ledger id.
        // Compute a valid network id so rejection cannot be attributed to malformed field encodings.
        final var otherGenesisVerificationKey = genesisVerificationKey.clone();
        otherGenesisVerificationKey[0] ^= 1;
        final var otherGenesisLedgerId =
                subject.computeWrapsMessage(genesis.addressBook(), otherGenesisVerificationKey);
        assertArrayEquals(Arrays.copyOf(ledgerId, 32), Arrays.copyOf(otherGenesisLedgerId, 32));
        assertFalse(Arrays.equals(ledgerId, otherGenesisLedgerId));
        assertFalse(
                subject.verifyCompressedProof(genesisProof.compressed(), otherGenesisLedgerId, genesisVerificationKey));
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
                ledgerId,
                genesisProof.uncompressed(),
                genesis.addressBook(),
                target.addressBook(),
                targetVerificationKey,
                signature,
                Set.of(0L, 2L));
        assertNotNull(incrementalProof);
        assertTrue(subject.verifyCompressedProof(incrementalProof.compressed(), ledgerId, targetVerificationKey));
        // Rotations remain bound to the genesis hinTS key, even with the correct current hinTS key.
        assertFalse(subject.verifyCompressedProof(
                incrementalProof.compressed(), otherGenesisLedgerId, targetVerificationKey));
        assertFalse(subject.verifyCompressedProof(incrementalProof.compressed(), ledgerId, genesisVerificationKey));
        // But not in the chain of trust of any other ledger id
        final var otherLedgerId = subject.computeWrapsMessage(target.addressBook(), targetVerificationKey);
        assertFalse(subject.verifyCompressedProof(incrementalProof.compressed(), otherLedgerId, targetVerificationKey));
    }

    @Test
    void genesisProofRejectsShiftedSubsetSignerPositions() {
        final var network = TestNetwork.withWeights(10L, 10L, 10L, 10L, 10L, 10L, 10L);
        final var canonicalBook = network.bookWithoutKeys(Set.of(3L, 4L));
        final var participantBook = network.subBook(0L, 1L, 2L, 5L, 6L);
        final var verificationKey = randomBytes(VK_LENGTH);
        final var message = subject.computeWrapsMessage(canonicalBook, verificationKey);
        final var signers = Set.of(0L, 1L, 5L);

        // Node 5 occupies subset position 3, which selects the sentinel for node 3 in the canonical book.
        final var signature = network.sign(message, signers, participantBook);

        assertFalse(verifiesAgainst(message, signature, canonicalBook));
        assertNull(subject.constructGenesisWrapsProof(
                subject.hashAddressBook(canonicalBook), verificationKey, signature, signers, canonicalBook));
    }

    @Test
    void genesisProofCanSurviveSubsetMismatchForLeadingSigners() {
        final var network = TestNetwork.withWeights(10L, 10L, 10L, 10L, 10L, 10L, 10L);
        final var canonicalBook = network.bookWithoutKeys(Set.of(3L, 4L));
        final var participantBook = network.subBook(0L, 1L, 2L, 5L, 6L);
        final var verificationKey = randomBytes(VK_LENGTH);
        final var message = subject.computeWrapsMessage(canonicalBook, verificationKey);
        final var signers = Set.of(0L, 1L, 2L);

        // Every selected position is unchanged, and three signers exceed half the five non-sentinel seats.
        final var signature = network.sign(message, signers, participantBook);

        assertTrue(verifiesAgainst(message, signature, canonicalBook));
        final var proof = subject.constructGenesisWrapsProof(
                subject.hashAddressBook(canonicalBook), verificationKey, signature, signers, canonicalBook);
        assertNotNull(proof);
        assertTrue(subject.verifyCompressedProof(proof.compressed(), message, verificationKey));
    }

    @Test
    void canonicalBookNeedsFourSignersWhenAllSevenSchnorrKeysArePresent() {
        final var network = TestNetwork.withWeights(10L, 10L, 10L, 10L, 10L, 10L, 10L);
        final var canonicalBook = network.addressBook();
        final var participantBook = network.subBook(0L, 1L, 2L, 5L, 6L);
        final var verificationKey = randomBytes(VK_LENGTH);
        final var message = subject.computeWrapsMessage(canonicalBook, verificationKey);
        final var earlySigners = Set.of(0L, 1L, 2L);

        // These positions agree in both books, isolating the native quorum from the index mismatch.
        final var insufficientSignature = network.sign(message, earlySigners, participantBook);
        assertFalse(verifiesAgainst(message, insufficientSignature, canonicalBook));
        assertNull(subject.constructGenesisWrapsProof(
                subject.hashAddressBook(canonicalBook),
                verificationKey,
                insufficientSignature,
                earlySigners,
                canonicalBook));

        // A fourth hinTS-eligible participant can complete a proof once R1 waits for the canonical quorum.
        final var sufficientSigners = Set.of(0L, 1L, 2L, 5L);
        final var signature = network.sign(message, sufficientSigners, canonicalBook);
        final var proof = subject.constructGenesisWrapsProof(
                subject.hashAddressBook(canonicalBook), verificationKey, signature, sufficientSigners, canonicalBook);
        assertNotNull(proof);
        assertTrue(subject.verifyCompressedProof(proof.compressed(), message, verificationKey));
    }

    @Test
    void sentinelListedWeightsAffectCommitmentButNotSigningQuorum() {
        final var network = TestNetwork.withWeights(1L, 1L, 1L, 1_000L, 1_000L, 1L, 1L);
        final var canonicalBook = network.bookWithoutKeys(Set.of(3L, 4L));
        final var zeroedWeights = canonicalBook.weights().clone();
        zeroedWeights[3] = 0;
        zeroedWeights[4] = 0;
        final var zeroedSentinelBook =
                new HistoryLibrary.AddressBook(zeroedWeights, canonicalBook.publicKeys(), canonicalBook.nodeIds());

        assertFalse(Arrays.equals(subject.hashAddressBook(canonicalBook), subject.hashAddressBook(zeroedSentinelBook)));

        final var verificationKey = randomBytes(VK_LENGTH);
        final var message = subject.computeWrapsMessage(canonicalBook, verificationKey);
        final var signers = Set.of(0L, 1L, 2L);
        final var signature = network.sign(message, signers, canonicalBook);
        assertTrue(verifiesAgainst(message, signature, canonicalBook));

        // The native protocol rejects selecting a sentinel seat, regardless of its listed weight.
        final var entropy = randomBytes(32);
        final var privateKey = network.keys().getFirst().privateKey();
        final var r1 = subject.runWrapsPhaseR1(entropy, message, privateKey);
        final var otherR1 = subject.runWrapsPhaseR1(randomBytes(32), message, privateKey);
        assertNotNull(r1);
        assertNotNull(otherR1);
        assertNull(subject.runWrapsPhaseR2(
                entropy, message, new byte[][] {r1, otherR1}, privateKey, canonicalBook, Set.of(0L, 3L)));
    }

    @Test
    void identityPointWithDifferentProofOfKnowledgeKeepsNativeQuorumAndCommitment() {
        final var network = TestNetwork.withWeights(1L, 1L, 1L, 1_000L);
        final var canonicalBook = network.bookWithoutKeys(Set.of(3L));
        final var differentAttestation = HistoryLibrary.MISSING_SCHNORR_KEY.toByteArray();
        differentAttestation[differentAttestation.length - 1] = 1;
        final var historicalKeys = canonicalBook.publicKeys().clone();
        historicalKeys[3] = differentAttestation;
        final var historicalBook =
                new HistoryLibrary.AddressBook(canonicalBook.weights(), historicalKeys, canonicalBook.nodeIds());

        assertTrue(HistoryLibrary.isIdentityKey(differentAttestation));
        assertTrue(HistoryLibrary.isIdentityKey(Bytes.wrap(differentAttestation)));
        assertTrue(HistoryLibrary.isIdentityKey(HistoryLibrary.MISSING_SCHNORR_KEY));
        assertFalse(HistoryLibrary.isIdentityKey(network.keys().getFirst().publicKey()));
        assertFalse(HistoryLibrary.isIdentityKey(new byte[] {1}));
        assertFalse(HistoryLibrary.isIdentityKey(Bytes.EMPTY));
        // Only the public point, not its proof of knowledge, is in the native commitment.
        assertArrayEquals(subject.hashAddressBook(canonicalBook), subject.hashAddressBook(historicalBook));

        final var sourceAuthority = new TreeMap<>(Map.of(0L, 1L, 1L, 1L, 2L, 1L, 3L, 1_000L));
        final var quorum = new WrapsSigningQuorum(historicalBook, sourceAuthority, false);
        final var signers = Set.of(0L, 1L);
        assertTrue(quorum.isSatisfiedBy(signers));
        assertFalse(quorum.isEligibleSigner(3L));
        assertTrue(quorum.canCompleteWithout(Set.of(2L, 3L)));
        final var verificationKey = randomBytes(VK_LENGTH);
        final var message = subject.computeWrapsMessage(historicalBook, verificationKey);
        final var signature = network.sign(message, signers, historicalBook);
        assertTrue(verifiesAgainst(message, signature, historicalBook));
        final var proof = subject.constructGenesisWrapsProof(
                subject.hashAddressBook(historicalBook), verificationKey, signature, signers, historicalBook);
        assertNotNull(proof);
        assertTrue(subject.verifyCompressedProof(proof.compressed(), message, verificationKey));
    }

    @Test
    void canonicalSentinelBookSupportsGenesisAndIncrementalProofsWithNonContiguousNodeIds() {
        final var network = TestNetwork.withNodeIds(
                new long[] {2L, 7L, 11L, 19L, 23L, 41L, 80L}, 10L, 10L, 10L, 10L, 10L, 10L, 10L);
        final var canonicalBook = network.bookWithoutKeys(Set.of(19L, 23L));
        final var genesisVerificationKey = randomBytes(VK_LENGTH);
        final var ledgerId = subject.computeWrapsMessage(canonicalBook, genesisVerificationKey);
        final var signers = Set.of(2L, 41L, 80L);
        final var genesisSignature = network.sign(ledgerId, signers, canonicalBook);
        final var genesisProof = subject.constructGenesisWrapsProof(
                subject.hashAddressBook(canonicalBook),
                genesisVerificationKey,
                genesisSignature,
                signers,
                canonicalBook);
        assertNotNull(genesisProof);
        assertTrue(subject.verifyCompressedProof(genesisProof.compressed(), ledgerId, genesisVerificationKey));

        final var target = TestNetwork.withNodeIds(new long[] {2L, 41L, 90L}, 12L, 30L, 40L);
        final var targetVerificationKey = randomBytes(VK_LENGTH);
        final var message = subject.computeWrapsMessage(target.addressBook(), targetVerificationKey);
        final var signature = network.sign(message, signers, canonicalBook);
        final var incrementalProof = subject.constructIncrementalWrapsProof(
                ledgerId,
                genesisProof.uncompressed(),
                canonicalBook,
                target.addressBook(),
                targetVerificationKey,
                signature,
                signers);
        assertNotNull(incrementalProof);
        assertTrue(subject.verifyCompressedProof(incrementalProof.compressed(), ledgerId, targetVerificationKey));
    }

    @Test
    void incrementalProofRejectsTrailingOmissionsEvenWhenSignerPositionsAndAggregateRemainValid() {
        final var network = TestNetwork.withWeights(10L, 10L, 10L, 10L, 10L, 10L, 10L);
        final var canonicalBook = network.bookWithoutKeys(Set.of(5L, 6L));
        final var participantBook = network.subBook(0L, 1L, 2L, 3L, 4L);
        final var genesisVerificationKey = randomBytes(VK_LENGTH);
        final var ledgerId = subject.computeWrapsMessage(canonicalBook, genesisVerificationKey);
        final var signers = Set.of(0L, 1L, 2L);
        final var genesisSignature = network.sign(ledgerId, signers, canonicalBook);
        final var genesisProof = subject.constructGenesisWrapsProof(
                subject.hashAddressBook(canonicalBook),
                genesisVerificationKey,
                genesisSignature,
                signers,
                canonicalBook);
        assertNotNull(genesisProof);
        assertTrue(subject.verifyCompressedProof(genesisProof.compressed(), ledgerId, genesisVerificationKey));

        final var target = TestNetwork.withWeights(12L, 30L, 40L);
        final var targetVerificationKey = randomBytes(VK_LENGTH);
        final var message = subject.computeWrapsMessage(target.addressBook(), targetVerificationKey);
        final var signature = network.sign(message, signers, participantBook);

        // Neither signer positions nor the effective native quorum changed, but the full commitment did.
        assertTrue(verifiesAgainst(message, signature, canonicalBook));
        assertFalse(Arrays.equals(subject.hashAddressBook(canonicalBook), subject.hashAddressBook(participantBook)));
        assertNull(subject.constructIncrementalWrapsProof(
                ledgerId,
                genesisProof.uncompressed(),
                participantBook,
                target.addressBook(),
                targetVerificationKey,
                signature,
                signers));

        final var incrementalProof = subject.constructIncrementalWrapsProof(
                ledgerId,
                genesisProof.uncompressed(),
                canonicalBook,
                target.addressBook(),
                targetVerificationKey,
                signature,
                signers);
        assertNotNull(incrementalProof);
        assertTrue(subject.verifyCompressedProof(incrementalProof.compressed(), ledgerId, targetVerificationKey));
    }

    @Test
    void nativeRejectsSelectingAnIdentitySeatWhateverItsProofOfKnowledge() {
        final var network = TestNetwork.withWeights(1L, 1L, 1L, 1L);
        final var identityWithOtherAttestation = HistoryLibrary.MISSING_SCHNORR_KEY.toByteArray();
        identityWithOtherAttestation[identityWithOtherAttestation.length - 1] = 1;
        final var publicKeys = network.addressBook().publicKeys().clone();
        publicKeys[3] = identityWithOtherAttestation;
        final var book = new HistoryLibrary.AddressBook(
                network.addressBook().weights(),
                publicKeys,
                network.addressBook().nodeIds());
        final var message = subject.computeWrapsMessage(book, randomBytes(VK_LENGTH));
        final var key0 = network.keys().get(0).privateKey();
        final var entropy = randomBytes(32);
        final var r1 = subject.runWrapsPhaseR1(entropy, message, key0);
        final var otherR1 = subject.runWrapsPhaseR1(
                randomBytes(32), message, network.keys().get(1).privateKey());
        assertNotNull(r1);
        assertNotNull(otherR1);
        final var r1Messages = new byte[][] {r1, otherR1};

        // A real second signer is accepted...
        assertNotNull(subject.runWrapsPhaseR2(entropy, message, r1Messages, key0, book, Set.of(0L, 1L)));
        // ...but selecting the identity seat is refused by native itself, since the bridge only screens out the
        // sentinel's exact bytes
        assertNull(subject.runWrapsPhaseR2(entropy, message, r1Messages, key0, book, Set.of(0L, 3L)));
    }

    @Test
    void nativeMajorityIsStrictAtExactlyHalfOfAnEvenTotal() {
        final var network = TestNetwork.withWeights(10L, 10L, 10L, 10L);
        final var book = network.addressBook();
        final var message = subject.computeWrapsMessage(book, randomBytes(VK_LENGTH));

        // Two of four equal seats sign; their positions are the same in the three-seat book that lets them sign
        final var halfSignature = network.sign(message, Set.of(0L, 1L), network.subBook(0L, 1L, 2L));
        assertFalse(verifiesAgainst(message, halfSignature, book));
        assertTrue(verifiesAgainst(message, network.sign(message, Set.of(0L, 1L, 2L)), book));
        // The Java quorum draws the same line
        final var quorum = new WrapsSigningQuorum(book, Map.of(0L, 10L, 1L, 10L, 2L, 10L, 3L, 10L), false);
        assertFalse(quorum.isSatisfiedBy(Set.of(0L, 1L)));
        assertTrue(quorum.isSatisfiedBy(Set.of(0L, 1L, 2L)));
    }

    private boolean verifiesAgainst(
            final byte[] message, final byte[] signature, final HistoryLibrary.AddressBook addressBook) {
        return subject.verifyAggregateSignature(
                message, addressBook.nodeIds(), addressBook.publicKeys(), addressBook.weights(), signature);
    }

    /**
     * A network of nodes with Schnorr keys and weights at the corresponding address-book positions.
     */
    private record TestNetwork(List<SchnorrKeys> keys, HistoryLibrary.AddressBook addressBook) {
        static TestNetwork withWeights(final long... weights) {
            return withNodeIds(IntStream.range(0, weights.length).asLongStream().toArray(), weights);
        }

        static TestNetwork withNodeIds(final long[] nodeIds, final long... weights) {
            assertEquals(weights.length, nodeIds.length);
            final var library = new HistoryLibraryImpl();
            final var keys = Arrays.stream(weights)
                    .mapToObj(ignore -> library.newSchnorrKeyPair())
                    .toList();
            final var addressBook = new HistoryLibrary.AddressBook(
                    weights, keys.stream().map(SchnorrKeys::publicKey).toArray(byte[][]::new), nodeIds.clone());
            return new TestNetwork(keys, addressBook);
        }

        HistoryLibrary.AddressBook bookWithoutKeys(final Set<Long> absentNodeIds) {
            final var publicKeys = addressBook.publicKeys().clone();
            absentNodeIds.forEach(
                    nodeId -> publicKeys[indexOf(nodeId)] = HistoryLibrary.MISSING_SCHNORR_KEY.toByteArray());
            return new HistoryLibrary.AddressBook(addressBook.weights(), publicKeys, addressBook.nodeIds());
        }

        HistoryLibrary.AddressBook subBook(final long... nodeIds) {
            return new HistoryLibrary.AddressBook(
                    Arrays.stream(nodeIds)
                            .map(nodeId -> addressBook.weights()[indexOf(nodeId)])
                            .toArray(),
                    Arrays.stream(nodeIds)
                            .mapToObj(nodeId -> addressBook.publicKeys()[indexOf(nodeId)])
                            .toArray(byte[][]::new),
                    nodeIds.clone());
        }

        /**
         * Runs the WRAPS signing protocol among the given signers to produce an aggregate signature on the message.
         */
        byte[] sign(final byte[] message, final Set<Long> signers) {
            return sign(message, signers, addressBook);
        }

        byte[] sign(final byte[] message, final Set<Long> signers, final HistoryLibrary.AddressBook signingBook) {
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
                            signingBook,
                            signers))
                    .toArray(byte[][]::new);
            final var r3Messages = IntStream.range(0, sortedSigners.size())
                    .mapToObj(i -> library.runWrapsPhaseR3(
                            entropies.get(i),
                            message,
                            r1Messages,
                            r2Messages,
                            privateKeyOf(sortedSigners, i),
                            signingBook,
                            signers))
                    .toArray(byte[][]::new);
            final var signature =
                    library.runAggregationPhase(message, r1Messages, r2Messages, r3Messages, signingBook, signers);
            assertNotNull(signature);
            assertTrue(library.verifyAggregateSignature(
                    message, signingBook.nodeIds(), signingBook.publicKeys(), signingBook.weights(), signature));
            return signature;
        }

        private byte[] privateKeyOf(final List<Long> sortedSigners, final int i) {
            return keys.get(indexOf(sortedSigners.get(i))).privateKey();
        }

        private int indexOf(final long nodeId) {
            final int index = Arrays.binarySearch(addressBook.nodeIds(), nodeId);
            assertTrue(index >= 0, "Unknown node id " + nodeId);
            return index;
        }
    }

    private static final int VK_LENGTH = 1096;
    private static final SplittableRandom RANDOM = new SplittableRandom(1_234_567L);

    private static HistoryLibrary.AddressBook addressBookWith(final byte[]... publicKeys) {
        final var weights = new long[publicKeys.length];
        Arrays.fill(weights, 1L);
        return new HistoryLibrary.AddressBook(
                weights,
                publicKeys,
                IntStream.rangeClosed(1, publicKeys.length).asLongStream().toArray());
    }

    private static byte[] randomBytes(final int n) {
        final var bytes = new byte[n];
        RANDOM.nextBytes(bytes);
        return bytes;
    }
}
