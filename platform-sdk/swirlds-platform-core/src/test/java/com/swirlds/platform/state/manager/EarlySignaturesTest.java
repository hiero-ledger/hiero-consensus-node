// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.state.manager;

import static com.swirlds.platform.test.fixtures.state.manager.SignatureVerificationTestUtils.buildFakeSignatureBytes;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.jupiter.api.Assertions.assertSame;

import com.hedera.hapi.node.state.roster.Roster;
import com.hedera.hapi.node.state.roster.RosterEntry;
import com.hedera.hapi.platform.event.StateSignatureTransaction;
import com.swirlds.common.context.PlatformContext;
import com.swirlds.common.test.fixtures.platform.TestPlatformContextBuilder;
import com.swirlds.merkledb.MerkleDb;
import com.swirlds.platform.components.state.output.StateHasEnoughSignaturesConsumer;
import com.swirlds.platform.components.state.output.StateLacksSignaturesConsumer;
import com.swirlds.platform.config.StateConfig;
import com.swirlds.platform.roster.RosterUtils;
import com.swirlds.platform.state.StateSignatureCollectorTester;
import com.swirlds.platform.state.signed.ReservedSignedState;
import com.swirlds.platform.state.signed.SignedState;
import com.swirlds.platform.test.fixtures.addressbook.RandomRosterBuilder;
import com.swirlds.platform.test.fixtures.addressbook.RandomRosterBuilder.WeightDistributionStrategy;
import com.swirlds.platform.test.fixtures.state.RandomSignedStateGenerator;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import org.hiero.consensus.model.node.NodeId;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("SignedStateManager: Early Signatures Test")
public class EarlySignaturesTest extends AbstractStateSignatureCollectorTest {

    // Note: this unit test was long and complex, so it was split into its own class.
    // As such, this test was designed differently than it would be designed if it were sharing
    // the class file with other tests.
    // DO NOT ADD ADDITIONAL UNIT TESTS TO THIS CLASS!

    private final int roundAgeToSign = 3;

    private final Roster roster = RandomRosterBuilder.create(random)
            .withSize(4)
            .withWeightDistributionStrategy(WeightDistributionStrategy.BALANCED)
            .build();

    /**
     * Called on each state as it gets too old without collecting enough signatures.
     * <p>
     * This consumer is provided by the wiring layer, so it should release the resource when finished.
     */
    private StateLacksSignaturesConsumer stateLacksSignaturesConsumer() {
        // No state is unsigned in this test. If this method is called then the test is expected to fail.
        return ss -> stateLacksSignaturesCount.getAndIncrement();
    }

    /**
     * Called on each state as it gathers enough signatures to be complete.
     * <p>
     * This consumer is provided by the wiring layer, so it should release the resource when finished.
     */
    private StateHasEnoughSignaturesConsumer stateHasEnoughSignaturesConsumer() {
        return ss -> {
            highestCompleteRound.accumulateAndGet(ss.getRound(), Math::max);
            stateHasEnoughSignaturesCount.getAndIncrement();
        };
    }

    @BeforeEach
    void setUp() {
        MerkleDb.resetDefaultInstancePath();
    }

    @AfterEach
    void tearDown() {
        RandomSignedStateGenerator.releaseAllBuiltSignedStates();
    }

