// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.proof;

import static com.swirlds.common.test.fixtures.crypto.CryptoRandomUtils.randomHash;
import static com.swirlds.common.test.fixtures.crypto.CryptoRandomUtils.randomSignature;
import static com.swirlds.common.test.fixtures.merkle.util.MerkleTestUtils.buildLessSimpleTree;
import static com.swirlds.common.test.fixtures.merkle.util.MerkleTestUtils.buildLessSimpleTreeExtended;
import static com.swirlds.common.test.fixtures.merkle.util.MerkleTestUtils.buildSizeOneTree;
import static com.swirlds.common.utility.Threshold.MAJORITY;
import static com.swirlds.common.utility.Threshold.STRONG_MINORITY;
import static com.swirlds.common.utility.Threshold.SUPER_MAJORITY;
import static org.hiero.base.utility.test.fixtures.RandomUtils.getRandomPrintSeed;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.swirlds.common.crypto.Cryptography;
import com.swirlds.common.crypto.CryptographyProvider;
import com.swirlds.common.crypto.Signature;
import com.swirlds.common.merkle.MerkleLeaf;
import com.swirlds.common.merkle.MerkleNode;
import com.swirlds.common.merkle.interfaces.MerkleType;
import com.swirlds.common.merkle.route.MerkleRouteFactory;
import com.swirlds.common.test.fixtures.merkle.TestMerkleCryptoFactory;
import com.swirlds.common.utility.Threshold;
import com.swirlds.platform.system.address.Address;
import com.swirlds.platform.system.address.AddressBook;
import com.swirlds.platform.test.fixtures.addressbook.RandomAddressBookBuilder;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;
import org.hiero.base.constructable.ConstructableRegistry;
import org.hiero.base.constructable.ConstructableRegistryException;
import org.hiero.base.io.streams.SerializableDataInputStream;
import org.hiero.base.io.streams.SerializableDataOutputStream;
import org.hiero.consensus.model.crypto.Hash;
import org.hiero.consensus.model.node.NodeId;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@DisplayName("StateProof Tests")
class StateProofTests {
    private static final Cryptography CRYPTOGRAPHY = CryptographyProvider.getInstance();

    @BeforeAll
    static void beforeAll() throws ConstructableRegistryException {
        ConstructableRegistry.getInstance().registerConstructables("com.swirlds");
    }

    /**
     * Generate enough signatures to meet a threshold.
     */
    @NonNull
    private Map<NodeId, Signature> generateThresholdOfSignatures(
            @NonNull final Random random,
            @NonNull final AddressBook addressBook,
            @NonNull final FakeSignatureBuilder signatureBuilder,
            @NonNull final Hash hash,
            @NonNull final Threshold threshold) {

        final List<NodeId> nodes = new ArrayList<>(addressBook.getSize());
        for (final Address address : addressBook) {
            nodes.add(address.getNodeId());
        }
        Collections.shuffle(nodes, random);

        final Map<NodeId, Signature> signatures = new HashMap<>();

        long weight = 0;
        for (final NodeId nodeId : nodes) {
            final Address address = addressBook.getAddress(nodeId);
            weight += address.getWeight();
            signatures.put(nodeId, signatureBuilder.fakeSign(hash.copyToByteArray(), address.getSigPublicKey()));

            if (threshold.isSatisfiedBy(weight, addressBook.getTotalWeight())) {
                break;
            }
        }

        return signatures;
    }

    /**
     * Serialize then deserialize a state proof.
     *
     * @param stateProof the state proof to serialize and deserialize
     * @return the deserialized state proof
     */
    @NonNull
    private StateProof serializeAndDeserialize(@NonNull final StateProof stateProof) throws IOException {
        final ByteArrayOutputStream byteOut = new ByteArrayOutputStream();
        final SerializableDataOutputStream out = new SerializableDataOutputStream(byteOut);

        out.writeSerializable(stateProof, true);

        final byte[] bytes = byteOut.toByteArray();
        final SerializableDataInputStream in = new SerializableDataInputStream(new ByteArrayInputStream(bytes));

        final StateProof deserialized = in.readSerializable();
        assertNotSame(stateProof, deserialized);
        return deserialized;
    }

