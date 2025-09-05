// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.state.manager;

import static com.swirlds.platform.test.fixtures.state.manager.SignatureVerificationTestUtils.buildFakeSignature;
import static org.hiero.base.crypto.test.fixtures.CryptoRandomUtils.randomHash;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.hedera.hapi.node.state.roster.Roster;
import com.hedera.hapi.node.state.roster.RosterEntry;
import com.swirlds.common.context.PlatformContext;
import com.swirlds.common.test.fixtures.WeightGenerators;
import com.swirlds.common.test.fixtures.platform.TestPlatformContextBuilder;
import com.swirlds.platform.components.state.output.StateHasEnoughSignaturesConsumer;
import com.swirlds.platform.components.state.output.StateLacksSignaturesConsumer;
import com.swirlds.platform.state.StateSignatureCollectorTester;
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

@DisplayName("SignedStateManager: Add Incomplete State Test")
class AddIncompleteStateTest extends AbstractStateSignatureCollectorTest {

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
            highestCompleteRound.accumulateAndGet(ss.getRound(), Math::max);
            stateHasEnoughSignaturesCount.getAndIncrement();
        };
    }

    @AfterEach
    void tearDown() {
        RandomSignedStateGenerator.releaseAllBuiltSignedStates();
    }

    @Test
    @DisplayName("Add Incomplete State Test")
    void addIncompleteStateTest() {

        final PlatformContext platformContext = TestPlatformContextBuilder.create()
                .withConfiguration(buildStateConfig())
                .build();
        final StateSignatureCollectorTester manager = new StateSignatureCollectorBuilder(platformContext)
                .stateLacksSignaturesConsumer(stateLacksSignaturesConsumer())
                .stateHasEnoughSignaturesConsumer(stateHasEnoughSignaturesConsumer())
                .build();

        // Simulate a restart (i.e. loading a state from disk)
        final Hash signedHash = randomHash(random);
        final Map<NodeId, Signature> signatures = new HashMap<>();
        for (final RosterEntry node : roster.rosterEntries()) {
            final PublicKey publicKey =
                    RosterUtils.fetchGossipCaCertificate(node).getPublicKey();
            final Signature signature = buildFakeSignature(publicKey, signedHash);
            signatures.put(NodeId.of(node.nodeId()), signature);
        }

        final long firstRound = 50;
        final SignedState stateFromDisk = new RandomSignedStateGenerator(random)
                .setRoster(roster)
                .setRound(firstRound)
                .setSignatures(signatures)
                .setState(new TestMerkleStateRoot()) // FUTURE WORK: remove this line to use TestHederaVirtualMapState
                .build();

        // This is intentionally a different hash than the signed hash!
        final Hash stateHash = randomHash();
        stateFromDisk.getState().setHash(stateHash);

        // The manager should store this state but not assigned it to the last complete signed state
        manager.addReservedState(stateFromDisk.reserve("test"));

        assertNull(manager.getLatestSignedState("test"));
        assertEquals(-1, manager.getLastCompleteRound());

        assertEquals(
                1,
                stateFromDisk.getReservationCount(),
                "One reservation expected, for collecting signatures, since the hash changed");

        validateCallbackCounts(0, 0);
    }
}
