// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.test;

import static org.apache.logging.log4j.Level.WARN;
import static org.hiero.otter.fixtures.OtterAssertions.assertThat;
import static org.hiero.otter.fixtures.turtle.TurtleNodeConfiguration.SOFTWARE_VERSION;

import java.time.Duration;
import org.hiero.otter.fixtures.Network;
import org.hiero.otter.fixtures.Node;
import org.hiero.otter.fixtures.OtterTest;
import org.hiero.otter.fixtures.TestEnvironment;
import org.hiero.otter.fixtures.TimeManager;

/**
 * Test class for verifying the behavior of birth round migration when loading a freeze state from disk that did not use
 * birth round ancient mode.
 */
public class BirthRoundFreezeFromStateTest {

    private static final String STATE_NAME = "v64_freezeState_populatedGenerations";

    private static final Duration THIRTY_SECONDS = Duration.ofSeconds(30L);
    private static final Duration ONE_MINUTE = Duration.ofMinutes(1L);

    private static final String NEW_VERSION = "1.0.2";

    /**
     * Test steps:
     * <pre>
     * 1. Load a state that has already migration to birth rounds but still populates generation.
     * 2. Run the network that sets generation to 0.
     * 4. Verify events in PCES are replayed correctly and consensus advances.
     * </pre>
     *
     * @param env the test environment for this test
     * @throws InterruptedException if an operation times out
     */
    @OtterTest
    void testBirthRoundMigrationFromFreezeState(final TestEnvironment env) throws InterruptedException {

        final Network network = env.network();
        final TimeManager timeManager = env.timeManager();

        timeManager.waitFor(THIRTY_SECONDS);

        // Setup simulation
        network.addNodes(4);
        for (final Node node : network.getNodes()) {
            node.getConfiguration().set(SOFTWARE_VERSION, NEW_VERSION);
            node.copyInitialState(STATE_NAME);
        }

        // Start the network. Load the freeze state from disk that did not use birth round ancient mode.
        network.start(ONE_MINUTE);
        env.transactionGenerator().start();

        // Wait for 30 seconds
        timeManager.waitFor(THIRTY_SECONDS);

        // Validations
        assertThat(network.getConsensusResults()).haveAdvancedSinceRound(51);
        assertThat(network.getLogResults()).noMessageWithLevelHigherThan(WARN);
    }
}
