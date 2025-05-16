// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.test;

import static org.hiero.otter.fixtures.OtterAssertions.assertThat;

import java.time.Duration;
import org.hiero.otter.fixtures.Network;
import org.hiero.otter.fixtures.OtterTest;
import org.hiero.otter.fixtures.TestEnvironment;
import org.hiero.otter.fixtures.TimeManager;

public class HappyPathTest {

    @OtterTest
    void testHappyPath(TestEnvironment env) throws InterruptedException {
        final Network network = env.network();
        final TimeManager timeManager = env.timeManager();

        // Setup simulation
        network.addNodes(4);
        network.start(Duration.ofMinutes(1L));
        env.generator().start();

        // Wait for two minutes
        timeManager.waitFor(Duration.ofMinutes(1L));

        // Validations
//        env.validator().validateRemaining(Profile.DEFAULT);
//
//        final MultipleNodeLogResults logResults =
//                network.getLogResults().ignoring(network.getNodes().getFirst()).ignoring(STARTUP);
//        assertThat(logResults).noMessageWithLevelHigherThan(Level.INFO);
//
//        assertThat(network.getStatusProgression())
//                .hasSteps(target(ACTIVE).requiringInterim(REPLAYING_EVENTS, OBSERVING, CHECKING));

        assertThat(network.getPcesResults()).hasBirthRoundsLessThan(2L);
    }
}
