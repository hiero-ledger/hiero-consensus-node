// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.test;

import static org.apache.logging.log4j.Level.WARN;
import static org.hiero.otter.fixtures.OtterAssertions.assertThat;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Duration;
import java.time.Instant;
import org.hiero.otter.fixtures.Network;
import org.hiero.otter.fixtures.OtterTest;
import org.hiero.otter.fixtures.TestEnvironment;
import org.hiero.otter.fixtures.TimeManager;

/**
 * Test class for verifying the behavior of birth rounds before and after a freeze in a network that never used
 * generation ancient mode.
 */
public class BirthRoundFreezeTest {

    private static final Duration THIRTY_SECONDS = Duration.ofSeconds(30L);

    /**
     * Test steps:
     * <pre>
     * 1. Run a network with birth round mode enabled.
     * 2. Freeze and upgrade the network.
     * 3. Run the network with birth round mode enabled again.
     * 4. Verify proper birth rounds in events created before and after the upgrade.
     * </pre>
     *
     * @param env the test environment for this test
     */
    @OtterTest
    void testFreezeInBirthRoundMode(@NonNull final TestEnvironment env) {

        final Network network = env.network();
        final TimeManager timeManager = env.timeManager();

        // Setup simulation
        network.addNodes(4);
        network.start();

        // Wait for 30 seconds
        timeManager.waitFor(THIRTY_SECONDS);

        // Initiate the migration
        network.freeze();
        network.shutdown();

        // Events with a created time before this time should have a maximum birth round of
        // the freeze round. Events created after this time should have a birth round greater
        // than the freeze round.
        final Instant postFreezeShutdownTime = timeManager.now();
        final long freezeRound = network.nodes().getFirst().newConsensusResult().lastRoundNum();

        assertThat(network.newPcesResults()).haveMaxBirthRoundLessThanOrEqualTo(freezeRound);

        // Restart the network. The version before and after this freeze have birth rounds enabled.
        network.bumpConfigVersion();
        network.start();

        // Wait for 30 seconds
        timeManager.waitFor(THIRTY_SECONDS);

        // Validations
        assertThat(network.newLogResults()).haveNoMessagesWithLevelHigherThan(WARN);

        assertThat(network.newConsensusResults())
                .haveAdvancedSinceRound(freezeRound)
                .haveEqualCommonRounds()
                .haveBirthRoundSplit(postFreezeShutdownTime, freezeRound);

        assertThat(network.newPcesResults()).haveBirthRoundSplit(postFreezeShutdownTime, freezeRound);
    }
}
