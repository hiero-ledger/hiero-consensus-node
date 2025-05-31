// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.test;

import static com.swirlds.logging.legacy.LogMarker.STARTUP;
import static org.hiero.consensus.model.status.PlatformStatus.ACTIVE;
import static org.hiero.consensus.model.status.PlatformStatus.CHECKING;
import static org.hiero.consensus.model.status.PlatformStatus.OBSERVING;
import static org.hiero.consensus.model.status.PlatformStatus.REPLAYING_EVENTS;
import static org.hiero.otter.fixtures.OtterAssertions.assertContinuouslyThat;
import static org.hiero.otter.fixtures.OtterAssertions.assertThat;
import static org.hiero.otter.fixtures.assertions.StatusProgressionStep.target;

import java.time.Duration;
import org.apache.logging.log4j.Level;
import org.assertj.core.data.Percentage;
import org.hiero.otter.fixtures.Network;
import org.hiero.otter.fixtures.OtterTest;
import org.hiero.otter.fixtures.TestEnvironment;
import org.hiero.otter.fixtures.TimeManager;
import org.hiero.otter.fixtures.result.MultipleNodeLogResults;
import org.junit.jupiter.api.Disabled;

public class HappyPathTest {

    @Disabled
    @OtterTest
    void testHappyPath(TestEnvironment env) throws InterruptedException {
        final Network network = env.network();
        final TimeManager timeManager = env.timeManager();

        // Setup simulation
        network.addNodes(4);
        assertContinuouslyThat(network.getConsensusResults()).haveEqualRounds();
        network.start(Duration.ofMinutes(1L));
        env.generator().start();

        // Wait for two minutes
        timeManager.waitFor(Duration.ofMinutes(1L));

        // Validations
        final MultipleNodeLogResults logResults =
                network.getLogResults().ignoring(network.getNodes().getFirst()).ignoring(STARTUP);
        assertThat(logResults).noMessageWithLevelHigherThan(Level.WARN);

        assertThat(network.getStatusProgression())
                .hasSteps(target(ACTIVE).requiringInterim(REPLAYING_EVENTS, OBSERVING, CHECKING));

        assertThat(network.getPcesResults()).hasAllBirthRoundsEqualTo(1);

        assertThat(network.getConsensusResults()).hasEqualRoundsIgnoringLast(Percentage.withPercentage(1));
    }
}
