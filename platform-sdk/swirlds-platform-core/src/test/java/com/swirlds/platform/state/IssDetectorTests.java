// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.state;

import static com.swirlds.common.utility.Threshold.MAJORITY;
import static com.swirlds.common.utility.Threshold.SUPER_MAJORITY;
import static com.swirlds.platform.state.RoundHashValidatorTests.generateCatastrophicNodeHashes;
import static com.swirlds.platform.state.RoundHashValidatorTests.generateRegularNodeHashes;
import static com.swirlds.platform.state.iss.IssDetector.DO_NOT_IGNORE_ROUNDS;
import static org.hiero.base.crypto.test.fixtures.CryptoRandomUtils.randomHash;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.hedera.hapi.node.base.SemanticVersion;
import com.hedera.hapi.node.state.roster.Roster;
import com.hedera.hapi.node.state.roster.RosterEntry;
import com.hedera.hapi.platform.event.StateSignatureTransaction;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.common.context.PlatformContext;
import com.swirlds.common.test.fixtures.GaussianWeightGenerator;
import com.swirlds.common.test.fixtures.Randotron;
import com.swirlds.common.test.fixtures.WeightGenerator;
import com.swirlds.platform.consensus.ConsensusConfig;
import com.swirlds.platform.state.iss.DefaultIssDetector;
import com.swirlds.platform.state.iss.IssDetector;
import com.swirlds.platform.state.iss.internal.HashValidityStatus;
import com.swirlds.platform.state.signed.ReservedSignedState;
import com.swirlds.platform.state.signed.SignedState;
import com.swirlds.platform.test.fixtures.PlatformTest;
import com.swirlds.platform.test.fixtures.addressbook.RandomRosterBuilder;
import com.swirlds.platform.test.fixtures.state.RandomSignedStateGenerator;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import org.hiero.base.crypto.Hash;
import org.hiero.consensus.model.node.NodeId;
import org.hiero.consensus.model.notification.IssNotification;
import org.hiero.consensus.model.notification.IssNotification.IssType;
import org.hiero.consensus.model.transaction.ScopedSystemTransaction;
import org.hiero.consensus.roster.RosterUtils;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("IssDetector Tests")
class IssDetectorTests extends PlatformTest {
    private static final WeightGenerator WEIGHT_GENERATOR = new GaussianWeightGenerator(100, 50);

    @Test
    @DisplayName("State reservation is released")
    void stateReservationIsReleased() {
        final Randotron random = Randotron.create();
        final RandomSignedStateGenerator stateGenerator = new RandomSignedStateGenerator(random);
        final ReservedSignedState stateWrapperForTest = stateGenerator.build().reserve("Test caller reference");
        final ReservedSignedState stateWrapperForIssDetector =
                stateWrapperForTest.getAndReserve("ISS Detector caller reference");
        assertEquals(
                2,
                stateWrapperForTest.get().getReservationCount(),
                "The state should have a single reservation before being passed to the ISS Detector");

        final PlatformContext platformContext = createDefaultPlatformContext();
        final IssDetector issDetector = new DefaultIssDetector(
                platformContext, mock(Roster.class), SemanticVersion.DEFAULT, false, DO_NOT_IGNORE_ROUNDS);

        issDetector.handleState(stateWrapperForIssDetector);
        assertTrue(stateWrapperForIssDetector.isClosed(), "State passed to the ISS Detector should be closed");
        assertFalse(stateWrapperForTest.isClosed(), "State held by the test should not be closed");
        assertEquals(
                1,
                stateWrapperForTest.get().getReservationCount(),
                "The test caller should still have a reservation on the state");
    }

