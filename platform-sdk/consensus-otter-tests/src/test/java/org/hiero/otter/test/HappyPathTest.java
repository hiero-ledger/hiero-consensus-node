// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.test;

import java.io.IOException;
import java.time.Duration;
import org.hiero.otter.fixtures.Network;
import org.hiero.otter.fixtures.OtterTest;
import org.hiero.otter.fixtures.TestEnvironment;
import org.hiero.otter.fixtures.TimeManager;

public class HappyPathTest {

    @OtterTest
    void testHappyPath(final TestEnvironment env) throws IOException, InterruptedException {
        final Network network = env.network();
        final TimeManager timeManager = env.timeManager();

        // Setup simulation
        network.addNodes(4);
        //        assertContinuouslyThat(network.getConsensusResults()).haveEqualRounds();
        network.start();

        // Wait for two minutes
        timeManager.waitFor(Duration.ofMinutes(1L));

        // Validations
//        final MultipleNodeLogResults logResults =
//                network.getLogResults().ignoring(network.getNodes().getFirst()).ignoring(STARTUP);
//        assertThat(logResults).noMessageWithLevelHigherThan(Level.WARN);
//
//        assertThat(network.getStatusProgression())
//                .hasSteps(target(ACTIVE).requiringInterim(REPLAYING_EVENTS, OBSERVING, CHECKING));
//
//        assertThat(network.getPcesResults()).hasAllBirthRoundsEqualTo(1);
//
//        assertThat(network.getConsensusResults()).haveEqualRoundsIgnoringLast(Percentage.withPercentage(1));
    }
}
