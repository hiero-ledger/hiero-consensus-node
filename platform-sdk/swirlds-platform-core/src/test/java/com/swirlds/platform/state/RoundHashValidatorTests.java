// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.state;

import static com.swirlds.common.utility.Threshold.MAJORITY;
import static com.swirlds.common.utility.Threshold.SUPER_MAJORITY;
import static org.hiero.base.crypto.test.fixtures.CryptoRandomUtils.randomHash;
import static org.hiero.base.utility.test.fixtures.RandomUtils.getRandomPrintSeed;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.hedera.hapi.node.state.roster.Roster;
import com.hedera.hapi.node.state.roster.RosterEntry;
import com.swirlds.common.test.fixtures.GaussianWeightGenerator;
import com.swirlds.common.test.fixtures.WeightGenerator;
import com.swirlds.platform.metrics.IssMetrics;
import com.swirlds.platform.state.iss.internal.HashValidityStatus;
import com.swirlds.platform.state.iss.internal.RoundHashValidator;
import com.swirlds.platform.test.fixtures.addressbook.RandomRosterBuilder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.stream.Stream;
import org.hiero.base.crypto.Hash;
import org.hiero.consensus.model.node.NodeId;
import org.hiero.consensus.roster.RosterUtils;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.Mockito;

@DisplayName("RoundHashValidator Tests")
class RoundHashValidatorTests {
    private static final WeightGenerator WEIGHT_GENERATOR = GaussianWeightGenerator.withAverageNodeWeight(100, 50);

    static Stream<Arguments> args() {
        return Stream.of(
                Arguments.of(HashValidityStatus.VALID),
                Arguments.of(HashValidityStatus.SELF_ISS),
                Arguments.of(HashValidityStatus.CATASTROPHIC_ISS));
    }

    /**
     * Describes a node's hash and the round it was generated in.
     *
     * @param nodeId        the node ID
     * @param nodeStateHash the hash the node will report
     * @param round         the round the hash was generated in
     */
    record NodeHashInfo(NodeId nodeId, Hash nodeStateHash, long round) {}

    /**
     * Holds a list of {@link NodeHashInfo} for a given round, and that round's consensus hash.
     *
     * @param nodeList      the node hash info list
     * @param consensusHash the consensus hash of the round
     */
    record HashGenerationData(List<NodeHashInfo> nodeList, Hash consensusHash) {}

    /**
     * Based on the desired network status, generate hashes for all nodes.
     *
     * @param random                a source of randomness
     * @param roster                the roster for the round
     * @param desiredValidityStatus the desired validity status
     * @return a list of node IDs in the order they should be added to the hash validator
     */
    static HashGenerationData generateNodeHashes(
            final Random random,
            final Roster roster,
            final HashValidityStatus desiredValidityStatus,
            final long round) {
        if (desiredValidityStatus == HashValidityStatus.VALID || desiredValidityStatus == HashValidityStatus.SELF_ISS) {
            return generateRegularNodeHashes(random, roster, round);
        } else if (desiredValidityStatus == HashValidityStatus.CATASTROPHIC_ISS) {
            return generateCatastrophicNodeHashes(random, roster, round);
        } else {
            throw new IllegalArgumentException("Unsupported case " + desiredValidityStatus);
        }
    }