    @Test
    @DisplayName("No ISSes Test")
    void noIss() {
        final Randotron random = Randotron.create();
        final Roster roster = RandomRosterBuilder.create(random)
                .withSize(100)
                .withWeightGenerator(WEIGHT_GENERATOR)
                .build();

        final PlatformContext platformContext = createDefaultPlatformContext();

        final IssDetector issDetector =
                new DefaultIssDetector(platformContext, roster, SemanticVersion.DEFAULT, false, DO_NOT_IGNORE_ROUNDS);
        final IssDetectorTestHelper issDetectorTestHelper = new IssDetectorTestHelper(issDetector);

        long currentRound = 0;
        issDetectorTestHelper.overridingState(mockState(currentRound, randomHash()));

        // A collection of all node signatures for all rounds that have yet to be submitted. These signatures are
        // generated for each round, and then are added randomly in subsequent calls
        final List<ScopedSystemTransaction<StateSignatureTransaction>> unsubmittedSignatures = new ArrayList<>();

        for (currentRound++; currentRound <= 1_000; currentRound++) {
            final Hash roundHash = randomHash(random);

            // create signature transactions for this round
            final RoundHashValidatorTests.HashGenerationData hashGenerationData =
                    constructHashGenerationData(roster, currentRound, roundHash);
            final Map<NodeId, ScopedSystemTransaction<StateSignatureTransaction>>
                    nodeIdToStateSignatureMapForCurrentRound =
                            generateSystemTransactions(currentRound, hashGenerationData);

            // Add all the generated signatures for this round to the list of all signatures for all rounds
            unsubmittedSignatures.addAll(nodeIdToStateSignatureMapForCurrentRound.values());

            // randomly select half of the unsubmitted signatures to now submit
            final List<ScopedSystemTransaction<StateSignatureTransaction>> signaturesToSubmit =
                    selectRandomSignatures(random, unsubmittedSignatures);

            // Randomly choose to submit the transactions before the state or vice versa
            if (random.nextBoolean()) {
                issDetectorTestHelper.handleStateSignatureTransactions(signaturesToSubmit);
                issDetectorTestHelper.handleState(mockState(currentRound, roundHash));
            } else {
                issDetectorTestHelper.handleState(mockState(currentRound, roundHash));
                issDetectorTestHelper.handleStateSignatureTransactions(signaturesToSubmit);
            }
        }

        // Add all remaining unsubmitted signatures
        issDetectorTestHelper.handleStateSignatureTransactions(unsubmittedSignatures);

        assertEquals(0, issDetectorTestHelper.getSelfIssCount(), "there should be no ISS notifications");
        assertEquals(
                0,
                issDetectorTestHelper.getCatastrophicIssCount(),
                "there should be no catastrophic ISS notifications");
        assertEquals(0, issDetectorTestHelper.getIssNotificationList().size(), "there should be no ISS notifications");

        // verify marker files
        assertMarkerFile(IssType.CATASTROPHIC_ISS.toString(), false);
        assertMarkerFile(IssType.SELF_ISS.toString(), false);
        assertMarkerFile(IssType.OTHER_ISS.toString(), false);
    }