    /**
     * Verify extremely simple scenario, good for debugging bugs with the core logic.
     */
    @Test
    @DisplayName("Basic Behavior Test")
    void basicBehaviorTest() throws IOException {
        final Random random = getRandomPrintSeed();

        final FakeSignatureBuilder signatureBuilder = new FakeSignatureBuilder(random);

        final MerkleNode root = buildLessSimpleTree();
        TestMerkleCryptoFactory.getInstance().digestTreeSync(root);

        final AddressBook addressBook =
                RandomAddressBookBuilder.create(random).withSize(10).build();

        final MerkleLeaf nodeD =
                root.getNodeAtRoute(MerkleRouteFactory.buildRoute(2, 0)).asLeaf();

        final Map<NodeId, Signature> signatures =
                generateThresholdOfSignatures(random, addressBook, signatureBuilder, root.getHash(), SUPER_MAJORITY);

        final StateProof stateProof = new StateProof(CRYPTOGRAPHY, root, signatures, List.of(nodeD));

        assertEquals(1, stateProof.getPayloads().size());
        assertSame(nodeD, stateProof.getPayloads().get(0));
        assertTrue(stateProof.isValid(addressBook, SUPER_MAJORITY, signatureBuilder));
        // Checking a second time shouldn't cause problems
        assertTrue(stateProof.isValid(addressBook, SUPER_MAJORITY, signatureBuilder));

        final StateProof deserialized = serializeAndDeserialize(stateProof);

        assertEquals(1, stateProof.getPayloads().size());
        assertNotSame(nodeD, deserialized.getPayloads().get(0));
        assertEquals(nodeD, deserialized.getPayloads().get(0));
        // Checking a second time shouldn't cause problems
        assertTrue(deserialized.isValid(addressBook, SUPER_MAJORITY, signatureBuilder));
    }

    @Test
    @DisplayName("Leaf Tree Test")
    void leafTreeTest() throws IOException {
        final Random random = getRandomPrintSeed();

        final FakeSignatureBuilder signatureBuilder = new FakeSignatureBuilder(random);

        final MerkleNode root = buildSizeOneTree();
        TestMerkleCryptoFactory.getInstance().digestTreeSync(root);

        final AddressBook addressBook =
                RandomAddressBookBuilder.create(random).withSize(10).build();

        final Map<NodeId, Signature> signatures =
                generateThresholdOfSignatures(random, addressBook, signatureBuilder, root.getHash(), SUPER_MAJORITY);

        final StateProof stateProof = new StateProof(CRYPTOGRAPHY, root, signatures, List.of(root.asLeaf()));

        assertEquals(1, stateProof.getPayloads().size());
        assertSame(root, stateProof.getPayloads().get(0));
        assertTrue(stateProof.isValid(addressBook, SUPER_MAJORITY, signatureBuilder));
        // Checking a second time shouldn't cause problems
        assertTrue(stateProof.isValid(addressBook, SUPER_MAJORITY, signatureBuilder));

        final StateProof deserialized = serializeAndDeserialize(stateProof);

        assertEquals(1, stateProof.getPayloads().size());
        assertNotSame(root, deserialized.getPayloads().get(0));
        assertEquals(root, deserialized.getPayloads().get(0));
        // Checking a second time shouldn't cause problems
        assertTrue(deserialized.isValid(addressBook, SUPER_MAJORITY, signatureBuilder));
    }

