// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.test;

import static org.hiero.consensus.model.status.PlatformStatus.ACTIVE;
import static org.hiero.consensus.model.status.PlatformStatus.BEHIND;
import static org.hiero.consensus.model.status.PlatformStatus.CHECKING;
import static org.hiero.consensus.model.status.PlatformStatus.FREEZING;
import static org.hiero.consensus.model.status.PlatformStatus.OBSERVING;
import static org.hiero.consensus.model.status.PlatformStatus.REPLAYING_EVENTS;
import static org.hiero.otter.fixtures.OtterAssertions.assertContinuouslyThat;
import static org.hiero.otter.fixtures.OtterAssertions.assertThat;
import static org.hiero.otter.fixtures.assertions.StatusProgressionStep.target;

import com.swirlds.platform.event.preconsensus.PcesMultiFileIterator;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.IOException;
import java.time.Duration;
import org.hiero.consensus.model.event.PlatformEvent;
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
    void testHappyPath(@NonNull final TestEnvironment env) throws IOException {
        final Network network = env.network();
        final TimeManager timeManager = env.timeManager();

        // Setup simulation
        network.addNodes(4);

        // Setup continuous assertions
        assertContinuouslyThat(network.newLogResults()).haveNoErrorLevelMessages();
        assertContinuouslyThat(network.newReconnectResults()).doNotAttemptToReconnect();
        assertContinuouslyThat(network.newConsensusResults())
                .haveEqualCommonRounds()
                .haveConsistentRounds();
        assertContinuouslyThat(network.newPlatformStatusResults())
                .doOnlyEnterStatusesOf(ACTIVE, REPLAYING_EVENTS, OBSERVING, CHECKING)
                .doNotEnterAnyStatusesOf(BEHIND, FREEZING);

        network.start();

        // Wait for 5 seconds
        timeManager.waitFor(Duration.ofSeconds(5L));

        // Validations
        assertThat(network.newPlatformStatusResults())
                .haveSteps(target(ACTIVE).requiringInterim(REPLAYING_EVENTS, OBSERVING, CHECKING));

        assertThat(network.newEventStreamResults()).haveEqualFiles();
        network.shutdown();

        int max = 0;
        int count = 0;
        double sum = 0;
        try(final PcesMultiFileIterator pces = network.newPcesResults().pcesResults().getFirst().pcesEvents()){
            while (pces.hasNext()){
                count++;
                final int numParents = pces.next().getAllParents().size();
                max = Math.max(max, numParents);
                sum += numParents;
            }
        }

        System.out.println("Max number of parents: " + max);
        System.out.println("Average number of parents: " + (sum/count));

    }
}