    /**
     * This test goes through a series of rounds, some of which experience ISSes. The test verifies that the expected
     * number of ISSes are registered by the ISS detector.
     */
    @Test
    @DisplayName("Mixed Order Test")
    void mixedOrderTest() {
        final Randotron random = Randotron.create();

        final Roster roster = RandomRosterBuilder.create(random)
                .withSize(Math.max(10, random.nextInt(1000)))
                .withWeightGenerator(WEIGHT_GENERATOR)
                .build();

        final PlatformContext platformContext = createDefaultPlatformContext();

        final NodeId selfId = NodeId.of(roster.rosterEntries().getFirst().nodeId());
        final int roundsNonAncient = platformContext
                .getConfiguration()
                .getConfigData(ConsensusConfig.class)
                .roundsNonAncient();

        // Build a roadmap for this test. Generate the hashes that will be sent to the detector, and determine
        // the expected result of adding these hashes to the detector.
        final List<RoundHashValidatorTests.HashGenerationData> roundData = new ArrayList<>(roundsNonAncient);
        final List<HashValidityStatus> expectedRoundStatus = new ArrayList<>(roundsNonAncient);
        int expectedSelfIssCount = 0;
        int expectedCatastrophicIssCount = 0;
        final List<Hash> selfHashes = new ArrayList<>(roundsNonAncient);
        for (int round = 0; round < roundsNonAncient; round++) {
            final RoundHashValidatorTests.HashGenerationData data;

            if (random.nextDouble() < 2.0 / 3) {
                // Choose hashes so that there is a valid consensus hash
                data = generateRegularNodeHashes(random, roster, round);

                HashValidityStatus expectedStatus = null;

                // Find this node's hash to figure out if we ISSed
                for (final RoundHashValidatorTests.NodeHashInfo nodeInfo : data.nodeList()) {
                    if (nodeInfo.nodeId() == selfId) {
                        final Hash selfHash = nodeInfo.nodeStateHash();
                        if (selfHash.equals(data.consensusHash())) {
                            expectedStatus = HashValidityStatus.VALID;
                        } else {
                            expectedStatus = HashValidityStatus.SELF_ISS;
                            expectedSelfIssCount++;
                        }
                        break;
                    }
                }

                assertNotNull(expectedStatus, "expected status should have been set");

                roundData.add(data);
                expectedRoundStatus.add(expectedStatus);
            } else {
                // Choose hashes that will result in a catastrophic ISS
                data = generateCatastrophicNodeHashes(random, roster, round);
                roundData.add(data);
                expectedRoundStatus.add(HashValidityStatus.CATASTROPHIC_ISS);
                expectedCatastrophicIssCount++;
            }

            // Figure out self hashes
            for (final RoundHashValidatorTests.NodeHashInfo nodeHashInfo : data.nodeList()) {
                if (nodeHashInfo.nodeId() == selfId) {
                    final Hash selfHash = nodeHashInfo.nodeStateHash();
                    selfHashes.add(selfHash);
                    break;
                }
            }
        }

        final IssDetector issDetector =
                new DefaultIssDetector(platformContext, roster, SemanticVersion.DEFAULT, false, DO_NOT_IGNORE_ROUNDS);
        final IssDetectorTestHelper issDetectorTestHelper = new IssDetectorTestHelper(issDetector);

        long currentRound = 0;
        issDetectorTestHelper.overridingState(mockState(currentRound, selfHashes.getFirst()));

        // All signature that have not yet been submitted to the ISS detector.
        final List<ScopedSystemTransaction<StateSignatureTransaction>> unsubmittedSignatures = new ArrayList<>();

        // Initialize the list of all unsubmitted signatures with signatures for round 0 which
        // is the override state added to the ISS detector above.
        final Collection<ScopedSystemTransaction<StateSignatureTransaction>> round0Signatures =
                generateSystemTransactions(currentRound, roundData.get((int) currentRound))
                        .values();
        unsubmittedSignatures.addAll(round0Signatures);

        for (currentRound++; currentRound < roundsNonAncient; currentRound++) {
            // create signature transactions for each node for this round
            final Map<NodeId, ScopedSystemTransaction<StateSignatureTransaction>>
                    nodeIdToStateSignatureMapForCurrentRound =
                            generateSystemTransactions(currentRound, roundData.get((int) currentRound));

            unsubmittedSignatures.addAll(nodeIdToStateSignatureMapForCurrentRound.values());

            // randomly select half of the unsubmitted signatures to now submit
            final List<ScopedSystemTransaction<StateSignatureTransaction>> signaturesToSubmit =
                    selectRandomSignatures(random, unsubmittedSignatures);

            // Randomly choose to submit the transactions before the state or vice versa
            if (random.nextBoolean()) {
                issDetectorTestHelper.handleStateSignatureTransactions(signaturesToSubmit);
                issDetectorTestHelper.handleState(mockState(currentRound, selfHashes.get((int) currentRound)));
            } else {
                issDetectorTestHelper.handleState(mockState(currentRound, selfHashes.get((int) currentRound)));
                issDetectorTestHelper.handleStateSignatureTransactions(signaturesToSubmit);
            }
        }

        // Add all remaining signature events
        issDetectorTestHelper.handleStateSignatureTransactions(unsubmittedSignatures);

        assertEquals(
                expectedSelfIssCount,
                issDetectorTestHelper.getSelfIssCount(),
                "unexpected number of self ISS notifications");
        assertEquals(
                expectedCatastrophicIssCount,
                issDetectorTestHelper.getCatastrophicIssCount(),
                "unexpected number of catastrophic ISS notifications");

        final Collection<Long> observedRounds = new HashSet<>();
        issDetectorTestHelper.getIssNotificationList().forEach(notification -> {
            assertTrue(
                    observedRounds.add(notification.getRound()), "rounds should trigger a notification at most once");

            final IssNotification.IssType expectedType =
                    switch (expectedRoundStatus.get((int) notification.getRound())) {
                        case SELF_ISS -> IssNotification.IssType.SELF_ISS;
                        case CATASTROPHIC_ISS -> IssNotification.IssType.CATASTROPHIC_ISS;
                        // if there was an other-ISS, then the round should still be valid
                        case VALID -> IssNotification.IssType.OTHER_ISS;
                        default ->
                            throw new IllegalStateException(
                                    "Unexpected value: " + expectedRoundStatus.get((int) notification.getRound()));
                    };
            assertEquals(
                    expectedType,
                    notification.getIssType(),
                    "Expected status for round %d to be %s but was %s"
                            .formatted(
                                    notification.getRound(),
                                    expectedRoundStatus.get((int) notification.getRound()),
                                    notification.getIssType()));
        });
        issDetectorTestHelper
                .getIssNotificationList()
                .forEach(notification ->
                        assertMarkerFile(notification.getIssType().toString(), true));
    }

