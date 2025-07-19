// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.test;

import static org.assertj.core.data.Percentage.withPercentage;
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
import java.time.Duration;
import org.hiero.otter.fixtures.Network;
import org.hiero.otter.fixtures.OtterTest;
import org.hiero.otter.fixtures.TestEnvironment;
import org.hiero.otter.fixtures.TimeManager;

/**
 * The most simple sanity test for the Otter framework.
 */
public class HappyPathTest {

    /**
     * Simple test that runs a network with 4 nodes for some time and does some basic validations.
     *
     * @param env the test environment for this test
     * @throws InterruptedException if an operation times out
     */
    @OtterTest
    void testHappyPath(@NonNull final TestEnvironment env) throws InterruptedException {
        final Network network = env.network();
        final TimeManager timeManager = env.timeManager();

        // Setup simulation
        network.addNodes(4);

        assertContinuouslyThat(network.getConsensusResults()).haveEqualRounds();
        assertContinuouslyThat(network.getLogResults()).haveNoErrorLevelMessages();
        assertContinuouslyThat(network.getPlatformStatusResults())
                .doOnlyEnterStatusesOf(ACTIVE, REPLAYING_EVENTS, OBSERVING, CHECKING)
                .doNotEnterAnyStatusesOf(BEHIND, FREEZING);

        network.start();

        // Wait for 30 seconds
        timeManager.waitFor(Duration.ofSeconds(30L));

        // Validations
        assertThat(network.getLogResults()).haveNoErrorLevelMessages();

        assertThat(network.getConsensusResults())
                .haveEqualCommonRounds()
                .haveMaxDifferenceInLastRoundNum(withPercentage(10));

        assertThat(network.getPlatformStatusResults())
                .haveSteps(target(ACTIVE).requiringInterim(REPLAYING_EVENTS, OBSERVING, CHECKING));
    }
}
