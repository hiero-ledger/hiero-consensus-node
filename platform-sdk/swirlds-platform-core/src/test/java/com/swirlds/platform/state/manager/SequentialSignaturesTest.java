// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.state.manager;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import com.hedera.hapi.node.state.roster.Roster;
import com.swirlds.common.context.PlatformContext;
import com.swirlds.common.test.fixtures.WeightGenerators;
import com.swirlds.common.test.fixtures.platform.TestPlatformContextBuilder;
import com.swirlds.merkledb.MerkleDb;
import com.swirlds.platform.components.state.output.StateHasEnoughSignaturesConsumer;
import com.swirlds.platform.components.state.output.StateLacksSignaturesConsumer;
import com.swirlds.platform.state.StateSignatureCollectorTester;
import com.swirlds.platform.state.signed.ReservedSignedState;
import com.swirlds.platform.state.signed.SignedState;
import com.swirlds.platform.test.fixtures.addressbook.RandomRosterBuilder;
import com.swirlds.platform.test.fixtures.state.RandomSignedStateGenerator;
import java.util.HashMap;
import org.hiero.consensus.model.node.NodeId;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("SignedStateManager: Sequential Signatures Test")
public class SequentialSignaturesTest extends AbstractStateSignatureCollectorTest {

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
            assertEquals(highestRound.get() - roundAgeToSign, ss.getRound(), "unexpected round completed");
            stateHasEnoughSignaturesCount.getAndIncrement();
            highestCompleteRound.accumulateAndGet(ss.getRound(), Math::max);
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
    @DisplayName("Sequential Signatures Test")
    void sequentialSignaturesTest() throws InterruptedException {
        this.roundsToKeepAfterSigning = 4;
        final PlatformContext platformContext = TestPlatformContextBuilder.create()
                .withConfiguration(buildStateConfig())
                .build();
        final StateSignatureCollectorTester manager = new StateSignatureCollectorBuilder(platformContext)
                .stateLacksSignaturesConsumer(stateLacksSignaturesConsumer())
                .stateHasEnoughSignaturesConsumer(stateHasEnoughSignaturesConsumer())
                .build();

        // Create a series of signed states.
        final int count = 100;
        for (int round = 0; round < count; round++) {
            MerkleDb.resetDefaultInstancePath();
            final SignedState signedState = new RandomSignedStateGenerator(random)
                    .setRoster(roster)
                    .setRound(round)
                    .setSignatures(new HashMap<>())
                    .build();

            signedStates.put((long) round, signedState);
            highestRound.set(round);

            manager.addReservedState(signedState.reserve("test"));

            // Add some signatures to one of the previous states
            final long roundToSign = round - roundAgeToSign;
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
            if (random.nextBoolean()) {
                addSignature(
                        manager,
                        roundToSign,
                        NodeId.of(roster.rosterEntries().get(1).nodeId()));
                addSignature(
                        manager,
                        roundToSign,
                        NodeId.of(roster.rosterEntries().get(1).nodeId()));
            }

            try (final ReservedSignedState lastCompletedState = manager.getLatestSignedState("test")) {
                if (roundToSign >= 0) {
                    assertSame(
                            signedStates.get(roundToSign), lastCompletedState.get(), "unexpected last completed state");
                } else {
                    assertNull(lastCompletedState, "no states should be completed yet");
                }
            }

            validateCallbackCounts(0, Math.max(0, round - roundAgeToSign + 1));
        }

        // Check reservation counts.
        validateReservationCounts(round -> round < signedStates.size() - roundsToKeepAfterSigning - 1);

        // We don't expect any further callbacks. But wait a little while longer in case there is something unexpected.
        SECONDS.sleep(1);

        validateCallbackCounts(0, count - roundAgeToSign);
    }
}