    /**
     * Handles additional rounds after an ISS occurred, but before all signatures have been submitted. Validates that
     * the ISS is detected after enough signatures are submitted, and not before.
     */
    @Test
    @DisplayName("Decide hash for catastrophic ISS")
    void decideForCatastrophicIss() {
        final Randotron random = Randotron.create();
        final PlatformContext platformContext = createDefaultPlatformContext();

        final Roster roster = RandomRosterBuilder.create(random)
                .withSize(100)
                .withWeightGenerator(WEIGHT_GENERATOR)
                .build();
        final NodeId selfId = NodeId.of(roster.rosterEntries().getFirst().nodeId());

        final IssDetector issDetector =
                new DefaultIssDetector(platformContext, roster, SemanticVersion.DEFAULT, false, DO_NOT_IGNORE_ROUNDS);
        final IssDetectorTestHelper issDetectorTestHelper = new IssDetectorTestHelper(issDetector);

        long currentRound = 0;

        // start with an initial state
        issDetectorTestHelper.overridingState(mockState(currentRound, randomHash()));
        currentRound++;

        // the round after the initial state will have a catastrophic iss
        final RoundHashValidatorTests.HashGenerationData catastrophicHashData =
                generateCatastrophicNodeHashes(random, roster, currentRound);
        final Hash selfHashForCatastrophicRound = catastrophicHashData.nodeList().stream()
                .filter(info -> info.nodeId() == selfId)
                .findFirst()
                .map(RoundHashValidatorTests.NodeHashInfo::nodeStateHash)
                .orElseThrow();

        final Map<NodeId, ScopedSystemTransaction<StateSignatureTransaction>> systemTransactions =
                generateSystemTransactions(currentRound, catastrophicHashData);
        final List<ScopedSystemTransaction<StateSignatureTransaction>> signaturesOnCatastrophicRound =
                new ArrayList<>(systemTransactions.values());

        // handle the catastrophic round, but don't submit any signatures yet, so it won't be detected
        issDetectorTestHelper.handleState(mockState(currentRound, selfHashForCatastrophicRound));

        // handle some more rounds on top of the catastrophic round without any more signatures
        for (currentRound++; currentRound < 10; currentRound++) {
            issDetectorTestHelper.handleState(mockState(currentRound, randomHash()));
        }

        final Map<Long, RosterEntry> nodesById = RosterUtils.toMap(roster);

        // submit signatures on the ISS round that represent a minority of the weight
        long submittedWeight = 0;
        final List<ScopedSystemTransaction<StateSignatureTransaction>> signaturesWithMinorityWeight = new ArrayList<>();
        for (final ScopedSystemTransaction<StateSignatureTransaction> signature : signaturesOnCatastrophicRound) {
            final long weight = nodesById.get(signature.submitterId().id()).weight();
            if (MAJORITY.isSatisfiedBy(submittedWeight + weight, RosterUtils.computeTotalWeight(roster))) {
                // If we add less than a majority then we won't be able to detect the ISS no matter what
                break;
            }
            submittedWeight += weight;
            signaturesWithMinorityWeight.add(signature);
        }

        issDetectorTestHelper.handleStateSignatureTransactions(signaturesWithMinorityWeight);
        assertEquals(
                0,
                issDetectorTestHelper.getIssNotificationList().size(),
                "there shouldn't have been enough data submitted to observe the ISS");

        // remove all signatures we just submitted from the complete list and submit then
        signaturesOnCatastrophicRound.removeAll(signaturesWithMinorityWeight);
        issDetectorTestHelper.handleStateSignatureTransactions(signaturesOnCatastrophicRound);

        assertEquals(
                1, issDetectorTestHelper.getCatastrophicIssCount(), "the catastrophic round should have caused an ISS");

        // verify marker files
        assertMarkerFile(IssType.CATASTROPHIC_ISS.toString(), true);
        assertMarkerFile(IssType.SELF_ISS.toString(), false);
        assertMarkerFile(IssType.OTHER_ISS.toString(), false);
    }

