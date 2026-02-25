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

import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.IOException;
import java.time.Duration;
import org.hiero.consensus.event.creator.config.EventCreationConfig_;
import org.hiero.consensus.io.IOIterator;
import org.hiero.consensus.model.event.PlatformEvent;
import org.hiero.otter.fixtures.Network;
import org.hiero.otter.fixtures.OtterTest;
import org.hiero.otter.fixtures.TestEnvironment;
import org.hiero.otter.fixtures.TimeManager;

/**
 * Tests for multiple other-parents
 */
public class MopTest {

    /**
     * Simple test that enables MOP and validates that nodes have multiple other-parents
     *
     * @param env the test environment for this test
     */
    @OtterTest
    void testHappyPath(@NonNull final TestEnvironment env) throws IOException {
        final Network network = env.network();
        final TimeManager timeManager = env.timeManager();

        // Setup simulation
        network.withConfigValue(EventCreationConfig_.MAX_OTHER_PARENTS, 100).addNodes(4);

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

        int maxParents = 0;
        int count = 0;
        double sum = 0;
        try (final IOIterator<PlatformEvent> pces =
                network.newPcesResults().pcesResults().getFirst().pcesEvents()) {
            while (pces.hasNext()) {
                count++;
                final int numParents = pces.next().getAllParents().size();
                maxParents = Math.max(maxParents, numParents);
                sum += numParents;
            }
        }
        final double averageParents = sum / count;

        System.out.println("Max number of parents: " + maxParents);
        System.out.println("Average number of parents: " + averageParents);

        assertThat(maxParents).isGreaterThan(2);
        assertThat(averageParents).isGreaterThan(2);
    }
}