    private void testWithNPayloads(
            @NonNull final Random random,
            @NonNull final MerkleNode root,
            @NonNull final Cryptography cryptography,
            @NonNull final List<MerkleLeaf> payloads,
            @NonNull final Threshold threshold)
            throws IOException {

        final FakeSignatureBuilder signatureBuilder = new FakeSignatureBuilder(random);

        TestMerkleCryptoFactory.getInstance().digestTreeSync(root);

        final AddressBook addressBook = RandomAddressBookBuilder.create(random)
                .withSize(random.nextInt(1, 10))
                .build();

        final Map<NodeId, Signature> signatures =
                generateThresholdOfSignatures(random, addressBook, signatureBuilder, root.getHash(), threshold);

        final StateProof stateProof = new StateProof(cryptography, root, signatures, payloads);

        // Make sure we have all the payloads
        assertEquals(payloads.size(), stateProof.getPayloads().size());
        for (final MerkleLeaf payload : payloads) {
            boolean payloadFound = false;
            for (final MerkleLeaf stateProofPayload : stateProof.getPayloads()) {
                if (payload == stateProofPayload) {
                    payloadFound = true;
                    break;
                }
            }
            assertTrue(payloadFound);
        }

        assertTrue(stateProof.isValid(addressBook, threshold, signatureBuilder));
        // Checking a second time shouldn't cause problems
        assertTrue(stateProof.isValid(addressBook, threshold, signatureBuilder));

        final StateProof deserialized = serializeAndDeserialize(stateProof);

        // We should have all the payloads, but they should be new instances since they were deserialized
        assertEquals(payloads.size(), deserialized.getPayloads().size());
        for (final MerkleLeaf payload : payloads) {
            boolean payloadFound = false;
            for (final MerkleLeaf stateProofPayload : deserialized.getPayloads()) {
                if (payload.equals(stateProofPayload)) {
                    assertNotSame(payload, stateProofPayload);
                    payloadFound = true;
                    break;
                }
            }
            assertTrue(payloadFound);
        }

        assertTrue(deserialized.isValid(addressBook, threshold, signatureBuilder));
        // Checking a second time shouldn't cause problems
        assertTrue(deserialized.isValid(addressBook, threshold, signatureBuilder));
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 2})
    @DisplayName("Multi Payload Test")
    void multiPayloadTest(final int thresholdOrdinal) throws IOException {
        final Random random = getRandomPrintSeed();

        final Threshold threshold = Threshold.values()[thresholdOrdinal];

        final MerkleNode root = buildLessSimpleTreeExtended();
        TestMerkleCryptoFactory.getInstance().digestTreeSync(root);
        final List<MerkleLeaf> leafNodes = new ArrayList<>();
        root.treeIterator().setFilter(MerkleType::isLeaf).forEachRemaining(node -> leafNodes.add(node.asLeaf()));

        for (int payloadCount = 1; payloadCount < leafNodes.size(); payloadCount++) {
            Collections.shuffle(leafNodes, random);
            final List<MerkleLeaf> payloads = leafNodes.subList(0, payloadCount);
            testWithNPayloads(random, root, CRYPTOGRAPHY, payloads, threshold);
        }
    }

    private void testWithNInvalidPayloads(
            @NonNull final Random random,
            @NonNull final MerkleNode root,
            @NonNull final Cryptography cryptography,
            @NonNull final List<MerkleLeaf> payloads)
            throws IOException {

        final FakeSignatureBuilder signatureBuilder = new FakeSignatureBuilder(random);

        // Clear out the tree's hashes
        root.treeIterator().forEachRemaining(node -> node.setHash(null));

        // Incorrectly set some of the payload hashes
        final int invalidPayloadCount;
        if (payloads.size() == 1) {
            invalidPayloadCount = 1;
        } else {
            invalidPayloadCount = random.nextInt(1, payloads.size());
        }
        for (int invalidPayloadIndex = 0; invalidPayloadIndex < invalidPayloadCount; invalidPayloadIndex++) {
            payloads.get(invalidPayloadIndex).setHash(randomHash(random));
        }

        // Now, rehash the tree using the incorrect leaf hashes.
        TestMerkleCryptoFactory.getInstance().digestTreeSync(root);

        final AddressBook addressBook = RandomAddressBookBuilder.create(random)
                .withSize(random.nextInt(1, 10))
                .build();

        final Map<NodeId, Signature> signatures =
                generateThresholdOfSignatures(random, addressBook, signatureBuilder, root.getHash(), STRONG_MINORITY);

        final StateProof stateProof = new StateProof(cryptography, root, signatures, payloads);

        // Make sure we have all the payloads
        assertEquals(payloads.size(), stateProof.getPayloads().size());
        for (final MerkleLeaf payload : payloads) {
            boolean payloadFound = false;
            for (final MerkleLeaf stateProofPayload : stateProof.getPayloads()) {
                if (payload == stateProofPayload) {
                    payloadFound = true;
                    break;
                }
            }
            assertTrue(payloadFound);
        }

        // serialize and deserialize to make sure the validator is not using the incorrect hashes
        final StateProof deserialized = serializeAndDeserialize(stateProof);

        assertFalse(deserialized.isValid(addressBook, STRONG_MINORITY, signatureBuilder));
        // Checking a second time shouldn't cause problems
        assertFalse(deserialized.isValid(addressBook, STRONG_MINORITY, signatureBuilder));
    }

    /**
     * Some of the payloads will not have the correct hash.
     */
    @Test
    @DisplayName("Invalid Payload Test")
    void invalidPayloadTest() throws IOException {
        final Random random = getRandomPrintSeed();

        final MerkleNode root = buildLessSimpleTreeExtended();
        final List<MerkleLeaf> leafNodes = new ArrayList<>();
        root.treeIterator().setFilter(MerkleType::isLeaf).forEachRemaining(node -> leafNodes.add(node.asLeaf()));

        for (int payloadCount = 1; payloadCount < leafNodes.size(); payloadCount++) {
            Collections.shuffle(leafNodes, random);
            final List<MerkleLeaf> payloads = leafNodes.subList(0, payloadCount);
            testWithNInvalidPayloads(random, root, CRYPTOGRAPHY, payloads);
        }
    }

    @Test
    @DisplayName("Deterministic Serialization Test")
    void deterministicSerializationTest() throws IOException {
        final Random random = getRandomPrintSeed();

        final MerkleNode root = buildLessSimpleTreeExtended();
        TestMerkleCryptoFactory.getInstance().digestTreeSync(root);
        final List<MerkleLeaf> leafNodes = new ArrayList<>();
        root.treeIterator().setFilter(MerkleType::isLeaf).forEachRemaining(node -> leafNodes.add(node.asLeaf()));
        Collections.shuffle(leafNodes, random);

        final int payloadCount = random.nextInt(1, leafNodes.size());
        final int signatureCount = random.nextInt(10, 20);

        final List<MerkleLeaf> payloads = leafNodes.subList(0, payloadCount);
        final Map<NodeId, Signature> signatures = new HashMap<>();
        for (int i = 0; i < signatureCount; i++) {
            final NodeId nodeId = NodeId.of(random.nextLong(1, 1000));
            final Signature signature = randomSignature(random);
            signatures.put(nodeId, signature);
        }

        final List<MerkleLeaf> payloadsAlternateOrder = new ArrayList<>(payloads);
        Collections.shuffle(payloadsAlternateOrder, random);

        final Map<NodeId, Signature> signaturesAlternateOrder = new TreeMap<>(Comparator.reverseOrder());
        signaturesAlternateOrder.putAll(signatures);

        final StateProof stateProofA = new StateProof(CRYPTOGRAPHY, root, signatures, payloads);
        final StateProof stateProofB =
                new StateProof(CRYPTOGRAPHY, root, signaturesAlternateOrder, payloadsAlternateOrder);

        final ByteArrayOutputStream byteOutA = new ByteArrayOutputStream();
        final ByteArrayOutputStream byteOutB = new ByteArrayOutputStream();

        final SerializableDataOutputStream outA = new SerializableDataOutputStream(byteOutA);
        final SerializableDataOutputStream outB = new SerializableDataOutputStream(byteOutB);

        outA.writeSerializable(stateProofA, true);
        outB.writeSerializable(stateProofB, true);

        final byte[] bytesA = byteOutA.toByteArray();
        final byte[] bytesB = byteOutB.toByteArray();

        assertArrayEquals(bytesA, bytesB);
    }

    @Test
    @DisplayName("Round Trip Serialization Test")
    void roundTripSerializationTest() throws IOException {
        final Random random = getRandomPrintSeed();

        final MerkleNode root = buildLessSimpleTreeExtended();
        TestMerkleCryptoFactory.getInstance().digestTreeSync(root);
        final List<MerkleLeaf> leafNodes = new ArrayList<>();
        root.treeIterator().setFilter(MerkleType::isLeaf).forEachRemaining(node -> leafNodes.add(node.asLeaf()));
        Collections.shuffle(leafNodes, random);

        final int payloadCount = random.nextInt(1, leafNodes.size());
        final int signatureCount = random.nextInt(10, 20);

        final List<MerkleLeaf> payloads = leafNodes.subList(0, payloadCount);
        final Map<NodeId, Signature> signatures = new HashMap<>();
        for (int i = 0; i < signatureCount; i++) {
            final NodeId nodeId = NodeId.of(random.nextLong(1, 1000));
            final Signature signature = randomSignature(random);
            signatures.put(nodeId, signature);
        }

        final StateProof stateProofA = new StateProof(CRYPTOGRAPHY, root, signatures, payloads);
        final ByteArrayOutputStream byteOutA = new ByteArrayOutputStream();
        final SerializableDataOutputStream outA = new SerializableDataOutputStream(byteOutA);
        outA.writeSerializable(stateProofA, true);
        final byte[] bytesA = byteOutA.toByteArray();
        final SerializableDataInputStream in = new SerializableDataInputStream(new ByteArrayInputStream(bytesA));

        final StateProof stateProofB = in.readSerializable();
        final ByteArrayOutputStream byteOutB = new ByteArrayOutputStream();
        final SerializableDataOutputStream outB = new SerializableDataOutputStream(byteOutB);
        outB.writeSerializable(stateProofB, true);
        final byte[] bytesB = byteOutB.toByteArray();

        assertArrayEquals(bytesA, bytesB);
    }

    @Test
    @DisplayName("Zero Weight Signature Test")
    void zeroWeightSignatureTest() {
        final Random random = getRandomPrintSeed();

        final FakeSignatureBuilder signatureBuilder = new FakeSignatureBuilder(random);

        final MerkleNode root = buildLessSimpleTree();
        TestMerkleCryptoFactory.getInstance().digestTreeSync(root);

        // For this test, there will be 9 total weight,
        // with the node at index 9 having 0 stake, and all others having a weight of 1.
        final AddressBook addressBook =
                RandomAddressBookBuilder.create(random).withSize(10).build();
        for (int index = 0; index < 10; index++) {
            final NodeId nodeId = addressBook.getNodeId(index);
            if (index == 9) {
                addressBook.add(addressBook.getAddress(nodeId).copySetWeight(0));
            } else {
                addressBook.add(addressBook.getAddress(nodeId).copySetWeight(1));
            }
        }

        final MerkleLeaf nodeD =
                root.getNodeAtRoute(MerkleRouteFactory.buildRoute(2, 0)).asLeaf();

        // Add 6 signatures. Not quite enough to reach super majority.
        final Map<NodeId, Signature> signatures = new HashMap<>();
        for (int index = 0; index < 6; index++) {
            final NodeId nodeId = addressBook.getNodeId(index);
            final Address address = addressBook.getAddress(nodeId);
            final Signature signature =
                    signatureBuilder.fakeSign(root.getHash().copyToByteArray(), address.getSigPublicKey());
            signatures.put(nodeId, signature);
        }

        final StateProof stateProofA = new StateProof(CRYPTOGRAPHY, root, signatures, List.of(nodeD));

        // We don't quite have the right threshold
        assertFalse(stateProofA.isValid(addressBook, SUPER_MAJORITY, signatureBuilder));

        // Adding the zero weight signature should not change the result.
        final NodeId nodeId = addressBook.getNodeId(9);
        final Address address = addressBook.getAddress(nodeId);
        final Signature signature =
                signatureBuilder.fakeSign(root.getHash().copyToByteArray(), address.getSigPublicKey());
        signatures.put(nodeId, signature);

        final StateProof stateProofB = new StateProof(CRYPTOGRAPHY, root, signatures, List.of(nodeD));
        assertFalse(stateProofB.isValid(addressBook, SUPER_MAJORITY, signatureBuilder));

        // Adding another non-zero weight signature should do the trick.

        final NodeId nodeId2 = addressBook.getNodeId(8);
        final Address address2 = addressBook.getAddress(nodeId2);
        final Signature signature2 =
                signatureBuilder.fakeSign(root.getHash().copyToByteArray(), address2.getSigPublicKey());
        signatures.put(nodeId2, signature2);

        final StateProof stateProofC = new StateProof(CRYPTOGRAPHY, root, signatures, List.of(nodeD));
        assertTrue(stateProofC.isValid(addressBook, SUPER_MAJORITY, signatureBuilder));
    }

    @Test
    @DisplayName("Signature Not In Address Book Test")
    void signatureNotInAddressBookTest() {
        final Random random = getRandomPrintSeed();

        final FakeSignatureBuilder signatureBuilder = new FakeSignatureBuilder(random);

        final MerkleNode root = buildLessSimpleTree();
        TestMerkleCryptoFactory.getInstance().digestTreeSync(root);

        // For this test, there will be 9 total weight, with each node having a weight of 1.
        final AddressBook addressBook =
                RandomAddressBookBuilder.create(random).withSize(9).build();
        for (int index = 0; index < 9; index++) {
            final NodeId nodeId = addressBook.getNodeId(index);
            addressBook.add(addressBook.getAddress(nodeId).copySetWeight(1));
        }

        final MerkleLeaf nodeD =
                root.getNodeAtRoute(MerkleRouteFactory.buildRoute(2, 0)).asLeaf();

        // Add 6 signatures. Not quite enough to reach super majority.
        final Map<NodeId, Signature> signatures = new HashMap<>();
        for (int index = 0; index < 6; index++) {
            final NodeId nodeId = addressBook.getNodeId(index);
            final Address address = addressBook.getAddress(nodeId);
            final Signature signature =
                    signatureBuilder.fakeSign(root.getHash().copyToByteArray(), address.getSigPublicKey());
            signatures.put(nodeId, signature);
        }

        final StateProof stateProofA = new StateProof(CRYPTOGRAPHY, root, signatures, List.of(nodeD));

        // We don't quite have the right threshold
        assertFalse(stateProofA.isValid(addressBook, SUPER_MAJORITY, signatureBuilder));

        // Adding a signature for a node not in the address book should not change the result.
        final NodeId nodeId = NodeId.of(10000000);
        assertFalse(addressBook.contains(nodeId));
        final Signature signature = randomSignature(random);
        signatures.put(nodeId, signature);

        final StateProof stateProofB = new StateProof(CRYPTOGRAPHY, root, signatures, List.of(nodeD));
        assertFalse(stateProofB.isValid(addressBook, SUPER_MAJORITY, signatureBuilder));

        // Adding another real signature should do the trick.

        final NodeId nodeId2 = addressBook.getNodeId(7);
        final Address address2 = addressBook.getAddress(nodeId2);
        final Signature signature2 =
                signatureBuilder.fakeSign(root.getHash().copyToByteArray(), address2.getSigPublicKey());
        signatures.put(nodeId2, signature2);

        final StateProof stateProofC = new StateProof(CRYPTOGRAPHY, root, signatures, List.of(nodeD));
        assertTrue(stateProofC.isValid(addressBook, SUPER_MAJORITY, signatureBuilder));
    }

    private final class DummyNodeId extends NodeId {
        public DummyNodeId(final long id) {
            super(id);
        }

        @Override
        public int hashCode() {
            return super.hashCode() + 1;
        }
    }

    @Test
    @DisplayName("Duplicate Signatures Test")
    void duplicateSignaturesTest() throws IOException {
        final Random random = getRandomPrintSeed();

        final FakeSignatureBuilder signatureBuilder = new FakeSignatureBuilder(random);

        final MerkleNode root = buildLessSimpleTree();
        TestMerkleCryptoFactory.getInstance().digestTreeSync(root);

        // For this test, there will be 9 total weight, with each node having a weight of 1.
        final AddressBook addressBook =
                RandomAddressBookBuilder.create(random).withSize(9).build();
        for (int index = 0; index < 9; index++) {
            final NodeId nodeId = addressBook.getNodeId(index);
            addressBook.add(addressBook.getAddress(nodeId).copySetWeight(1));
        }

        final MerkleLeaf nodeD =
                root.getNodeAtRoute(MerkleRouteFactory.buildRoute(2, 0)).asLeaf();

        // Add 6 signatures. Not quite enough to reach super majority.
        final Map<NodeId, Signature> signatures = new HashMap<>();
        for (int index = 0; index < 6; index++) {
            final NodeId nodeId = addressBook.getNodeId(index);
            final Address address = addressBook.getAddress(nodeId);
            final Signature signature =
                    signatureBuilder.fakeSign(root.getHash().copyToByteArray(), address.getSigPublicKey());
            signatures.put(nodeId, signature);
        }

        final StateProof stateProofA = new StateProof(CRYPTOGRAPHY, root, signatures, List.of(nodeD));

        // We don't quite have the right threshold
        assertFalse(stateProofA.isValid(addressBook, SUPER_MAJORITY, signatureBuilder));

        // Adding duplicate signatures should not change the result.
        // We can force the logic to accept duplicate signatures by playing games with hash codes,
        // which will cause the hash map to see them as non-colliding objects.
        final Map<NodeId, Signature> duplicateSignatures = new HashMap<>();
        for (final Map.Entry<NodeId, Signature> entry : signatures.entrySet()) {
            final NodeId nodeId = new DummyNodeId(entry.getKey().id());
            duplicateSignatures.put(nodeId, entry.getValue());
        }
        signatures.putAll(duplicateSignatures);
        assertEquals(12, signatures.size());

        final StateProof stateProofB = new StateProof(CRYPTOGRAPHY, root, signatures, List.of(nodeD));
        assertFalse(stateProofB.isValid(addressBook, SUPER_MAJORITY, signatureBuilder));

        // Serialize and deserialize. This will get rid of any hash code games. Should still not be valid.
        final StateProof deserialized = serializeAndDeserialize(stateProofB);
        assertFalse(deserialized.isValid(addressBook, SUPER_MAJORITY, signatureBuilder));

        // Adding another non-duplicate signature should do the trick.

        final NodeId nodeId2 = addressBook.getNodeId(7);
        final Address address2 = addressBook.getAddress(nodeId2);
        final Signature signature2 =
                signatureBuilder.fakeSign(root.getHash().copyToByteArray(), address2.getSigPublicKey());
        signatures.put(nodeId2, signature2);

        final StateProof stateProofC = new StateProof(CRYPTOGRAPHY, root, signatures, List.of(nodeD));
        assertTrue(stateProofC.isValid(addressBook, SUPER_MAJORITY, signatureBuilder));
    }

    @Test
    @DisplayName("Real Signatures Wrong IDs Test")
    void realSignaturesWrongIdsTest() throws IOException {
        final Random random = getRandomPrintSeed();

        final FakeSignatureBuilder signatureBuilder = new FakeSignatureBuilder(random);

        final MerkleNode root = buildLessSimpleTree();
        TestMerkleCryptoFactory.getInstance().digestTreeSync(root);

        // For this test, there will be 9 total weight, with each node having a weight of 1.
        final AddressBook addressBook =
                RandomAddressBookBuilder.create(random).withSize(9).build();
        for (int index = 0; index < 9; index++) {
            final NodeId nodeId = addressBook.getNodeId(index);
            addressBook.add(addressBook.getAddress(nodeId).copySetWeight(1));
        }

        final MerkleLeaf nodeD =
                root.getNodeAtRoute(MerkleRouteFactory.buildRoute(2, 0)).asLeaf();

        // Add 7 signatures, enough to make the proof valid if we use the right IDs.
        final Map<NodeId, Signature> signatures = new HashMap<>();
        for (int index = 0; index < 7; index++) {
            final NodeId nodeId = addressBook.getNodeId(index);
            final Address address = addressBook.getAddress(nodeId);
            final Signature signature =
                    signatureBuilder.fakeSign(root.getHash().copyToByteArray(), address.getSigPublicKey());
            signatures.put(nodeId, signature);
        }

        final StateProof stateProofA = new StateProof(CRYPTOGRAPHY, root, signatures, List.of(nodeD));
        assertTrue(stateProofA.isValid(addressBook, SUPER_MAJORITY, signatureBuilder));

        // Using the same signatures with the wrong node IDs should not work.
        final Map<NodeId, Signature> wrongSignatures = new HashMap<>();
        final List<NodeId> nodeIds = new ArrayList<>(signatures.keySet());
        final List<Signature> signatureList = new ArrayList<>(signatures.values());
        for (final Map.Entry<NodeId, Signature> entry : signatures.entrySet()) {
            nodeIds.add(entry.getKey());
            signatureList.add(entry.getValue());
        }
        // Map each signature to the node ID to "the right"
        for (int index = 0; index < nodeIds.size(); index++) {
            final NodeId nodeId = nodeIds.get((index + 1) % nodeIds.size());
            final Signature signature = signatureList.get(index);
            wrongSignatures.put(nodeId, signature);
        }

        final StateProof stateProofB = new StateProof(CRYPTOGRAPHY, root, wrongSignatures, List.of(nodeD));
        assertFalse(stateProofB.isValid(addressBook, SUPER_MAJORITY, signatureBuilder));

        // serialization shouldn't change anything
        final StateProof deserialized = serializeAndDeserialize(stateProofB);
        assertFalse(deserialized.isValid(addressBook, SUPER_MAJORITY, signatureBuilder));
    }

    @Test
    @DisplayName("Threshold Test")
    void thresholdTest() {
        final Random random = getRandomPrintSeed();

        final FakeSignatureBuilder signatureBuilder = new FakeSignatureBuilder(random);

        final MerkleNode root = buildLessSimpleTree();
        TestMerkleCryptoFactory.getInstance().digestTreeSync(root);

        // For this test, there will be 12 total weight, with each node having a weight of 1.
        // 12 is chosen because it is divisible by both 2 and 3.
        final AddressBook addressBook =
                RandomAddressBookBuilder.create(random).withSize(12).build();
        for (int index = 0; index < 12; index++) {
            final NodeId nodeId = addressBook.getNodeId(index);
            addressBook.add(addressBook.getAddress(nodeId).copySetWeight(1));
        }

        final MerkleLeaf nodeD =
                root.getNodeAtRoute(MerkleRouteFactory.buildRoute(2, 0)).asLeaf();

        final Map<NodeId, Signature> signatures = new HashMap<>();
        for (int index = 0; index < 12; index++) {

            final NodeId nextId = addressBook.getNodeId(index);
            final Address address = addressBook.getAddress(nextId);
            final Signature signature =
                    signatureBuilder.fakeSign(root.getHash().copyToByteArray(), address.getSigPublicKey());
            signatures.put(nextId, signature);

            int weight = index + 1;

            final StateProof stateProof = new StateProof(CRYPTOGRAPHY, root, signatures, List.of(nodeD));

            if (weight >= 4) { // >= 1/3
                assertTrue(stateProof.isValid(addressBook, STRONG_MINORITY, signatureBuilder));
            } else {
                assertFalse(stateProof.isValid(addressBook, STRONG_MINORITY, signatureBuilder));
            }

            if (weight > 6) { // > 1/2
                assertTrue(stateProof.isValid(addressBook, MAJORITY, signatureBuilder));
            } else {
                assertFalse(stateProof.isValid(addressBook, MAJORITY, signatureBuilder));
            }

            if (weight > 8) { // > 2/3
                assertTrue(stateProof.isValid(addressBook, SUPER_MAJORITY, signatureBuilder));
            } else {
                assertFalse(stateProof.isValid(addressBook, SUPER_MAJORITY, signatureBuilder));
            }
        }
    }
}