    /**
     * Generate data in an order that will cause a catastrophic ISS after the timeout, but without a supermajority of
     * signatures being on an incorrect hash.
     */
    private static List<RoundHashValidatorTests.NodeHashInfo> generateCatastrophicTimeoutIss(
            final Random random, final Roster roster, final long targetRound) {

        final List<RoundHashValidatorTests.NodeHashInfo> data = new ArrayList<>();

        // Almost add enough hashes to create a consensus hash, but not quite enough.
        // Put these at the beginning. Since we will need just a little extra weight to
        // cross the 1/3 threshold, the detection algorithm will not make a decision
        // once it reaches a >2/3 threshold

        final Hash almostConsensusHash = randomHash(random);
        long almostConsensusWeight = 0;
        for (final RosterEntry node : roster.rosterEntries()) {
            final NodeId nodeId = NodeId.of(node.nodeId());
            if (MAJORITY.isSatisfiedBy(almostConsensusWeight + node.weight(), RosterUtils.computeTotalWeight(roster))) {
                data.add(new RoundHashValidatorTests.NodeHashInfo(nodeId, randomHash(), targetRound));
            } else {
                almostConsensusWeight += node.weight();
                data.add(new RoundHashValidatorTests.NodeHashInfo(nodeId, almostConsensusHash, targetRound));
            }
        }

        return data;
    }

