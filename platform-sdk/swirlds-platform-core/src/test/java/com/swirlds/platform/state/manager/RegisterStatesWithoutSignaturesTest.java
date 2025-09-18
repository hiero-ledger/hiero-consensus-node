// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.state.manager;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import com.hedera.hapi.node.state.roster.Roster;
import com.swirlds.common.context.PlatformContext;
import com.swirlds.common.test.fixtures.platform.TestPlatformContextBuilder;
import com.swirlds.platform.components.state.output.StateHasEnoughSignaturesConsumer;
import com.swirlds.platform.components.state.output.StateLacksSignaturesConsumer;
import com.swirlds.platform.state.StateSignatureCollectorTester;
import com.swirlds.platform.state.signed.ReservedSignedState;
import com.swirlds.platform.state.signed.SignedState;
import com.swirlds.platform.test.fixtures.addressbook.RandomRosterBuilder;
import com.swirlds.platform.test.fixtures.state.RandomSignedStateGenerator;
import java.util.HashMap;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("SignedStateManager: Register States Without Signatures Test")
public class RegisterStatesWithoutSignaturesTest extends AbstractStateSignatureCollectorTest {

    // Note: this unit test was long and complex, so it was split into its own class.
    // As such, this test was designed differently than it would be designed if it were sharing
    // the class file with other tests.
    // DO NOT ADD ADDITIONAL UNIT TESTS TO THIS CLASS!

    private final Roster roster = RandomRosterBuilder.create(random).withSize(4).build();

    /**
     * Called on each state as it gets too old without collecting enough signatures.
     * <p>
     * This consumer is provided by the wiring layer, so it should release the resource when finished.
     */
    private StateLacksSignaturesConsumer stateLacksSignaturesConsumer() {
        return ss -> {
            stateLacksSignaturesCount.getAndIncrement();

            assertEquals(highestRound.get() - roundsToKeepForSigning, ss.getRound(), "unexpected round retired");
            assertSame(
                    signedStates.get(highestRound.get() - roundsToKeepForSigning), ss, "unexpected state was retired");
        };
    }

    /**
     * Called on each state as it gathers enough signatures to be complete.
     * <p>
     * This consumer is provided by the wiring layer, so it should release the resource when finished.
     */
    private StateHasEnoughSignaturesConsumer stateHasEnoughSignaturesConsumer() {
        return ss -> {
            stateHasEnoughSignaturesCount.getAndIncrement();
            highestCompleteRound.accumulateAndGet(ss.getRound(), Math::max);
        };
    }

    @AfterEach
    void tearDown() {
        RandomSignedStateGenerator.releaseAllBuiltSignedStates();
    }

    /**
     * Keep adding new states to the manager but never sign any of them (other than self signatures).
     */
    @Test
    @DisplayName("Register States Without Signatures")
    void registerStatesWithoutSignatures() throws InterruptedException {
        final PlatformContext platformContext = TestPlatformContextBuilder.create()
                .withConfiguration(buildStateConfig())
                .build();
        final StateSignatureCollectorTester manager = new StateSignatureCollectorBuilder(platformContext)
                .stateLacksSignaturesConsumer(stateLacksSignaturesConsumer())
                .stateHasEnoughSignaturesConsumer(stateHasEnoughSignaturesConsumer())
                .build();

        // Create a series of signed states. Don't add any signatures. Self signatures will be automatically added.
        final int count = 100;
        for (int round = 0; round < count; round++) {
            final SignedState signedState = new RandomSignedStateGenerator(random)
                    .setRoster(roster)
                    .setCalculateHash(true)
                    .setRound(round)
                    .setSignatures(new HashMap<>())
                    .build();

            signedStates.put((long) round, signedState);
            highestRound.set(round);

            manager.addReservedState(signedState.reserve("test"));

            try (final ReservedSignedState lastCompletedState = manager.getLatestSignedState("test")) {
                assertNull(lastCompletedState, "no states should be completed in this test");
            }

            final int expectedUnsignedStates = Math.max(0, round - roundsToKeepForSigning + 1);

            validateCallbackCounts(expectedUnsignedStates, 0);
        }

        validateReservationCounts(round -> round < signedStates.size() - roundsToKeepForSigning);

        // We don't expect any further callbacks. But wait a little while longer in case there is something
        // unexpected.
        SECONDS.sleep(1);

        validateCallbackCounts(count - roundsToKeepForSigning, 0);
    }
}
