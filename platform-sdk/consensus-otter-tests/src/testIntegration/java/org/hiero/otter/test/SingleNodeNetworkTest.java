// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hiero.consensus.model.status.PlatformStatus.ACTIVE;
import static org.hiero.consensus.model.status.PlatformStatus.CHECKING;
import static org.hiero.consensus.model.status.PlatformStatus.FREEZE_COMPLETE;
import static org.hiero.consensus.model.status.PlatformStatus.FREEZING;
import static org.hiero.consensus.model.status.PlatformStatus.OBSERVING;
import static org.hiero.consensus.model.status.PlatformStatus.REPLAYING_EVENTS;
import static org.hiero.otter.fixtures.OtterAssertions.assertThat;
import static org.hiero.otter.fixtures.assertions.StatusProgressionStep.target;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Duration;
import org.hiero.otter.fixtures.Network;
import org.hiero.otter.fixtures.Node;
import org.hiero.otter.fixtures.OtterTest;
import org.hiero.otter.fixtures.TestEnvironment;
import org.hiero.otter.fixtures.TimeManager;

/**
 * Tests basic functionality of a single node network. Single node networks would probably never be used in production,
 * but they can be useful for testing and are officially supported.
 */
public class SingleNodeNetworkTest {

    /**
     * A basic test that a single node network can reach consensus and freeze correctly.
     */
    @OtterTest
    void testSingleNodeNetwork(@NonNull final TestEnvironment env) {
        final Network network = env.network();
        final TimeManager timeManager = env.timeManager();

        network.addNodes(1);
        network.start();
        // Let the single node run for a short time
        timeManager.waitFor(Duration.ofSeconds(10));
        network.freeze();
        final Node theOnlyNode = network.nodes().getFirst();
        final long freezeRound = theOnlyNode.newConsensusResult().lastRoundNum();
        network.shutdown();

        // Verify that the single node reached freeze complete status while being active for a while
        assertThat(theOnlyNode.newPlatformStatusResult())
                .hasSteps(target(FREEZE_COMPLETE)
                        .requiringInterim(ACTIVE)
                        .optionalInterim(REPLAYING_EVENTS, OBSERVING, CHECKING, FREEZING));
        // Verify that the freeze round is reasonable, given the time we let the node run
        assertThat(freezeRound)
                .withFailMessage("10 seconds should be enough time for a single node to reach at least round 20")
                .isGreaterThan(20);
        // Verify that there are no errors
        assertThat(network.newLogResults()).haveNoErrorLevelMessages();
    }
}