    /**
     * Causes a catastrophic ISS, but shifts the window before deciding on a consensus hash. Even though we don't get
     * enough signatures to "decide", there will be enough signatures to declare a catastrophic ISS when shifting the
     * window past the ISS round.
     */
    @Test
    @DisplayName("Catastrophic Shift Before Complete Test")
    void catastrophicShiftBeforeCompleteTest() {
        final Randotron random = Randotron.create();
        final PlatformContext platformContext = createDefaultPlatformContext();

        final int roundsNonAncient = platformContext
                .getConfiguration()
                .getConfigData(ConsensusConfig.class)
                .roundsNonAncient();
        final Roster roster = RandomRosterBuilder.create(random)
                .withSize(100)
                .withWeightGenerator(WEIGHT_GENERATOR)
                .build();
        final NodeId selfId = NodeId.of(roster.rosterEntries().getFirst().nodeId());

        final IssDetector issDetector =
                new DefaultIssDetector(platformContext, roster, SemanticVersion.DEFAULT, false, DO_NOT_IGNORE_ROUNDS);
        final IssDetectorTestHelper issDetectorTestHelper = new IssDetectorTestHelper(issDetector);

        long currentRound = 0;

        final List<RoundHashValidatorTests.NodeHashInfo> catastrophicData =
                generateCatastrophicTimeoutIss(random, roster, currentRound);
        final Hash selfHashForCatastrophicRound = catastrophicData.stream()
                .filter(info -> info.nodeId() == selfId)
                .findFirst()
                .map(RoundHashValidatorTests.NodeHashInfo::nodeStateHash)
                .orElseThrow();
        final RoundHashValidatorTests.HashGenerationData hashGenerationData =
                new RoundHashValidatorTests.HashGenerationData(catastrophicData, null);

        final Map<NodeId, ScopedSystemTransaction<StateSignatureTransaction>> nodeIdToSystemTransactionsMap =
                generateSystemTransactions(currentRound, hashGenerationData);
        final List<ScopedSystemTransaction<StateSignatureTransaction>> signaturesOnCatastrophicRound =
                new LinkedList<>(nodeIdToSystemTransactionsMap.values());

        final Map<Long, RosterEntry> nodesById = RosterUtils.toMap(roster);
        long submittedWeight = 0;
        final List<ScopedSystemTransaction<StateSignatureTransaction>> signaturesToSubmit = new ArrayList<>();
        for (final ScopedSystemTransaction<StateSignatureTransaction> signature : signaturesOnCatastrophicRound) {
            final long weight = nodesById.get(signature.submitterId().id()).weight();

            signaturesToSubmit.add(signature);

            // Stop once we have added >2/3. We should not have decided yet, but will
            // have gathered enough to declare a catastrophic ISS via a "catastrophic lack of data" on the hash
            submittedWeight += weight;
            if (SUPER_MAJORITY.isSatisfiedBy(submittedWeight, RosterUtils.computeTotalWeight(roster))) {
                break;
            }
        }

        // handle the catastrophic round, but it won't be decided yet, since there aren't enough signatures
        issDetectorTestHelper.handleState(mockState(currentRound, selfHashForCatastrophicRound));
        issDetectorTestHelper.handleStateSignatureTransactions(signaturesToSubmit);

        // shift through until the catastrophic round is almost ready to be cleaned up
        for (currentRound++; currentRound < roundsNonAncient; currentRound++) {
            issDetectorTestHelper.handleState(mockState(currentRound, randomHash()));
        }

        assertEquals(
                0,
                issDetectorTestHelper.getIssNotificationList().size(),
                "no ISS should be detected prior to shifting");

        // Shift the window. Even though we have not added enough data for a decision, we will have added enough to lead
        // to a catastrophic ISS when the timeout is triggered.
        issDetectorTestHelper.handleState(mockState(currentRound, randomHash()));

        assertEquals(1, issDetectorTestHelper.getIssNotificationList().size(), "shifting should have caused an ISS");
        assertEquals(
                1, issDetectorTestHelper.getCatastrophicIssCount(), "shifting should have caused a catastrophic ISS");

        // verify marker files
        assertMarkerFile(IssType.CATASTROPHIC_ISS.toString(), true);
        assertMarkerFile(IssType.SELF_ISS.toString(), false);
        assertMarkerFile(IssType.OTHER_ISS.toString(), false);
    }

