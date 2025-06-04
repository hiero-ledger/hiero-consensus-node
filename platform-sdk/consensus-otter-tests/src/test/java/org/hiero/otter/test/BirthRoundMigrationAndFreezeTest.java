// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.test;

import static org.apache.logging.log4j.Level.WARN;
import static org.assertj.core.data.Percentage.withPercentage;
import static org.hiero.otter.fixtures.OtterAssertions.assertThat;
import static org.hiero.otter.test.BirthRoundFreezeTestUtils.assertBirthRoundsBeforeAndAfterFreeze;

import java.time.Duration;
import java.time.Instant;
import org.hiero.consensus.config.EventConfig_;
import org.hiero.otter.fixtures.Network;
import org.hiero.otter.fixtures.Node;
import org.hiero.otter.fixtures.OtterTest;
import org.hiero.otter.fixtures.TestEnvironment;
import org.hiero.otter.fixtures.TimeManager;

/**
 * Test class for verifying the behavior of birth round migration and a subsequent freeze and restart.
 */
public class BirthRoundMigrationAndFreezeTest {

    private static final Duration THIRTY_SECONDS = Duration.ofSeconds(30L);
    private static final Duration ONE_MINUTE = Duration.ofMinutes(1L);

    /**
     * Test steps:
     * <pre>
     * 1. Run a network with birth round mode disabled.
     * 2. Upgrade the network and enable birth round mode.
     * 3. Perform another upgrade.
     * </pre>
     *
     * @param env the test environment for this test
     * @throws InterruptedException if an operation times out
     */
    @OtterTest
    void testBirthRoundMigrationAndSubsequentFreeze(final TestEnvironment env) throws InterruptedException {

        final Network network = env.network();
        final TimeManager timeManager = env.timeManager();

        // Setup simulation
        network.addNodes(4);
        for (final Node node : network.getNodes()) {
            node.getConfiguration().set(EventConfig_.USE_BIRTH_ROUND_ANCIENT_THRESHOLD, false);
        }
        network.start(ONE_MINUTE);

        // Wait for 30 seconds
        timeManager.waitFor(THIRTY_SECONDS);

        // Initiate the migration
        network.freeze(ONE_MINUTE);
        network.shutdown(ONE_MINUTE);

        for (final Node node : network.getNodes()) {
            node.getConfiguration().set(EventConfig_.USE_BIRTH_ROUND_ANCIENT_THRESHOLD, true);
        }

        // Restart the network and perform birth round migration
        network.bumpVersion();
        network.resume(ONE_MINUTE);

        // Wait for 30 seconds
        timeManager.waitFor(THIRTY_SECONDS);

        // Initiate the migration
        network.freeze(ONE_MINUTE);
        network.shutdown(ONE_MINUTE);

        // Events with a created time before this time should have a maximum birth round of
        // the freeze round. Events created after this time should have a birth round greater
        // than the freeze round.
        final Instant postFreezeShutdownTime = timeManager.now();
        final long freezeRound =
                network.getNodes().getFirst().getConsensusResult().lastRoundNum();

        assertThat(network.getPcesResults()).hasMaxBirthRoundLessThanOrEqualTo(freezeRound);

        // Restart the network. The version before and after this freeze have birth rounds enabled.
        network.resume(ONE_MINUTE);

        // Wait for 30 seconds
        timeManager.waitFor(THIRTY_SECONDS);

        // Validations
        assertThat(network.getLogResults()).noMessageWithLevelHigherThan(WARN);

        assertThat(network.getConsensusResults())
                .haveAdvancedSinceRound(freezeRound)
                .haveEqualRoundsIgnoringLast(withPercentage(5));

        assertBirthRoundsBeforeAndAfterFreeze(
                network.getNodes().getFirst().getConsensusResult().consensusRounds(),
                postFreezeShutdownTime,
                freezeRound);
    }
}
