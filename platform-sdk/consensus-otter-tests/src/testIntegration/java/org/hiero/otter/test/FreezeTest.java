// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.test;

import static org.assertj.core.api.Assertions.fail;
import static org.hiero.consensus.model.status.PlatformStatus.ACTIVE;
import static org.hiero.consensus.model.status.PlatformStatus.CHECKING;
import static org.hiero.consensus.model.status.PlatformStatus.OBSERVING;
import static org.hiero.consensus.model.status.PlatformStatus.REPLAYING_EVENTS;
import static org.hiero.otter.fixtures.OtterAssertions.assertThat;
import static org.hiero.otter.fixtures.assertions.StatusProgressionStep.target;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Duration;
import java.time.Instant;
import org.hiero.otter.fixtures.Network;
import org.hiero.otter.fixtures.OtterTest;
import org.hiero.otter.fixtures.TestEnvironment;
import org.hiero.otter.fixtures.TimeManager;
import org.hiero.otter.fixtures.result.MultipleNodeConsensusResults;
import org.hiero.otter.fixtures.result.MultipleNodePlatformStatusResults;
import org.junit.jupiter.api.RepeatedTest;

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
        network.start();

        final MultipleNodeConsensusResults networkConsensusResults = network.newConsensusResults();
        final MultipleNodePlatformStatusResults networkPlatformStatusResults = network.newPlatformStatusResults();

        for (int i = 0; i < NUM_FREEZE_ITERATIONS; i++) {
            network.freeze();
            final long freezeRound =
                    network.nodes().getFirst().newConsensusResult().lastRoundNum();
            assertThat(network.newConsensusResults())
                    .haveEqualCommonRounds()
                    .haveConsistentRounds()
                    .haveLastRoundNum(freezeRound);

            network.shutdown();
            final Instant postFreezeShutdownTime = timeManager.now();
            assertThat(network.newPcesResults())
                    .haveMaxBirthRoundLessThanOrEqualTo(freezeRound)
                    .haveBirthRoundSplit(postFreezeShutdownTime, freezeRound);
            assertThat(networkPlatformStatusResults).haveSteps(
                    target(ACTIVE).requiringInterim(REPLAYING_EVENTS, OBSERVING, CHECKING));
            network.start();

            timeManager.waitForCondition(
                    () -> networkConsensusResults.allNodesAdvancedToRound(freezeRound + 20),
                    Duration.ofSeconds(120),
                    "At least one node failed to advance 20 rounds past the freeze round in the time allowed");

            assertThat(networkConsensusResults).haveBirthRoundSplit(postFreezeShutdownTime, freezeRound);
            networkConsensusResults.clear();
        }

        network.freeze();
        network.shutdown();

        assertThat(network.newLogResults()).haveNoErrorLevelMessages();
        assertThat(network.newReconnectResults()).haveNoReconnects();
        assertThat(network.newEventStreamResults()).haveEqualFiles();
        assertThat(network.newMarkerFileResults()).haveNoMarkerFiles();
    }
}
