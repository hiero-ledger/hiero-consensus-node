// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.test;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Duration;
import org.hiero.consensus.model.quiescence.QuiescenceCommand;
import org.hiero.otter.fixtures.Network;
import org.hiero.otter.fixtures.OtterTest;
import org.hiero.otter.fixtures.TestEnvironment;
import org.hiero.otter.fixtures.TimeManager;

/**
 * The simplest sanity test for the Otter framework.
 */
public class HappyPathTest {

    /**
     * Simple test that runs a network with 4 nodes for some time and does some basic validations.
     *
     * @param env the test environment for this test
     */
    @OtterTest
    void testHappyPath(@NonNull final TestEnvironment env) {
        final Network network = env.network();
        final TimeManager timeManager = env.timeManager();

        // Setup simulation
        network.addNodes(4);

        // Setup continuous assertions
//        assertContinuouslyThat(network.newLogResults()).haveNoErrorLevelMessages();
//        assertContinuouslyThat(network.newReconnectResults()).doNotAttemptToReconnect();
//        assertContinuouslyThat(network.newConsensusResults())
//                .haveEqualCommonRounds()
//                .haveConsistentRounds();
//        assertContinuouslyThat(network.newMarkerFileResults()).haveNoMarkerFiles();
//        assertContinuouslyThat(network.newPlatformStatusResults())
//                .doOnlyEnterStatusesOf(ACTIVE, REPLAYING_EVENTS, OBSERVING, CHECKING)
//                .doNotEnterAnyStatusesOf(BEHIND, FREEZING);

        network.start();

        env.transactionGenerator().stop();

        // Wait for some time
        timeManager.waitForRealTime(Duration.ofMinutes(5L));

        // Validations
//        assertThat(network.newPlatformStatusResults())
//                .haveSteps(target(ACTIVE).requiringInterim(REPLAYING_EVENTS, OBSERVING, CHECKING));

//        assertThat(network.newEventStreamResults()).haveEqualFiles();


        network.nodes().getFirst().sendQuiescenceCommand(QuiescenceCommand.DONT_QUIESCE);

        // wait a bit before shutting down to allow writing timestamps file
        timeManager.waitForRealTime(Duration.ofSeconds(10L));
    }
}
