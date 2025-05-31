// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.test;

import static org.apache.logging.log4j.Level.WARN;
import static org.assertj.core.data.Percentage.withPercentage;
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

    private static final Duration THIRTY_SECONDS = Duration.ofSeconds(30L);
    private static final Duration ONE_MINUTE = Duration.ofMinutes(1L);

    private static final String NEW_VERSION = "1.0.2";

    /**
     * Test steps:
     * <pre>
     * 1. Run the network with birth round mode enabled, starting from a state with birth round mode disabled.
     * 4. Verify proper birth rounds in events created before and after the upgrade.
     * </pre>
     *
     * @param env the test environment for this test
     * @throws InterruptedException if an operation times out
     */
    @OtterTest
    void testBirthRoundMigrationFromFreezeState(final TestEnvironment env) throws InterruptedException {

        final Network network = env.network();
        final TimeManager timeManager = env.timeManager();

        // Setup simulation
        network.addNodes(4);
        for (final Node node : network.getNodes()) {
            node.getConfiguration().set(SOFTWARE_VERSION, NEW_VERSION);
            node.copyInitialState("birthRoundMigrationFromFreezeState");
        }

        // Start the network. Load the freeze state from disk that did not use birth round ancient mode.
        network.start(ONE_MINUTE);
        env.generator().start();

        // Wait for 30 seconds
        timeManager.waitFor(THIRTY_SECONDS);

        // Validations
        assertThat(network.getLogResults()).noMessageWithLevelHigherThan(WARN);

        assertThat(network.getConsensusResults()).hasAdvancedSince(51).hasEqualRoundsIgnoringLast(withPercentage(5));
    }
}
