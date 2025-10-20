// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.test;

import static org.hiero.otter.fixtures.OtterAssertions.assertContinuouslyThat;
import static org.hiero.otter.fixtures.OtterAssertions.assertThat;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Duration;
import java.time.Instant;
import org.hiero.otter.fixtures.Network;
import org.hiero.otter.fixtures.OtterTest;
import org.hiero.otter.fixtures.TestEnvironment;
import org.hiero.otter.fixtures.TimeManager;
import org.hiero.otter.fixtures.result.MultipleNodeConsensusResults;

/**
 * Tests that multiple freezes of the network work correctly, and that all nodes are able to freeze, restart, and
 * continue reaching consensus.
 */
public class FreezeTest {

    /** The number of freeze iterations to perform in the test. */
    private static final int NUM_FREEZE_ITERATIONS = 3;

    /**
     * Tests that multiple freezes of the network work correctly, and that all nodes are able to freeze, restart, and
     * continue reaching consensus.
     *
     * @param env the test environment
     */
    @OtterTest
    void testMultipleFreezes(@NonNull final TestEnvironment env) {
        final Network network = env.network();
        final TimeManager timeManager = env.timeManager();

        network.addNodes(4);

        // Setup continuous assertions
        assertContinuouslyThat(network.newLogResults()).haveNoErrorLevelMessages();
        assertContinuouslyThat(network.newReconnectResults()).doNotAttemptToReconnect();
        assertContinuouslyThat(network.newConsensusResults())
                .haveEqualCommonRounds()
                .haveConsistentRounds();
        assertContinuouslyThat(network.newMarkerFileResults()).haveNoMarkerFiles();

        network.start();

        final MultipleNodeConsensusResults networkConsensusResults = network.newConsensusResults();

        for (int i = 0; i < NUM_FREEZE_ITERATIONS; i++) {
            network.freeze();
            final long freezeRound =
                    network.nodes().getFirst().newConsensusResult().lastRoundNum();
            final Instant postFreezeTime = timeManager.now();
            assertThat(network.newConsensusResults()).haveLastRoundNum(freezeRound);

            network.shutdown();
            assertThat(network.newPcesResults()).haveMaxBirthRoundLessThanOrEqualTo(freezeRound);
            network.start();

            timeManager.waitForCondition(
                    () -> networkConsensusResults.allNodesAdvancedToRound(freezeRound + 20),
                    Duration.ofSeconds(120),
                    "At least one node failed to advance 20 rounds past the freeze round in the time allowed");

            assertThat(networkConsensusResults).haveBirthRoundSplit(postFreezeTime, freezeRound);
            networkConsensusResults.clear();
        }

        assertThat(network.newEventStreamResults()).haveEqualFiles();
    }
}
