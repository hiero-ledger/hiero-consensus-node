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
import java.time.Duration;
import org.hiero.otter.fixtures.Network;
import org.hiero.otter.fixtures.OtterTest;
import org.hiero.otter.fixtures.TestEnvironment;
import org.hiero.otter.fixtures.TimeManager;

public class InstrumentedNodeTest {

    /**
     * Simple test that runs a network with 3 regular and one instrumented node for some time. It sends a ping to the
     * instrumented node and validates that it was received.
     *
     * @param env the test environment for this test
     */
    @OtterTest
    void testSimplePing(@NonNull final TestEnvironment env) {
        final Network network = env.network();
        final TimeManager timeManager = env.timeManager();

        // Setup simulation
        network.addNodes(4);

        assertContinuouslyThat(network.newConsensusResults()).haveEqualRounds();
        assertContinuouslyThat(network.newLogResults()).haveNoErrorLevelMessages();
        assertContinuouslyThat(network.newPlatformStatusResults())
                .doOnlyEnterStatusesOf(ACTIVE, REPLAYING_EVENTS, OBSERVING, CHECKING)
                .doNotEnterAnyStatusesOf(BEHIND, FREEZING);
        assertContinuouslyThat(network.newMarkerFileResults())
                .haveNoNoSuperMajorityMarkerFiles()
                .haveNoNoJudgesMarkerFiles()
                .haveNoConsensusExceptionMarkerFiles()
                .haveNoIssMarkerFiles();

        network.start();

        // Wait for 30 seconds
        timeManager.waitFor(Duration.ofSeconds(30L));

        // Validations
        assertThat(network.newLogResults()).haveNoErrorLevelMessages();

        assertThat(network.newConsensusResults()).haveEqualCommonRounds().haveConsistentRounds();

        assertThat(network.newPlatformStatusResults())
                .haveSteps(target(ACTIVE).requiringInterim(REPLAYING_EVENTS, OBSERVING, CHECKING));
    }
}
