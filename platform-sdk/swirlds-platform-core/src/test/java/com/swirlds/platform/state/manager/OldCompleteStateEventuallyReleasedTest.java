// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.state.manager;

import static com.swirlds.platform.test.fixtures.state.manager.SignatureVerificationTestUtils.buildFakeSignature;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.hiero.base.crypto.test.fixtures.CryptoRandomUtils.randomHash;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import com.hedera.hapi.node.state.roster.Roster;
import com.hedera.hapi.node.state.roster.RosterEntry;
import com.swirlds.common.context.PlatformContext;
import com.swirlds.common.test.fixtures.platform.TestPlatformContextBuilder;
import com.swirlds.platform.components.state.output.StateHasEnoughSignaturesConsumer;
import com.swirlds.platform.components.state.output.StateLacksSignaturesConsumer;
import com.swirlds.platform.state.StateSignatureCollectorTester;
import com.swirlds.platform.state.signed.ReservedSignedState;
import com.swirlds.platform.state.signed.SignedState;
import com.swirlds.platform.test.fixtures.addressbook.RandomRosterBuilder;
import com.swirlds.platform.test.fixtures.state.RandomSignedStateGenerator;
import com.swirlds.platform.test.fixtures.state.TestMerkleStateRoot;
import java.security.PublicKey;
import java.util.HashMap;
import java.util.Map;
import org.hiero.base.crypto.Hash;
import org.hiero.base.crypto.Signature;
import org.hiero.consensus.model.node.NodeId;
import org.hiero.consensus.roster.RosterUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("SignedStateManager: Old Complete State Eventually Released Test")
class OldCompleteStateEventuallyReleasedTest extends AbstractStateSignatureCollectorTest {

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
        // No state is unsigned in this test. If this method is called then the test is expected to fail.
        return ss -> stateLacksSignaturesCount.getAndIncrement();
    }

    /**
     * Called on each state as it gathers enough signatures to be complete.
     * <p>
     * This consumer is provided by the wiring layer, so it should release the resource when finished.
     */
    private StateHasEnoughSignaturesConsumer stateHasEnoughSignaturesConsumer() {
        return ss -> highestCompleteRound.accumulateAndGet(ss.getRound(), Math::max);
    }

    @AfterEach
    void tearDown() {
        RandomSignedStateGenerator.releaseAllBuiltSignedStates();
    }

    /**
     * Keep adding new states to the manager but never sign any of them (other than self signatures).
     */
    @Test
    @DisplayName("Old Complete State Eventually Released")
    void oldCompleteStateEventuallyReleased() throws InterruptedException {

        final PlatformContext platformContext = TestPlatformContextBuilder.create()
                .withConfiguration(buildStateConfig())
                .build();
        final StateSignatureCollectorTester manager = new StateSignatureCollectorBuilder(platformContext)
                .stateLacksSignaturesConsumer(stateLacksSignaturesConsumer())
                .stateHasEnoughSignaturesConsumer(stateHasEnoughSignaturesConsumer())
                .build();

        final Hash stateHash = randomHash(random);
        final Map<NodeId, Signature> signatures = new HashMap<>();
        for (final RosterEntry node : roster.rosterEntries()) {
            final PublicKey publicKey =
                    RosterUtils.fetchGossipCaCertificate(node).getPublicKey();
            signatures.put(NodeId.of(node.nodeId()), buildFakeSignature(publicKey, stateHash));
        }

        // Add a complete signed state. Eventually this will get released.
        final SignedState stateFromDisk = new RandomSignedStateGenerator(random)
                .setRoster(roster)
                .setRound(0)
                .setSignatures(signatures)
                .setState(new TestMerkleStateRoot()) // FUTURE WORK: remove this line to use TestHederaVirtualMapState
                .build();
        stateFromDisk.getState().setHash(stateHash);

        signedStates.put(0L, stateFromDisk);
        highestRound.set(0);
        manager.addReservedState(stateFromDisk.reserve("test"));

        // Create a series of signed states. Don't add any signatures. Self signatures will be automatically added.
        // Note: the multiplier should be reasonably low because each reserved state includes a virtual map (the
        // RosterMap),
        // and that consumes a lot of RAM. If the multiplier is too high (e.g. 100 as it used to be), then tests
        // will eventually run into an OOM. The multiplier of 5 seems high enough for the purpose of the test
        // and doesn't produce OOMs.
        final int count = roundsToKeepForSigning * 5;
        for (int round = 1; round < count; round++) {
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

                if (round >= roundsToKeepForSigning) {
                    assertNull(lastCompletedState, "initial state should have been released");
                } else {
                    assertSame(lastCompletedState.get(), stateFromDisk);
                }
            }
        }

        validateReservationCounts(round -> round < signedStates.size() - roundsToKeepForSigning);

        // We don't expect any further callbacks. But wait a little while longer in case there is something unexpected.
        SECONDS.sleep(1);

        validateCallbackCounts(count - roundsToKeepForSigning - 1, 0);
    }
}