    /**
     * Generate node hashes that contain an ISS without there being a catastrophic ISS.
     */
    static HashGenerationData generateRegularNodeHashes(final Random random, final Roster roster, final long round) {

        // Greater than 1/2 must have the same hash. But all other nodes are free to take whatever other hash
        // they want. Choose that fraction randomly.

        final List<NodeId> randomNodeOrder = new LinkedList<>();
        roster.rosterEntries().forEach(node -> randomNodeOrder.add(NodeId.of(node.nodeId())));
        Collections.shuffle(randomNodeOrder, random);

        final long totalWeight = RosterUtils.computeTotalWeight(roster);

        // This is the hash we want to be chosen for the consensus hash.
        final Hash consensusHash = randomHash(random);
        final List<NodeId> correctHashNodes = new LinkedList<>();
        long correctHashWeight = 0;

        // A large group of nodes may decide to use this hash. But it won't become the consensus hash.
        final Hash otherHash = randomHash(random);
        long otherHashWeight = 0;
        final List<NodeId> otherHashNodes = new LinkedList<>();

        // All remaining nodes will choose a hash randomly.
        final List<NodeId> randomHashNodes = new LinkedList<>();

        // Assign each node to one of the hashing strategies described above.
        final Map<Long, RosterEntry> nodesById = RosterUtils.toMap(roster);
        for (final NodeId nodeId : randomNodeOrder) {
            final long weight = nodesById.get(nodeId.id()).weight();

            if (!MAJORITY.isSatisfiedBy(correctHashWeight, totalWeight)) {
                correctHashNodes.add(nodeId);
                correctHashWeight += weight;
            } else {
                if (random.nextBoolean()) {
                    otherHashNodes.add(nodeId);
                } else {
                    randomHashNodes.add(nodeId);
                }
            }
        }

        correctHashWeight = 0;

        // For the sake of sanity, don't let this loop go on forever
        int allowedIterations = 1_000_000;

        // Now, decide what order the hashes should be processed. Make sure that the
        // consensus hash is the first to reach a strong minority.
        final List<NodeHashInfo> nodes = new LinkedList<>();
        while (nodes.size() < roster.rosterEntries().size()) {
            final double choice = random.nextDouble();

            allowedIterations--;
            assertTrue(allowedIterations > 0, "loop is not terminating");

            if (choice < 1.0 / 3) {
                if (!correctHashNodes.isEmpty()) {
                    final NodeId nodeId = correctHashNodes.remove(0);
                    final long weight = nodesById.get(nodeId.id()).weight();
                    nodes.add(new NodeHashInfo(nodeId, consensusHash, round));
                    correctHashWeight += weight;
                }
            } else if (choice < 2.0 / 3) {
                if (!otherHashNodes.isEmpty()) {
                    final NodeId nodeId = otherHashNodes.get(0);
                    final long weight = nodesById.get(nodeId.id()).weight();

                    // TODO - this if statement seems redundant because in the above block,
                    //  the set of correctHashNodes nodes is guaranteed to be a majority of
                    //  weight. Come back later and try removing it
                    if (MAJORITY.isSatisfiedBy(otherHashWeight + weight, totalWeight)) {
                        // We don't want to allow the other hash to accumulate >1/2
                        continue;
                    }

                    otherHashNodes.remove(0);

                    nodes.add(new NodeHashInfo(nodeId, otherHash, round));
                    otherHashWeight += nodesById.get(nodeId.id()).weight();
                }
            } else {
                // The random hashes will never reach a majority, so they can go in whenever
                if (!randomHashNodes.isEmpty()) {
                    final NodeId nodeId = randomHashNodes.remove(0);
                    nodes.add(new NodeHashInfo(nodeId, randomHash(random), round));
                }
            }
        }

        return new HashGenerationData(nodes, consensusHash);
    }

    /**
     * Generate node hashes that result in a catastrophic ISS.
     */
    static HashGenerationData generateCatastrophicNodeHashes(
            final Random random, final Roster roster, final long round) {

        // There should exist no group of nodes with the same hash that >1/2

        final List<NodeHashInfo> nodes = new ArrayList<>();

        final long totalWeight = RosterUtils.computeTotalWeight(roster);

        final List<NodeId> randomNodeOrder = new LinkedList<>();
        roster.rosterEntries().forEach(node -> randomNodeOrder.add(NodeId.of(node.nodeId())));
        Collections.shuffle(randomNodeOrder, random);

        // A large group of nodes may decide to use this hash. But it won't become the consensus hash.
        final Hash otherHash = randomHash(random);
        long otherHashWeight = 0;

        final Map<Long, RosterEntry> nodesById = RosterUtils.toMap(roster);
        for (final NodeId nodeId : randomNodeOrder) {
            final long weight = nodesById.get(nodeId.id()).weight();

            final double choice = random.nextDouble();
            if (choice < 1.0 / 3 && !MAJORITY.isSatisfiedBy(otherHashWeight + weight, totalWeight)) {
                nodes.add(new NodeHashInfo(nodeId, otherHash, round));
                otherHashWeight += weight;
            } else {
                nodes.add(new NodeHashInfo(nodeId, randomHash(random), round));
            }
        }

        return new HashGenerationData(nodes, null);
    }

