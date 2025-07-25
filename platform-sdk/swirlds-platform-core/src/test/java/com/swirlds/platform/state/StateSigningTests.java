// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.state;

import static com.swirlds.common.utility.Threshold.MAJORITY;
import static com.swirlds.common.utility.Threshold.SUPER_MAJORITY;
import static com.swirlds.platform.test.fixtures.state.manager.SignatureVerificationTestUtils.buildFakeSignature;
import static org.hiero.base.crypto.test.fixtures.CryptoRandomUtils.randomHash;
import static org.hiero.base.crypto.test.fixtures.CryptoRandomUtils.randomSignature;
import static org.hiero.base.utility.test.fixtures.RandomUtils.getRandomPrintSeed;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.hedera.hapi.node.state.roster.Roster;
import com.hedera.hapi.node.state.roster.RosterEntry;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.common.test.fixtures.WeightGenerators;
import com.swirlds.merkledb.MerkleDb;
import com.swirlds.platform.state.signed.SigSet;
import com.swirlds.platform.state.signed.SignedState;
import com.swirlds.platform.state.signed.SignedStateInvalidException;
import com.swirlds.platform.test.fixtures.addressbook.RandomRosterBuilder;
import com.swirlds.platform.test.fixtures.crypto.PreGeneratedX509Certs;
import com.swirlds.platform.test.fixtures.state.RandomSignedStateGenerator;
import com.swirlds.platform.test.fixtures.state.TestMerkleStateRoot;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.security.PublicKey;
import java.security.cert.CertificateEncodingException;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.stream.IntStream;
import org.hiero.base.crypto.Hash;
import org.hiero.base.crypto.Signature;
import org.hiero.consensus.model.node.NodeId;
import org.hiero.consensus.roster.RosterUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@DisplayName("State Signing Tests")
class StateSigningTests {

    @BeforeEach
    void setUp() {
        MerkleDb.resetDefaultInstancePath();
    }

