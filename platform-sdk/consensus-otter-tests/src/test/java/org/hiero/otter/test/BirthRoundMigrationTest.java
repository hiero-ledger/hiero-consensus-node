// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.test;

import static org.apache.logging.log4j.Level.WARN;
import static org.assertj.core.data.Percentage.withPercentage;
import static org.hiero.consensus.model.status.PlatformStatus.ACTIVE;
import static org.hiero.consensus.model.status.PlatformStatus.CHECKING;
import static org.hiero.consensus.model.status.PlatformStatus.FREEZE_COMPLETE;
import static org.hiero.consensus.model.status.PlatformStatus.FREEZING;
import static org.hiero.consensus.model.status.PlatformStatus.OBSERVING;
import static org.hiero.consensus.model.status.PlatformStatus.REPLAYING_EVENTS;
import static org.hiero.otter.fixtures.OtterAssertions.assertThat;
import static org.hiero.otter.fixtures.assertions.StatusProgressionStep.target;
import static org.hiero.otter.fixtures.turtle.TurtleNodeConfiguration.SOFTWARE_VERSION;

import java.time.Duration;
import org.hiero.consensus.config.EventConfig_;
import org.hiero.otter.fixtures.Network;
import org.hiero.otter.fixtures.Node;
import org.hiero.otter.fixtures.OtterTest;
import org.hiero.otter.fixtures.TestEnvironment;
import org.hiero.otter.fixtures.TimeManager;

class BirthRoundMigrationTest {

    private static final Duration THIRTY_SECONDS = Duration.ofSeconds(30L);
    private static final Duration ONE_MINUTE = Duration.ofMinutes(1L);

    private static final String OLD_VERSION = "1.0.0";
    private static final String NEW_VERSION = "1.0.1";

    @OtterTest
    void testBirthRoundMigration(final TestEnvironment env) throws InterruptedException {
        final Network network = env.network();
        final TimeManager timeManager = env.timeManager();

        // Setup simulation
        network.addNodes(4);
        for (final Node node : network.getNodes()) {
            node.getConfiguration().set(SOFTWARE_VERSION, OLD_VERSION);
        }
        network.start(ONE_MINUTE);
        env.generator().start();

        // Wait for 30 seconds
        timeManager.waitFor(THIRTY_SECONDS);

        // Initiate the migration
        env.generator().stop();
        network.prepareUpgrade(ONE_MINUTE);

        // Before migrating to birth round, all events should have a birth round of 1L
        assertThat(network.getPcesResults()).hasAllBirthRoundsEqualTo(1L);

        // store the consensus round
        final long freezeRound =
                network.getNodes().getFirst().getConsensusResult().lastRoundNum();

        // check that all nodes froze at the same round
        assertThat(network.getConsensusResult()).hasLastRoundNum(freezeRound);

        // update the configuration
        for (final Node node : network.getNodes()) {
            node.getConfiguration()
                    .set(EventConfig_.USE_BIRTH_ROUND_ANCIENT_THRESHOLD, true)
                    .set(SOFTWARE_VERSION, NEW_VERSION);
        }

        // restart the network
        network.resume(ONE_MINUTE);
        env.generator().start();

        // Wait for 30 seconds
        timeManager.waitFor(THIRTY_SECONDS);

        // Assert the results
        assertThat(network.getLogResults()).noMessageWithLevelHigherThan(WARN);
        assertThat(network.getConsensusResult())
                .hasAdvancedSince(freezeRound)
                .hasEqualRoundsIgnoringLast(withPercentage(5));

        assertThat(network.getStatusProgression())
                .hasSteps(
                        target(ACTIVE).requiringInterim(REPLAYING_EVENTS, OBSERVING, CHECKING),
                        target(FREEZE_COMPLETE).requiringInterim(FREEZING),
                        target(ACTIVE).requiringInterim(REPLAYING_EVENTS, OBSERVING, CHECKING));

        assertThat(network.getPcesResults()).hasMaxBirthRoundGreaterThan(1L).hasMaxBirthRoundLessThan(100L);
    }
}