    /**
     * Choose a node to be the "self" node
     */
    private static NodeHashInfo chooseSelfNode(
            final Random random,
            final HashGenerationData hashGenerationData,
            final HashValidityStatus desiredHashValidityStatus) {

        if (desiredHashValidityStatus == HashValidityStatus.CATASTROPHIC_ISS) {
            // It doesn't matter which node we choose
            return hashGenerationData.nodeList.get(random.nextInt(hashGenerationData.nodeList.size()));
        } else if (desiredHashValidityStatus == HashValidityStatus.SELF_ISS) {
            final List<NodeHashInfo> possibleChoices = new ArrayList<>();
            for (final NodeHashInfo node : hashGenerationData.nodeList) {
                if (!node.nodeStateHash.equals(hashGenerationData.consensusHash)) {
                    possibleChoices.add(node);
                }
            }
            return possibleChoices.get(random.nextInt(possibleChoices.size()));

        } else if (desiredHashValidityStatus == HashValidityStatus.VALID) {
            final List<NodeHashInfo> possibleChoices = new ArrayList<>();
            for (final NodeHashInfo node : hashGenerationData.nodeList) {
                if (node.nodeStateHash.equals(hashGenerationData.consensusHash)) {
                    possibleChoices.add(node);
                }
            }
            return possibleChoices.get(random.nextInt(possibleChoices.size()));
        } else {
            throw new IllegalArgumentException();
        }
    }

    @ParameterizedTest
    @MethodSource("args")
    @DisplayName("Self Signature Last Test")
    void selfSignatureLastTest(final HashValidityStatus expectedStatus) {
        final Random random = getRandomPrintSeed();

        final Roster roster = RandomRosterBuilder.create(random)
                .withSize(Math.max(10, random.nextInt(1000)))
                .withWeightGenerator(WEIGHT_GENERATOR)
                .build();

        final HashGenerationData hashGenerationData = generateNodeHashes(random, roster, expectedStatus, 0);
        final NodeHashInfo thisNode = chooseSelfNode(random, hashGenerationData, expectedStatus);

        final long round = random.nextInt(1000);
        final RoundHashValidator validator =
                new RoundHashValidator(round, RosterUtils.computeTotalWeight(roster), Mockito.mock(IssMetrics.class));

        boolean decided = false;

        final Map<Long, RosterEntry> nodesById = RosterUtils.toMap(roster);
        for (final NodeHashInfo nodeHashInfo : hashGenerationData.nodeList) {
            final NodeId nodeId = nodeHashInfo.nodeId;
            final long weight = nodesById.get(nodeId.id()).weight();
            final Hash hash = nodeHashInfo.nodeStateHash;

            final boolean operationCausedDecision = validator.reportHashFromNetwork(nodeId, weight, hash);

            if (expectedStatus != HashValidityStatus.CATASTROPHIC_ISS) {
                assertFalse(operationCausedDecision, "should not be decided until self hash is added");
                assertEquals(HashValidityStatus.UNDECIDED, validator.getStatus(), "should not yet be decided");
            } else if (operationCausedDecision) {
                assertFalse(decided, "should only be decided once");
                decided = true;
            }
        }

        if (expectedStatus != HashValidityStatus.CATASTROPHIC_ISS) {
            assertTrue(validator.reportSelfHash(thisNode.nodeStateHash), "validator should now be decided");
        } else {
            assertFalse(validator.reportSelfHash(thisNode.nodeStateHash), "validator should already be decided");
        }
        assertFalse(validator.outOfTime(), "timing out should have no effect here");
        assertEquals(expectedStatus, validator.getStatus(), "unexpected status");
    }

