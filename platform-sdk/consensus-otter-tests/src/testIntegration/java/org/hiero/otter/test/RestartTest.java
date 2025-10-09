// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.test;

import static org.hiero.consensus.model.status.PlatformStatus.ACTIVE;
import static org.hiero.consensus.model.status.PlatformStatus.BEHIND;
import static org.hiero.consensus.model.status.PlatformStatus.CHECKING;
import static org.hiero.consensus.model.status.PlatformStatus.OBSERVING;
import static org.hiero.consensus.model.status.PlatformStatus.REPLAYING_EVENTS;
import static org.hiero.otter.fixtures.OtterAssertions.assertContinuouslyThat;
import static org.hiero.otter.fixtures.OtterAssertions.assertThat;
import static org.hiero.otter.fixtures.assertions.StatusProgressionStep.target;

import com.swirlds.logging.legacy.LogMarker;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Duration;
import org.hiero.otter.fixtures.Network;
import org.hiero.otter.fixtures.OtterTest;
import org.hiero.otter.fixtures.TestEnvironment;
import org.hiero.otter.fixtures.TimeManager;
import org.hiero.otter.fixtures.result.MultipleNodePlatformStatusResults;
import org.hiero.otter.fixtures.result.SingleNodeConsensusResult;

/**
 * Tests that a hard restart of all nodes in the network works correctly, and that all nodes are able to restart, replay
 * their PCES events, go ACTIVE, and continue reaching consensus.
 */
public class RestartTest {

    /**
     * Tests that a hard restart of all nodes in the network works correctly, and that all nodes are able to restart,
     * replay their PCES events, go ACTIVE, and continue reaching consensus.
     *
     * @param env the test environment
     */
    @OtterTest
    void testHardNetworkRestart(@NonNull final TestEnvironment env) {
        final Network network = env.network();
        final TimeManager timeManager = env.timeManager();

        // Setup simulation
        network.addNodes(4);

        // Setup continuous assertions
        assertContinuouslyThat(network.newConsensusResults()).haveEqualRounds();
        assertContinuouslyThat(network.newPlatformStatusResults()).doNotEnterAnyStatusesOf(BEHIND);

        network.start();

        // Allow the nodes to run for a short time
        timeManager.waitFor(Duration.ofSeconds(10L));

        // Restart all the nodes
        network.shutdown();

        // Verify that the node was healthy prior to being killed
        final MultipleNodePlatformStatusResults networkStatusResults = network.newPlatformStatusResults();
        assertThat(networkStatusResults)
                .haveSteps(target(ACTIVE).requiringInterim(REPLAYING_EVENTS, OBSERVING, CHECKING));
        networkStatusResults.clear();

        final long lastRoundReached = network.newConsensusResults().results().stream()
                .map(SingleNodeConsensusResult::lastRoundNum)
                .max(Long::compareTo)
                .orElseThrow(() -> new IllegalStateException("No consensus rounds found"));
        timeManager.waitFor(Duration.ofSeconds(3L));
        network.start();

        // Wait for all nodes to advance at least 20 rounds beyond the last round reached
        timeManager.waitForCondition(
                () -> allNodesAdvancedToRound(lastRoundReached + 20, network), Duration.ofSeconds(120L));

        assertThat(network.newLogResults().suppressingLogMarker(LogMarker.SOCKET_EXCEPTIONS))
                .haveNoErrorLevelMessages();

        // All nodes should go through the normal status progression again
        assertThat(networkStatusResults)
                .haveSteps(target(ACTIVE).requiringInterim(REPLAYING_EVENTS, OBSERVING, CHECKING));

        assertThat(network.newConsensusResults()).haveEqualCommonRounds();
    }

    private boolean allNodesAdvancedToRound(final long targetRound, @NonNull final Network network) {
        return network.newConsensusResults().results().stream().allMatch(r -> r.lastRoundNum() > targetRound);
    }
}