    @AfterEach
    void tearDown() {
        RandomSignedStateGenerator.releaseAllBuiltSignedStates();
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    @DisplayName("Add Valid Signatures Test")
    void addValidSignaturesTest(final boolean evenWeighting) {
        final Random random = getRandomPrintSeed();

        final int nodeCount = random.nextInt(10, 20);

        final Roster roster = RandomRosterBuilder.create(random)
                .withWeightGenerator(
                        evenWeighting ? WeightGenerators.BALANCED_1000_PER_NODE : WeightGenerators.GAUSSIAN)
                .withSize(nodeCount)
                .build();
        final SignedState signedState = new RandomSignedStateGenerator(random)
                .setCalculateHash(true)
                .setRoster(roster)
                .setSignatures(new HashMap<>())
                .setCalculateHash(true)
                .build();

        // Randomize roster order
        final List<RosterEntry> nodes = new ArrayList<>(roster.rosterEntries());
        Collections.shuffle(nodes, random);

        final Set<NodeId> signaturesAdded = new HashSet<>();

        final SigSet sigSet = signedState.getSigSet();

        final List<Signature> signatures = new ArrayList<>(nodeCount);
        for (final RosterEntry node : nodes) {
            final PublicKey publicKey =
                    RosterUtils.fetchGossipCaCertificate(node).getPublicKey();
            signatures.add(buildFakeSignature(publicKey, signedState.getState().getHash()));
        }

        long expectedWeight = 0;
        int count = 0;
        for (int index = 0; index < nodeCount; index++) {
            final RosterEntry node = nodes.get(index);
            final NodeId nodeId = NodeId.of(node.nodeId());
            final Signature signature = signatures.get(index);

            final boolean previouslyComplete = signedState.isComplete();
            final boolean completed = signedState.addSignature(NodeId.of(node.nodeId()), signature);
            final boolean nowComplete = signedState.isComplete();
            final boolean verifiable = signedState.isVerifiable();

            if (verifiable) {
                signedState.throwIfNotVerifiable();
            } else {
                assertThrows(SignedStateInvalidException.class, signedState::throwIfNotVerifiable);
            }

            if (!previouslyComplete || !nowComplete) {
                count++;
                expectedWeight += node.weight();
                signaturesAdded.add(nodeId);
            }

            if (completed) {
                assertTrue(!previouslyComplete && nowComplete);
            }

            if (random.nextBoolean()) {
                // Sometimes offer the signature more than once. This should have no effect
                // since duplicates are ignored.
                assertFalse(signedState.addSignature(nodeId, signature));
            }

            final long totalWeight = RosterUtils.computeTotalWeight(roster);

            assertEquals(SUPER_MAJORITY.isSatisfiedBy(expectedWeight, totalWeight), signedState.isComplete());
            assertEquals(MAJORITY.isSatisfiedBy(expectedWeight, totalWeight), signedState.isVerifiable());
            assertEquals(expectedWeight, signedState.getSigningWeight());
            assertEquals(count, sigSet.size());

            for (int metaIndex = 0; metaIndex < nodeCount; metaIndex++) {
                final NodeId metaNodeId = NodeId.of(nodes.get(metaIndex).nodeId());

                if (signaturesAdded.contains(metaNodeId)) {
                    // We have added this signature, make sure the sigset is tracking it
                    assertSame(signatures.get(metaIndex), sigSet.getSignature(metaNodeId));
                } else {
                    // We haven't yet added this signature, the sigset should not be tracking it
                    assertNull(sigSet.getSignature(metaNodeId));
                }
            }

            if (random.nextBoolean()) {
                // This should have no effect
                signedState.pruneInvalidSignatures();
            }
        }
    }

    /**
     * For the test below, treat all nodes divisible by 5 as having invalid signatures.
     */
    private boolean isInvalid(@NonNull final NodeId nodeId) {
        return nodeId.id() % 5 == 0;
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    @DisplayName("Add Invalid Signatures Test")
    void addInvalidSignaturesTest(final boolean evenWeighting) {
        final Random random = getRandomPrintSeed();

        final int nodeCount = random.nextInt(10, 20);

        final Roster roster = RandomRosterBuilder.create(random)
                .withWeightGenerator(
                        evenWeighting ? WeightGenerators.BALANCED_1000_PER_NODE : WeightGenerators.GAUSSIAN)
                .withSize(nodeCount)
                .build();
        final SignedState signedState = new RandomSignedStateGenerator(random)
                .setRoster(roster)
                .setCalculateHash(true)
                .setSignatures(new HashMap<>())
                .setCalculateHash(true)
                .build();

        final Set<NodeId> signaturesAdded = new HashSet<>();

        final SigSet sigSet = signedState.getSigSet();

        // Randomize roster order
        final List<RosterEntry> nodes = new ArrayList<>(roster.rosterEntries());
        Collections.shuffle(nodes, random);

        final List<Signature> signatures = new ArrayList<>(nodeCount);
        for (final RosterEntry node : nodes) {
            if (isInvalid(NodeId.of(node.nodeId()))) {
                // A random signature won't be valid with high probability
                signatures.add(randomSignature(random));
            } else {
                final PublicKey publicKey =
                        RosterUtils.fetchGossipCaCertificate(node).getPublicKey();
                final Signature signature =
                        buildFakeSignature(publicKey, signedState.getState().getHash());
                signatures.add(signature);
            }
        }

        long expectedWeight = 0;
        int count = 0;
        for (int index = 0; index < nodeCount; index++) {
            final RosterEntry node = nodes.get(index);
            final NodeId nodeId = NodeId.of(node.nodeId());
            final Signature signature = signatures.get(index);

            final boolean previouslyComplete = signedState.isComplete();
            final boolean completed = signedState.addSignature(nodeId, signature);
            final boolean nowComplete = signedState.isComplete();

            if (!isInvalid(nodeId) && !previouslyComplete) {
                count++;
                expectedWeight += node.weight();
                signaturesAdded.add(nodeId);
            }

            if (completed) {
                assertTrue(!previouslyComplete && nowComplete);
            }

            if (random.nextBoolean()) {
                // Sometimes offer the signature more than once. This should have no effect
                // since duplicates are ignored.
                assertFalse(signedState.addSignature(nodeId, signature));
            }

            final long totalWeight = RosterUtils.computeTotalWeight(roster);

            assertEquals(SUPER_MAJORITY.isSatisfiedBy(expectedWeight, totalWeight), signedState.isComplete());
            assertEquals(MAJORITY.isSatisfiedBy(expectedWeight, totalWeight), signedState.isVerifiable());
            assertEquals(expectedWeight, signedState.getSigningWeight());
            assertEquals(count, sigSet.size());

            for (int metaIndex = 0; metaIndex < nodeCount; metaIndex++) {
                final NodeId metaNodeId = NodeId.of(nodes.get(metaIndex).nodeId());

                if (signaturesAdded.contains(metaNodeId)) {
                    // We have added this signature, make sure the sigset is tracking it
                    assertSame(signatures.get(metaIndex), sigSet.getSignature(metaNodeId));
                } else {
                    // We haven't yet added this signature or it is invalid, the sigset should not be tracking it
                    assertNull(sigSet.getSignature(metaNodeId));
                }
            }

            if (random.nextBoolean()) {
                // This should have no effect
                signedState.pruneInvalidSignatures();
                assertEquals(expectedWeight, signedState.getSigningWeight());
            }
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    @DisplayName("Signature Becomes Invalid Test")
    void signatureBecomesInvalidTest(final boolean evenWeighting) {
        final Random random = getRandomPrintSeed();

        final int nodeCount = random.nextInt(10, 20);

        final Roster roster = RandomRosterBuilder.create(random)
                .withWeightGenerator(
                        evenWeighting ? WeightGenerators.BALANCED_1000_PER_NODE : WeightGenerators.GAUSSIAN)
                .withSize(nodeCount)
                .build();

        final SignedState signedState = new RandomSignedStateGenerator(random)
                .setRoster(roster)
                .setCalculateHash(true)
                .setSignatures(new HashMap<>())
                .setCalculateHash(true)
                .build();

        final Set<NodeId> signaturesAdded = new HashSet<>();
        long expectedWeight = 0;

        final SigSet sigSet = signedState.getSigSet();

        // Randomize roster order
        final List<RosterEntry> nodes = new ArrayList<>(roster.rosterEntries());
        Collections.shuffle(nodes, random);

        final List<Signature> signatures = new ArrayList<>(nodeCount);
        for (final RosterEntry node : nodes) {
            final PublicKey publicKey =
                    RosterUtils.fetchGossipCaCertificate(node).getPublicKey();
            final Signature signature =
                    buildFakeSignature(publicKey, signedState.getState().getHash());
            final Signature mockSignature = mock(Signature.class);
            when(mockSignature.getBytes()).thenReturn(signature.getBytes());
            when(mockSignature.getType()).thenReturn(signature.getType());
            signatures.add(mockSignature);
        }

        for (int index = 0; index < nodeCount; index++) {
            final RosterEntry node = nodes.get(index);
            final NodeId nodeId = NodeId.of(node.nodeId());
            final boolean alreadyComplete = signedState.isComplete();
            signedState.addSignature(nodeId, signatures.get(index));
            if (!alreadyComplete) {
                signaturesAdded.add(nodeId);
                expectedWeight += node.weight();
            }
        }

        assertTrue(signedState.isComplete());
        assertEquals(signaturesAdded.size(), sigSet.size());
        assertEquals(expectedWeight, signedState.getSigningWeight());

        // Remove a node from the address book
        final RosterEntry nodeToRemove = nodes.getFirst();
        final List<RosterEntry> entries = signedState.getRoster().rosterEntries().stream()
                .filter(entry -> entry.nodeId() != nodeToRemove.nodeId())
                .toList();
        final Roster updatedRoster = new Roster(entries);

        // Tamper with a node's signature
        final long weightWithModifiedSignature = nodes.get(1).weight();
        final byte[] tamperedBytes = signatures.get(1).getBytes().toByteArray();
        tamperedBytes[0] = 0;
        when(signatures.get(1).getBytes()).thenReturn(Bytes.wrap(tamperedBytes));

        signedState.pruneInvalidSignatures(updatedRoster);

        assertEquals(signaturesAdded.size() - 2, sigSet.size());
        assertEquals(
                expectedWeight - weightWithModifiedSignature - nodeToRemove.weight(), signedState.getSigningWeight());

        for (int index = 0; index < nodes.size(); index++) {
            final NodeId nodeId = NodeId.of(nodes.get(index).nodeId());

            if (index == 0 || index == 1 || !signaturesAdded.contains(nodeId)) {
                assertNull(sigSet.getSignature(nodeId));
            } else {
                assertSame(signatures.get(index), sigSet.getSignature(nodeId));
            }
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    @DisplayName("All Signatures Become Invalid Test")
    void allSignaturesBecomeInvalidTest(final boolean evenWeighting) {
        final Random random = getRandomPrintSeed();

        final int nodeCount = random.nextInt(10, 20);

        final Roster roster = RandomRosterBuilder.create(random)
                .withWeightGenerator(
                        evenWeighting ? WeightGenerators.BALANCED_1000_PER_NODE : WeightGenerators.GAUSSIAN)
                .withSize(nodeCount)
                .build();

        final SignedState signedState = new RandomSignedStateGenerator(random)
                .setRoster(roster)
                .setCalculateHash(true)
                .setSignatures(new HashMap<>())
                .setCalculateHash(true)
                .setState(new TestMerkleStateRoot()) // FUTURE WORK: remove this line to use TestHederaVirtualMapState
                .build();

        final SigSet sigSet = signedState.getSigSet();

        // Randomize roster order
        final List<RosterEntry> nodes = new ArrayList<>(roster.rosterEntries());
        Collections.shuffle(nodes, random);

        for (final RosterEntry node : nodes) {
            final PublicKey publicKey =
                    RosterUtils.fetchGossipCaCertificate(node).getPublicKey();
            final Signature signature =
                    buildFakeSignature(publicKey, signedState.getState().getHash());
            signedState.addSignature(NodeId.of(node.nodeId()), signature);
        }

        assertTrue(signedState.isComplete());

        final Hash newHash = randomHash();
        signedState.getState().setHash(newHash);
        signedState.pruneInvalidSignatures();

        assertEquals(0, sigSet.size());
        assertEquals(0, signedState.getSigningWeight());
        assertFalse(signedState.isComplete());
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    @DisplayName("Signatures Invalid With Different Roster Test")
    void signaturesInvalidWithDifferentRosterTest(final boolean evenWeighting) throws CertificateEncodingException {
        final Random random = getRandomPrintSeed();

        final int nodeCount = random.nextInt(10, 20);
        final Roster roster = RandomRosterBuilder.create(random)
                .withWeightGenerator(
                        evenWeighting ? WeightGenerators.BALANCED_1000_PER_NODE : WeightGenerators.GAUSSIAN)
                .withSize(nodeCount)
                .build();

        final SignedState signedState = new RandomSignedStateGenerator(random)
                .setRoster(roster)
                .setCalculateHash(true)
                .setSignatures(new HashMap<>())
                .setCalculateHash(true)
                .build();

        final SigSet sigSet = signedState.getSigSet();

        // Randomize roster order
        final List<RosterEntry> nodes = new ArrayList<>(roster.rosterEntries());
        Collections.shuffle(nodes, random);

        for (final RosterEntry node : nodes) {
            final PublicKey publicKey =
                    RosterUtils.fetchGossipCaCertificate(node).getPublicKey();
            final Signature signature =
                    buildFakeSignature(publicKey, signedState.getState().getHash());
            signedState.addSignature(NodeId.of(node.nodeId()), signature);
        }

        assertTrue(signedState.isComplete());

        final List<RosterEntry> newRosterEntries = new ArrayList<>(nodeCount);

        for (final RosterEntry originalNode : roster.rosterEntries()) {
            final X509Certificate certificate =
                    PreGeneratedX509Certs.getSigCert(50 + originalNode.nodeId()).getCertificate();
            final RosterEntry newNode = originalNode
                    .copyBuilder()
                    .gossipCaCertificate(Bytes.wrap(certificate.getEncoded()))
                    .build();
            newRosterEntries.add(newNode);
        }

        final Roster newRoster = new Roster(newRosterEntries);

        signedState.pruneInvalidSignatures(newRoster);

        assertEquals(0, sigSet.size());
        assertEquals(0, signedState.getSigningWeight());
        assertFalse(signedState.isComplete());
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    @DisplayName("Signatures Invalid Due To Zero Weight")
    void signaturesInvalidDueToZeroWeightTest(final boolean evenWeighting) {
        final Random random = getRandomPrintSeed();

        final int nodeCount = random.nextInt(10, 20);

        final Roster tempRoster = RandomRosterBuilder.create(random)
                .withWeightGenerator(
                        evenWeighting ? WeightGenerators.BALANCED_1000_PER_NODE : WeightGenerators.GAUSSIAN)
                .withSize(nodeCount)
                .build();

        // set node to zero weight
        final List<RosterEntry> rosterEntries = IntStream.range(
                        0, tempRoster.rosterEntries().size())
                .mapToObj(idx -> {
                    final RosterEntry entry = tempRoster.rosterEntries().get(idx);
                    if (idx == 0) {
                        // replace the first node such that it has a weight of 0
                        return entry.copyBuilder().weight(0L).build();
                    }
                    return entry;
                })
                .toList();
        final Roster roster = new Roster(rosterEntries);

        final SignedState signedState = new RandomSignedStateGenerator(random)
                .setRoster(roster)
                .setCalculateHash(true)
                .setSignatures(new HashMap<>())
                .setCalculateHash(true)
                .build();

        final SigSet sigSet = signedState.getSigSet();

        // Randomize roster order
        final List<RosterEntry> nodes = new ArrayList<>(roster.rosterEntries());
        Collections.shuffle(nodes, random);

        for (final RosterEntry node : nodes) {
            final PublicKey publicKey =
                    RosterUtils.fetchGossipCaCertificate(node).getPublicKey();
            final Signature signature =
                    buildFakeSignature(publicKey, signedState.getState().getHash());
            signedState.addSignature(NodeId.of(node.nodeId()), signature);
        }

        assertFalse(
                sigSet.hasSignature(NodeId.of(roster.rosterEntries().getFirst().nodeId())),
                "Signature for node with zero weight should not be added");
        assertTrue(signedState.isComplete());

        final List<RosterEntry> newRosterEntries = roster.rosterEntries().stream()
                .map(entry -> entry.copyBuilder().weight(0L).build())
                .toList();
        final Roster newRoster = new Roster(newRosterEntries);

        signedState.pruneInvalidSignatures(newRoster);

        assertEquals(0, sigSet.size());
        assertEquals(0, signedState.getSigningWeight());
        assertFalse(signedState.isComplete());
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    @DisplayName("Recovery State Is Complete Test")
    void recoveryStateIsCompleteTest(final boolean evenWeighting) {
        final Random random = getRandomPrintSeed();

        final int nodeCount = random.nextInt(10, 20);

        final Roster roster = RandomRosterBuilder.create(random)
                .withWeightGenerator(
                        evenWeighting ? WeightGenerators.BALANCED_1000_PER_NODE : WeightGenerators.GAUSSIAN)
                .withSize(nodeCount)
                .build();

        final SignedState signedState = new RandomSignedStateGenerator(random)
                .setRoster(roster)
                .setCalculateHash(true)
                .setSignatures(new HashMap<>())
                .setCalculateHash(true)
                .build();

        assertFalse(signedState.isComplete());

        signedState.markAsRecoveryState();

        // Recovery states are considered to be complete regardless of signature count
        assertTrue(signedState.isComplete());
    }
}