    @ParameterizedTest
    @MethodSource("args")
    @DisplayName("Self Signature First Test")
    void selfSignatureFirstTest(final HashValidityStatus expectedStatus) {
        final Random random = getRandomPrintSeed();

        final Roster roster = RandomRosterBuilder.create(random)
                .withSize(Math.max(10, random.nextInt(1000)))
                .withWeightGenerator(WEIGHT_GENERATOR)
                .build();

        final HashGenerationData hashGenerationData = generateNodeHashes(random, roster, expectedStatus, 0);
        final NodeHashInfo thisNode = chooseSelfNode(random, hashGenerationData, expectedStatus);

        final long round = random.nextInt(1000);
        final RoundHashValidator validator =
                new RoundHashValidator(round, RosterUtils.computeTotalWeight(roster), Mockito.mock(IssMetrics.class));

        boolean decided = false;

        assertFalse(
                validator.reportSelfHash(thisNode.nodeStateHash),
                "we should need to gather more data before becoming decided");

        final Map<Long, RosterEntry> nodesById = RosterUtils.toMap(roster);
        for (final NodeHashInfo nodeHashInfo : hashGenerationData.nodeList) {
            final NodeId nodeId = nodeHashInfo.nodeId;
            final long weight = nodesById.get(nodeId.id()).weight();
            final Hash hash = nodeHashInfo.nodeStateHash;

            final boolean operationCausedDecision = validator.reportHashFromNetwork(nodeId, weight, hash);

            if (operationCausedDecision) {
                assertFalse(decided, "should only be decided once");
                decided = true;
            }
        }

        assertFalse(validator.outOfTime(), "timing out should have no effect here");

        assertTrue(decided, "should have been decided");
        assertEquals(expectedStatus, validator.getStatus(), "unexpected status");
    }

    @ParameterizedTest
    @MethodSource("args")
    @DisplayName("Self Signature In Middle Test")
    void selfSignatureInMiddleTest(final HashValidityStatus expectedStatus) {
        final Random random = getRandomPrintSeed();

        final Roster roster = RandomRosterBuilder.create(random)
                .withSize(Math.max(10, random.nextInt(1000)))
                .withWeightGenerator(WEIGHT_GENERATOR)
                .build();

        final HashGenerationData hashGenerationData = generateNodeHashes(random, roster, expectedStatus, 0);
        final NodeHashInfo thisNode = chooseSelfNode(random, hashGenerationData, expectedStatus);

        final long round = random.nextInt(1000);
        final RoundHashValidator validator =
                new RoundHashValidator(round, RosterUtils.computeTotalWeight(roster), Mockito.mock(IssMetrics.class));

        boolean decided = false;

        final int addSelfHashIndex = random.nextInt(roster.rosterEntries().size() - 1);
        int index = 0;

        final Map<Long, RosterEntry> nodesById = RosterUtils.toMap(roster);
        for (final NodeHashInfo nodeHashInfo : hashGenerationData.nodeList) {
            final NodeId nodeId = nodeHashInfo.nodeId;
            final long weight = nodesById.get(nodeId.id()).weight();
            final Hash hash = nodeHashInfo.nodeStateHash;

            if (index == addSelfHashIndex) {
                final boolean operationCausedDecision = validator.reportSelfHash(thisNode.nodeStateHash);
                if (operationCausedDecision) {
                    assertFalse(decided, "should only be decided once");
                    decided = true;
                }
            }
            index++;

            final boolean operationCausedDecision = validator.reportHashFromNetwork(nodeId, weight, hash);
            if (operationCausedDecision) {
                assertFalse(decided, "should only be decided once");
                decided = true;
            }
        }

        assertFalse(validator.outOfTime(), "timing out should have no effect here");

        assertTrue(decided, "should have been decided");
        assertEquals(expectedStatus, validator.getStatus(), "unexpected status");
    }