    /**
     * Causes a catastrophic ISS, but shifts the window by a large amount past the ISS round. This causes the
     * catastrophic ISS to not be registered.
     */
    @Test
    @DisplayName("Big Shift Test")
    void bigShiftTest() {
        final Randotron random = Randotron.create();

        final PlatformContext platformContext = createDefaultPlatformContext();

        final int roundsNonAncient = platformContext
                .getConfiguration()
                .getConfigData(ConsensusConfig.class)
                .roundsNonAncient();
        final Roster roster = RandomRosterBuilder.create(random)
                .withSize(100)
                .withWeightGenerator(WEIGHT_GENERATOR)
                .build();
        final NodeId selfId = NodeId.of(roster.rosterEntries().getFirst().nodeId());

        final IssDetector issDetector =
                new DefaultIssDetector(platformContext, roster, SemanticVersion.DEFAULT, false, DO_NOT_IGNORE_ROUNDS);
        final IssDetectorTestHelper issDetectorTestHelper = new IssDetectorTestHelper(issDetector);

        long currentRound = 0;

        // start with an initial state
        issDetectorTestHelper.overridingState(mockState(currentRound, randomHash()));
        currentRound++;

        final List<RoundHashValidatorTests.NodeHashInfo> catastrophicData =
                generateCatastrophicTimeoutIss(random, roster, currentRound);
        final Hash selfHashForCatastrophicRound = catastrophicData.stream()
                .filter(info -> info.nodeId() == selfId)
                .findFirst()
                .map(RoundHashValidatorTests.NodeHashInfo::nodeStateHash)
                .orElseThrow();

        final RoundHashValidatorTests.HashGenerationData hashGenerationData =
                new RoundHashValidatorTests.HashGenerationData(catastrophicData, null);
        final Map<NodeId, ScopedSystemTransaction<StateSignatureTransaction>> nodeIdStateSignatureTransactionMap =
                generateSystemTransactions(currentRound, hashGenerationData);
        final List<ScopedSystemTransaction<StateSignatureTransaction>> signaturesOnCatastrophicRound =
                new LinkedList<>(nodeIdStateSignatureTransactionMap.values());

        // handle the catastrophic round, but don't submit any signatures yet, so it won't be detected
        issDetectorTestHelper.handleState(mockState(currentRound, selfHashForCatastrophicRound));

        final Map<Long, RosterEntry> nodesById = RosterUtils.toMap(roster);
        long submittedWeight = 0;
        final List<ScopedSystemTransaction<StateSignatureTransaction>> signaturesToSubmit = new ArrayList<>();
        for (final ScopedSystemTransaction<StateSignatureTransaction> signature : signaturesOnCatastrophicRound) {
            final long weight = nodesById.get(signature.submitterId().id()).weight();

            // Stop once we have added >2/3. We should not have decided yet, but will have gathered enough to declare a
            // catastrophic ISS. Meaning, no single hash can be declared the consensus hash, but enough signatures
            // disagree, by weight, that we could declare a catastrophic ISS due to a catastrophic lack of data.
            submittedWeight += weight;
            signaturesToSubmit.add(signature);
            if (SUPER_MAJORITY.isSatisfiedBy(submittedWeight + weight, RosterUtils.computeTotalWeight(roster))) {
                break;
            }
        }

        // submit the supermajority of signatures
        issDetectorTestHelper.handleStateSignatureTransactions(signaturesToSubmit);

        assertTrue(issDetectorTestHelper.getIssNotificationList().isEmpty(), "there should be no ISS notifications");

        // Shifting the window a great distance should not trigger the ISS.
        issDetectorTestHelper.overridingState(mockState(roundsNonAncient + 100L, randomHash(random)));

        assertTrue(issDetectorTestHelper.getIssNotificationList().isEmpty(), "there should be no ISS notifications");

        // verify marker files
        assertMarkerFile(IssType.CATASTROPHIC_ISS.toString(), false);
        assertMarkerFile(IssType.SELF_ISS.toString(), false);
        assertMarkerFile(IssType.OTHER_ISS.toString(), false);
    }

    /**
     * Causes a catastrophic ISS, but specifies that round to be ignored. This should cause the ISS to not be detected.
     */
    @Test
    @DisplayName("Ignored Round Test")
    void ignoredRoundTest() {
        final Randotron random = Randotron.create();

        final Roster roster = RandomRosterBuilder.create(random)
                .withSize(100)
                .withWeightGenerator(WEIGHT_GENERATOR)
                .build();

        final PlatformContext platformContext = createDefaultPlatformContext();
        final int roundsNonAncient = platformContext
                .getConfiguration()
                .getConfigData(ConsensusConfig.class)
                .roundsNonAncient();

        final IssDetector issDetector =
                new DefaultIssDetector(platformContext, roster, SemanticVersion.DEFAULT, false, 1);
        final IssDetectorTestHelper issDetectorTestHelper = new IssDetectorTestHelper(issDetector);

        long currentRound = 0;

        issDetectorTestHelper.overridingState(mockState(currentRound, randomHash()));
        currentRound++;

        final List<RoundHashValidatorTests.NodeHashInfo> catastrophicData =
                generateCatastrophicTimeoutIss(random, roster, currentRound);

        final RoundHashValidatorTests.HashGenerationData hashGenerationData =
                new RoundHashValidatorTests.HashGenerationData(catastrophicData, null);
        final Map<NodeId, ScopedSystemTransaction<StateSignatureTransaction>> nodeIdStateSignatureTransactionMap =
                generateSystemTransactions(currentRound, hashGenerationData);
        final List<ScopedSystemTransaction<StateSignatureTransaction>> signaturesOnCatastrophicRound =
                new LinkedList<>(nodeIdStateSignatureTransactionMap.values());

        // handle the round and all signatures.
        // The round has a catastrophic ISS, but should be ignored
        issDetectorTestHelper.handleState(mockState(currentRound, randomHash()));
        issDetectorTestHelper.handleStateSignatureTransactions(signaturesOnCatastrophicRound);

        // shift through some rounds, to make sure nothing unexpected happens
        for (currentRound++; currentRound <= roundsNonAncient; currentRound++) {
            issDetectorTestHelper.handleState(mockState(currentRound, randomHash()));
        }

        assertEquals(0, issDetectorTestHelper.getIssNotificationList().size(), "ISS should have been ignored");

        // verify marker files
        assertMarkerFile(IssType.CATASTROPHIC_ISS.toString(), false);
        assertMarkerFile(IssType.SELF_ISS.toString(), false);
        assertMarkerFile(IssType.OTHER_ISS.toString(), false);
    }

