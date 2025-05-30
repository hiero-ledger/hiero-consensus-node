// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.test;

import static org.apache.logging.log4j.Level.WARN;
import static org.assertj.core.data.Percentage.withPercentage;
import static org.hiero.otter.fixtures.OtterAssertions.assertThat;
import static org.hiero.otter.fixtures.turtle.TurtleNodeConfiguration.SOFTWARE_VERSION;
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
 * Test class for verifying the behavior of birth rounds before and after a freeze in a network
 * that never used generation ancient mode.
 */
public class BirthRoundFreezeTest {

    private static final Duration THIRTY_SECONDS = Duration.ofSeconds(30L);
    private static final Duration ONE_MINUTE = Duration.ofMinutes(1L);

    private static final String OLD_VERSION = "1.0.0";
    private static final String NEW_VERSION = "1.0.1";

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
     * @throws InterruptedException if an operation times out
     */
    @OtterTest
    void testFreezeInBirthRoundMode(final TestEnvironment env) throws InterruptedException {

        final Network network = env.network();
        final TimeManager timeManager = env.timeManager();

        // Setup simulation
        network.addNodes(4);
        for (final Node node : network.getNodes()) {
            node.getConfiguration().set(EventConfig_.USE_BIRTH_ROUND_ANCIENT_THRESHOLD, true);
            node.getConfiguration().set(SOFTWARE_VERSION, OLD_VERSION);
        }
        network.start(ONE_MINUTE);
        env.generator().start();

        // Wait for 30 seconds
        timeManager.waitFor(THIRTY_SECONDS);

        // Initiate the migration
        env.generator().stop();
        network.prepareUpgrade(ONE_MINUTE);

        // Events with a created time before this time should have a maximum birth round of
        // the freeze round. Events created after this time should have a birth round greater
        // than the freeze round.
        final Instant postFreezeShutdownTime = timeManager.now();
        final long freezeRound =
                network.getNodes().getFirst().getConsensusResult().lastRoundNum();

        for (final Node node : network.getNodes()) {
            node.getConfiguration().set(SOFTWARE_VERSION, NEW_VERSION);
        }

        // Restart the network. The version before and after this freeze have birth rounds enabled.
        network.resume(ONE_MINUTE);
        env.generator().start();

        // Wait for 30 seconds
        timeManager.waitFor(THIRTY_SECONDS);

        // Validations
        assertThat(network.getLogResults()).noMessageWithLevelHigherThan(WARN);

        assertThat(network.getConsensusResult())
                .hasAdvancedSince(freezeRound)
                .hasEqualRoundsIgnoringLast(withPercentage(5));

        assertBirthRoundsBeforeAndAfterFreeze(
                network.getNodes().getFirst().getConsensusResult().consensusRounds(),
                postFreezeShutdownTime,
                freezeRound);
    }
}