    @Test
    @DisplayName("Timeout Self Hash Test")
    void timeoutSelfHashTest() {
        final Random random = getRandomPrintSeed();

        final Roster roster = RandomRosterBuilder.create(random)
                .withSize(Math.max(10, random.nextInt(1000)))
                .withWeightGenerator(WEIGHT_GENERATOR)
                .build();

        final HashGenerationData hashGenerationData = generateNodeHashes(random, roster, HashValidityStatus.VALID, 0);

        final long round = random.nextInt(1000);
        final RoundHashValidator validator =
                new RoundHashValidator(round, RosterUtils.computeTotalWeight(roster), Mockito.mock(IssMetrics.class));

        final Map<Long, RosterEntry> nodesById = RosterUtils.toMap(roster);
        for (final NodeHashInfo nodeHashInfo : hashGenerationData.nodeList) {
            final NodeId nodeId = nodeHashInfo.nodeId;
            final long weight = nodesById.get(nodeId.id()).weight();
            final Hash hash = nodeHashInfo.nodeStateHash;

            assertFalse(validator.reportHashFromNetwork(nodeId, weight, hash), "insufficient data to make decision");
            assertEquals(HashValidityStatus.UNDECIDED, validator.getStatus(), "should not be decided");
        }

        assertTrue(
                validator.outOfTime(), "since we have not decided, running out of time will make the decision for us");

        assertEquals(HashValidityStatus.LACK_OF_DATA, validator.getStatus(), "we should lack data");
    }

    @Test
    @DisplayName("Timeout Self Hash And Signatures Test")
    void timeoutSelfHashAndSignaturesTest() {
        final Random random = getRandomPrintSeed();

        final Roster roster = RandomRosterBuilder.create(random)
                .withSize(Math.max(10, random.nextInt(1000)))
                .withWeightGenerator(WEIGHT_GENERATOR)
                .build();
        final long totalWeight = RosterUtils.computeTotalWeight(roster);

        final HashGenerationData hashGenerationData = generateNodeHashes(random, roster, HashValidityStatus.VALID, 0);

        final long round = random.nextInt(1000);
        final RoundHashValidator validator = new RoundHashValidator(round, totalWeight, Mockito.mock(IssMetrics.class));

        long addedWeight = 0;

        final Map<Long, RosterEntry> nodesById = RosterUtils.toMap(roster);
        for (final NodeHashInfo nodeHashInfo : hashGenerationData.nodeList) {
            final NodeId nodeId = nodeHashInfo.nodeId;
            final long weight = nodesById.get(nodeId.id()).weight();
            final Hash hash = nodeHashInfo.nodeStateHash;

            if (MAJORITY.isSatisfiedBy(addedWeight + weight, totalWeight)) {
                // Don't add enough hash data to reach a decision
                break;
            }

            assertFalse(validator.reportHashFromNetwork(nodeId, weight, hash), "insufficient data to make decision");
            assertEquals(HashValidityStatus.UNDECIDED, validator.getStatus(), "should not be decided");
        }

        assertTrue(
                validator.outOfTime(), "since we have not decided, running out of time will make the decision for us");

        assertEquals(HashValidityStatus.LACK_OF_DATA, validator.getStatus(), "we should lack data");
    }