    private static Map<NodeId, ScopedSystemTransaction<StateSignatureTransaction>> generateSystemTransactions(
            final long roundNumber, @NonNull final RoundHashValidatorTests.HashGenerationData hashGenerationData) {

        final Map<NodeId, ScopedSystemTransaction<StateSignatureTransaction>> nodeIdToScopedSystemTransactionMap =
                new HashMap<>();
        hashGenerationData.nodeList().forEach(nodeHashInfo -> {
            final StateSignatureTransaction signatureTransaction = StateSignatureTransaction.newBuilder()
                    .round(roundNumber)
                    .signature(Bytes.EMPTY)
                    .hash(nodeHashInfo.nodeStateHash().getBytes())
                    .build();
            final ScopedSystemTransaction<StateSignatureTransaction> scopedSystemTransaction =
                    new ScopedSystemTransaction<>(nodeHashInfo.nodeId(), SemanticVersion.DEFAULT, signatureTransaction);
            nodeIdToScopedSystemTransactionMap.put(nodeHashInfo.nodeId(), scopedSystemTransaction);
        });

        return nodeIdToScopedSystemTransactionMap;
    }

    /**
     * Randomly selects ~50% of a collection of candidate signatures to submit from a round, and removes them from the
     * candidate signatures collection.
     *
     * @param random              a source of randomness
     * @param candidateSignatures the collection of candidate signatures to select from
     * @return a list of signatures to include in a round
     */
    private static List<ScopedSystemTransaction<StateSignatureTransaction>> selectRandomSignatures(
            @NonNull final Random random,
            @NonNull final Collection<ScopedSystemTransaction<StateSignatureTransaction>> candidateSignatures) {

        final List<ScopedSystemTransaction<StateSignatureTransaction>> signaturesToInclude = new ArrayList<>();
        candidateSignatures.forEach(signature -> {
            if (random.nextBoolean()) {
                signaturesToInclude.add(signature);
            }
        });
        candidateSignatures.removeAll(signaturesToInclude);

        return signaturesToInclude;
    }

    private static ReservedSignedState mockState(final long round, final Hash hash) {
        final ReservedSignedState rs = mock(ReservedSignedState.class);
        final SignedState ss = mock(SignedState.class);
        final MerkleNodeState s = mock(MerkleNodeState.class);
        when(rs.get()).thenReturn(ss);
        when(ss.getState()).thenReturn(s);
        when(ss.getRound()).thenReturn(round);
        when(s.getHash()).thenReturn(hash);
        return rs;
    }

    private RoundHashValidatorTests.HashGenerationData constructHashGenerationData(
            final Roster roster, final long round, final Hash roundHash) {
        final List<RoundHashValidatorTests.NodeHashInfo> nodeHashInfos = new ArrayList<>();
        roster.rosterEntries()
                .forEach(node -> nodeHashInfos.add(
                        new RoundHashValidatorTests.NodeHashInfo(NodeId.of(node.nodeId()), roundHash, round)));
        return new RoundHashValidatorTests.HashGenerationData(nodeHashInfos, roundHash);
    }
}
