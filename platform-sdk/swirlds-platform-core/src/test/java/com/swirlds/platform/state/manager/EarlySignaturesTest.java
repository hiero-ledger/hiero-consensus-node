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
import com.swirlds.platform.components.state.output.StateHasEnoughSignaturesConsumer;
import com.swirlds.platform.components.state.output.StateLacksSignaturesConsumer;
import com.swirlds.platform.state.StateSignatureCollectorTester;
import com.swirlds.platform.test.fixtures.state.RandomSignedStateGenerator;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.hiero.consensus.model.node.NodeId;
import org.hiero.consensus.roster.RosterUtils;
import org.hiero.consensus.roster.test.fixtures.RandomRosterBuilder;
import org.hiero.consensus.state.config.StateConfig;
import org.hiero.consensus.state.signed.ReservedSignedState;
import org.hiero.consensus.state.signed.SignedState;
import org.hiero.consensus.test.fixtures.WeightGenerators;
import org.junit.jupiter.api.AfterEach;
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
            .withWeightGenerator(WeightGenerators.BALANCED_1000_PER_NODE)
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
        // Track states that are evicted incomplete when a newer complete state arrives.
        // Completing a state for round R releases all incomplete states older than R.
        // These evicted states fire the stateLacksSignatures callback.
        int expectedLacksSignaturesCount = 0;
        // Keep track of which rounds were evicted so we know to skip signing them later.
        final Set<Long> evictedRounds = new HashSet<>();

        long lastExpectedCompletedRound = -1;

        for (int round = 0; round < count; round++) {
            final SignedState signedState = states.get(round);

            signedStates.put((long) round, signedState);
            highestRound.set(round);

            manager.addReservedState(signedState.reserve("test"));

            // When an even round < futureSignatures arrives, it is complete on arrival
            // (3 early signatures). Eviction behavior releases all older incomplete
            // states. The only older incomplete state is the previous odd round (if it exists
            // and is < futureSignatures), because every even round < futureSignatures completes
            // on arrival and every odd round < futureSignatures stays incomplete.
            final boolean currentRoundShouldBeComplete = round < futureSignatures && round % 2 == 0;
            if (currentRoundShouldBeComplete && round > 0) {
                // The previous round is odd and < futureSignatures, so it was incomplete
                // and just got evicted by this complete state arriving.
                final long evictedRound = round - 1;
                if (evictedRound > 0) {
                    expectedLacksSignaturesCount++;
                    evictedRounds.add(evictedRound);
                }
            }

            // Add some signatures to one of the previous states, but only if that round needs
            // signatures and hasn't already been evicted.
            final long roundToSign = round - roundAgeToSign;

            if (roundToSign > 0 && !evictedRounds.contains(roundToSign)) {
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

            if (currentRoundShouldBeComplete) {
                expectedCompletedStateCount++;
                lastExpectedCompletedRound = round;
            } else {
                // Only update lastExpectedCompletedRound if the round actually completed
                // (not if it was evicted). Evicted rounds go through the "lacks signatures"
                // path and do NOT update the LatestCompleteStateNexus.
                if (roundToSign > 0 && !evictedRounds.contains(roundToSign)) {
                    lastExpectedCompletedRound = Math.max(lastExpectedCompletedRound, roundToSign);
                }
            }

            try (final ReservedSignedState lastCompletedState =
                    manager.getLatestSignedState("test get lastCompletedState")) {
                assertSame(
                        signedStates.get(lastExpectedCompletedRound),
                        lastCompletedState.get(),
                        "unexpected last completed state");
            }

            validateCallbackCounts(expectedLacksSignaturesCount, expectedCompletedStateCount);
        }

        // Check reservation counts.
        validateReservationCounts(round -> round < signedStates.size() - roundAgeToSign - 1);

        // We don't expect any further callbacks. But wait a little while longer in case there is something unexpected.
        SECONDS.sleep(1);

        // The total completed count is (count - roundAgeToSign) minus the evicted rounds that
        // never got a chance to complete via deferred signing.
        validateCallbackCounts(expectedLacksSignaturesCount, count - roundAgeToSign - evictedRounds.size());
    }
}