    @Test
    @DisplayName("Timeout Self Hash And Signatures Test")
    void timeoutSignaturesTest() {
        final Random random = getRandomPrintSeed();

        final Roster roster = RandomRosterBuilder.create(random)
                .withSize(Math.max(10, random.nextInt(1000)))
                .withWeightGenerator(WEIGHT_GENERATOR)
                .build();
        final long totalWeight = RosterUtils.computeTotalWeight(roster);

        final HashGenerationData hashGenerationData = generateNodeHashes(random, roster, HashValidityStatus.VALID, 0);
        final NodeHashInfo thisNode = chooseSelfNode(random, hashGenerationData, HashValidityStatus.VALID);

        final long round = random.nextInt(1000);
        final RoundHashValidator validator = new RoundHashValidator(round, totalWeight, Mockito.mock(IssMetrics.class));

        assertFalse(validator.reportSelfHash(thisNode.nodeStateHash), "should not allow a decision");

        long addedWeight = 0;

        final Map<Long, RosterEntry> nodesById = RosterUtils.toMap(roster);
        for (final NodeHashInfo nodeHashInfo : hashGenerationData.nodeList) {
            final NodeId nodeId = nodeHashInfo.nodeId;
            final long weight = nodesById.get(nodeId.id()).weight();
            final Hash hash = nodeHashInfo.nodeStateHash;

            if (MAJORITY.isSatisfiedBy(addedWeight + weight, totalWeight)) {
                // Don't add enough hash data to reach a decision
                break;
            }
            addedWeight += weight;

            assertFalse(validator.reportHashFromNetwork(nodeId, weight, hash), "insufficient data to make decision");
            assertEquals(HashValidityStatus.UNDECIDED, validator.getStatus(), "should not be decided");
        }

        assertTrue(
                validator.outOfTime(), "since we have not decided, running out of time will make the decision for us");

        assertEquals(HashValidityStatus.LACK_OF_DATA, validator.getStatus(), "we should lack data");
    }

    @Test
    @DisplayName("Timeout With Super Majority Test")
    void timeoutWithSuperMajorityTest() {
        final Random random = getRandomPrintSeed();

        final Roster roster = RandomRosterBuilder.create(random)
                .withSize(Math.max(10, random.nextInt(1000)))
                .withWeightGenerator(WEIGHT_GENERATOR)
                .build();
        final long totalWeight = RosterUtils.computeTotalWeight(roster);

        final HashGenerationData hashGenerationData =
                generateNodeHashes(random, roster, HashValidityStatus.CATASTROPHIC_ISS, 0);
        final NodeHashInfo thisNode = chooseSelfNode(random, hashGenerationData, HashValidityStatus.CATASTROPHIC_ISS);

        final long round = random.nextInt(1000);
        final RoundHashValidator validator = new RoundHashValidator(round, totalWeight, Mockito.mock(IssMetrics.class));

        assertFalse(validator.reportSelfHash(thisNode.nodeStateHash), "should not allow a decision");

        long addedWeight = 0;

        final Map<Long, RosterEntry> nodesById = RosterUtils.toMap(roster);
        for (final NodeHashInfo nodeHashInfo : hashGenerationData.nodeList) {
            final NodeId nodeId = nodeHashInfo.nodeId;
            final long weight = nodesById.get(nodeId.id()).weight();
            final Hash hash = nodeHashInfo.nodeStateHash;

            boolean decided = validator.reportHashFromNetwork(nodeId, weight, hash);

            if (decided) {
                // There is a very low probability that the chosen data set will
                // have a catastrophic ISS that is discoverable at this point
                // in time (~1%). That's not the scenario we are trying
                // to test. But we shouldn't fail if the data choice was unlucky.
                assertEquals(HashValidityStatus.CATASTROPHIC_ISS, validator.getStatus());
                assertTrue(MAJORITY.isSatisfiedBy(addedWeight + weight, totalWeight));
                return;
            }

            assertEquals(HashValidityStatus.UNDECIDED, validator.getStatus(), "should not be decided");

            addedWeight += weight;
            if (SUPER_MAJORITY.isSatisfiedBy(addedWeight, totalWeight)) {
                // quit once we add a super majority
                break;
            }
        }

        assertTrue(
                validator.outOfTime(), "since we have not decided, running out of time will make the decision for us");

        assertEquals(
                HashValidityStatus.CATASTROPHIC_LACK_OF_DATA,
                validator.getStatus(),
                "gathering >= 2/3 without reaching a decision should lead to catastrophic lack of data");
    }
}