    @Test
    @DisplayName("Early Signatures Test")
    void earlySignaturesTest() throws InterruptedException {
        final int count = 100;
        final PlatformContext platformContext = TestPlatformContextBuilder.create()
                .withConfiguration(buildStateConfig())
                .build();
        final int futureSignatures = platformContext
                .getConfiguration()
                .getConfigData(StateConfig.class)
                .maxAgeOfFutureStateSignatures();
        final StateSignatureCollectorTester manager = new StateSignatureCollectorBuilder(platformContext)
                .stateLacksSignaturesConsumer(stateLacksSignaturesConsumer())
                .stateHasEnoughSignaturesConsumer(stateHasEnoughSignaturesConsumer())
                .build();

        // Create a series of signed states.
        final List<SignedState> states = new ArrayList<>();
        for (int round = 0; round < count; round++) {
            MerkleDb.resetDefaultInstancePath();
            final SignedState signedState = new RandomSignedStateGenerator(random)
                    .setRoster(roster)
                    .setRound(round)
                    .setSignatures(new HashMap<>())
                    .build();
            states.add(signedState);
        }

        // send out signatures super early. Many will be rejected.
        for (int round = 0; round < count; round++) {
            // All node 0 and 2 signatures are sent very early.
            final RosterEntry node0 = roster.rosterEntries().get(0);
            final RosterEntry node2 = roster.rosterEntries().get(2);

            manager.handlePreconsensusSignatureTransaction(
                    NodeId.of(node0.nodeId()),
                    StateSignatureTransaction.newBuilder()
                            .round(round)
                            .signature(buildFakeSignatureBytes(
                                    RosterUtils.fetchGossipCaCertificate(node0).getPublicKey(),
                                    states.get(round).getState().getHash()))
                            .hash(states.get(round).getState().getHash().getBytes())
                            .build());
            manager.handlePreconsensusSignatureTransaction(
                    NodeId.of(node2.nodeId()),
                    StateSignatureTransaction.newBuilder()
                            .round(round)
                            .signature(buildFakeSignatureBytes(
                                    RosterUtils.fetchGossipCaCertificate(node2).getPublicKey(),
                                    states.get(round).getState().getHash()))
                            .hash(states.get(round).getState().getHash().getBytes())
                            .build());

            // Even numbered rounds have 3 sent very early.
            final RosterEntry node3 = roster.rosterEntries().get(3);
            if (round % 2 == 0) {
                manager.handlePreconsensusSignatureTransaction(
                        NodeId.of(node3.nodeId()),
                        StateSignatureTransaction.newBuilder()
                                .round(round)
                                .signature(buildFakeSignatureBytes(
                                        RosterUtils.fetchGossipCaCertificate(node3)
                                                .getPublicKey(),
                                        states.get(round).getState().getHash()))
                                .hash(states.get(round).getState().getHash().getBytes())
                                .build());
            }
        }

        int expectedCompletedStateCount = 0;

        long lastExpectedCompletedRound = -1;

        for (int round = 0; round < count; round++) {
            final SignedState signedState = states.get(round);

            signedStates.put((long) round, signedState);
            highestRound.set(round);

            manager.addReservedState(signedState.reserve("test"));

            // Add some signatures to one of the previous states, but only if that round need signatures.
            final long roundToSign = round - roundAgeToSign;

            if (roundToSign > 0) {
                if (roundToSign >= futureSignatures) {
                    addSignature(
                            manager,
                            roundToSign,
                            NodeId.of(roster.rosterEntries().get(0).nodeId()));
                    addSignature(
                            manager,
                            roundToSign,
                            NodeId.of(roster.rosterEntries().get(1).nodeId()));
                    addSignature(
                            manager,
                            roundToSign,
                            NodeId.of(roster.rosterEntries().get(2).nodeId()));
                    expectedCompletedStateCount++;
                } else if (roundToSign % 2 != 0) {
                    addSignature(
                            manager,
                            roundToSign,
                            NodeId.of(roster.rosterEntries().get(0).nodeId()));
                    addSignature(
                            manager,
                            roundToSign,
                            NodeId.of(roster.rosterEntries().get(1).nodeId()));
                    expectedCompletedStateCount++;
                }
            }

            final boolean currentRoundShouldBeComplete = round < futureSignatures && round % 2 == 0;
            if (currentRoundShouldBeComplete) {
                expectedCompletedStateCount++;
                lastExpectedCompletedRound = round;
            } else {
                lastExpectedCompletedRound = Math.max(lastExpectedCompletedRound, roundToSign);
            }

            try (final ReservedSignedState lastCompletedState =
                    manager.getLatestSignedState("test get lastCompletedState")) {
                assertSame(
                        signedStates.get(lastExpectedCompletedRound),
                        lastCompletedState.get(),
                        "unexpected last completed state");
            }

            validateCallbackCounts(0, expectedCompletedStateCount);
        }

        // Check reservation counts.
        validateReservationCounts(round -> round < signedStates.size() - roundAgeToSign - 1);

        // We don't expect any further callbacks. But wait a little while longer in case there is something unexpected.
        SECONDS.sleep(1);

        validateCallbackCounts(0, count - roundAgeToSign);
    }
}
