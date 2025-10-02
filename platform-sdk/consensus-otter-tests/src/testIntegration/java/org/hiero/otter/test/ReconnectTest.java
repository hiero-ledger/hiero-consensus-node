// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.test;

import static org.hiero.consensus.model.status.PlatformStatus.ACTIVE;
import static org.hiero.consensus.model.status.PlatformStatus.BEHIND;
import static org.hiero.consensus.model.status.PlatformStatus.CHECKING;
import static org.hiero.consensus.model.status.PlatformStatus.OBSERVING;
import static org.hiero.consensus.model.status.PlatformStatus.RECONNECT_COMPLETE;
import static org.hiero.consensus.model.status.PlatformStatus.REPLAYING_EVENTS;
import static org.hiero.otter.fixtures.OtterAssertions.assertContinuouslyThat;
import static org.hiero.otter.fixtures.OtterAssertions.assertThat;
import static org.hiero.otter.fixtures.assertions.StatusProgressionStep.target;

import com.swirlds.common.test.fixtures.WeightGenerators;
import com.swirlds.platform.consensus.ConsensusConfig_;
import java.time.Duration;
import org.hiero.otter.fixtures.Capability;
import org.hiero.otter.fixtures.Network;
import org.hiero.otter.fixtures.Node;
import org.hiero.otter.fixtures.OtterTest;
import org.hiero.otter.fixtures.TestEnvironment;
import org.hiero.otter.fixtures.TimeManager;
import org.hiero.otter.fixtures.result.SingleNodePlatformStatusResult;

/**
 * Tests the reconnect functionality of a node that has fallen behind in the consensus rounds. The test ensures that the
 * node can successfully reconnect and catch up with the rest of the network.
 */
public class ReconnectTest {

    private static final long ROUNDS_NON_ANCIENT = 50L;
    private static final long ROUNDS_EXPIRED = 100L;

    @OtterTest(requires = Capability.RECONNECT)
    void testSimpleNodeDeathReconnect(final TestEnvironment env) {
        final Network network = env.network();
        final TimeManager timeManager = env.timeManager();

        // Setup simulation

        // Add more than 3 nodes with balanced weights so that one node can be taken down without halting consensus
        network.setWeightGenerator(WeightGenerators.BALANCED);
        network.addNodes(4);

        // Set the rounds non-ancient and expired to smaller values to allow nodes to fall behind quickly
        network.withConfigValue(ConsensusConfig_.ROUNDS_NON_ANCIENT, ROUNDS_NON_ANCIENT)
                .withConfigValue(ConsensusConfig_.ROUNDS_EXPIRED, ROUNDS_EXPIRED);

        // Set the node we will force to reconnect
        final Node nodeToReconnect = network.nodes().getLast();

        // Setup continuous assertions
        assertContinuouslyThat(network.newConsensusResults()).haveEqualRounds();
        assertContinuouslyThat(network.newPlatformStatusResults().suppressingNode(nodeToReconnect))
                .doNotEnterAnyStatusesOf(BEHIND);
        assertContinuouslyThat(nodeToReconnect.newReconnectResult())
                .hasNoFailedReconnects()
                .hasMaximumReconnectTime(Duration.ofSeconds(10))
                .hasMaximumTreeInitializationTime(Duration.ofSeconds(1));
        network.start();

        // Wait for thirty seconds
        timeManager.waitFor(Duration.ofSeconds(5L));

        // Shutdown the node for a period of time so that it falls behind.
        nodeToReconnect.killImmediately();

        // Verify that the node was healthy prior to being killed
        final SingleNodePlatformStatusResult nodeToReconnectStatusResults = nodeToReconnect.newPlatformStatusResult();
        assertThat(nodeToReconnectStatusResults)
                .hasSteps(target(ACTIVE).requiringInterim(REPLAYING_EVENTS, OBSERVING, CHECKING));
        nodeToReconnectStatusResults.clear();

        // Wait for the node we just killed to become behind enough to require a reconnect.
        timeManager.waitForCondition(
                () -> network.nodeIsBehindByNodeCount(nodeToReconnect, 0.5),
                Duration.ofSeconds(120),
                "Node did not fall behind in the time allotted.");

        // Restart the node that was killed
        nodeToReconnect.start();

        // First, we must wait for the node to come back up and report that it is behind.
        // If we wait for it to be active, this check will pass immediately. That was the last status it had,
        // and we will check the value before the node has a change to tell us that it is behind.
        timeManager.waitForCondition(nodeToReconnect::isBehind, Duration.ofSeconds(30));

        // Now we wait for the node to reconnect and become active again.
        timeManager.waitForCondition(nodeToReconnect::isActive, Duration.ofSeconds(30));

        // Validations
        assertThat(network.newLogResults()).haveNoErrorLevelMessages();

        assertThat(nodeToReconnect.newReconnectResult()).hasNoFailedReconnects().hasExactSuccessfulReconnects(1);

        assertThat(network.newConsensusResults().suppressingNode(nodeToReconnect))
                .haveConsistentRounds();
        assertThat(network.newConsensusResults()).haveEqualCommonRounds();

        // All non-reconnected nodes should go through the normal status progression
        assertThat(network.newPlatformStatusResults().suppressingNode(nodeToReconnect))
                .haveSteps(target(ACTIVE).requiringInterim(REPLAYING_EVENTS, OBSERVING, CHECKING));

        // The reconnected node should have gone through the reconnect status progression since restarting
        assertThat(nodeToReconnectStatusResults)
                .hasSteps(target(ACTIVE)
                        .requiringInterim(REPLAYING_EVENTS, OBSERVING, BEHIND, RECONNECT_COMPLETE, CHECKING));
    }
}
