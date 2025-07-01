// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.test;

import static org.assertj.core.data.Percentage.withPercentage;
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
import org.hiero.otter.fixtures.Network;
import org.hiero.otter.fixtures.Node;
import org.hiero.otter.fixtures.OtterTest;
import org.hiero.otter.fixtures.TestEnvironment;
import org.hiero.otter.fixtures.TimeManager;

/**
 * Tests the reconnect functionality of a node that has fallen behind in the consensus rounds.
 * The test ensures that the node can successfully reconnect and catch up with the rest of the network.
 */
public class ReconnectTest {

    private static final long ROUNDS_NON_ANCIENT = 5L;
    private static final long ROUNDS_EXPIRED = 10L;

    @OtterTest
    void testSimpleNodeDeathReconnect(final TestEnvironment env) throws InterruptedException {
        final Network network = env.network();
        final TimeManager timeManager = env.timeManager();

        // Setup simulation

        // Add more than 3 nodes with balanced weights so that one node can be taken down without halting consensus
        network.addNodes(4, WeightGenerators.BALANCED);

        // Set the rounds non-ancient and expired to smaller values to allow nodes to fall behind quickly
        network.getNodes().forEach(node -> {
            node.configuration()
                    .set(ConsensusConfig_.ROUNDS_NON_ANCIENT, String.valueOf(ROUNDS_NON_ANCIENT))
                    .set(ConsensusConfig_.ROUNDS_EXPIRED, String.valueOf(ROUNDS_EXPIRED));
        });

        assertContinuouslyThat(network.getConsensusResults()).haveEqualRounds();
        network.start();

        // Wait for thirty seconds minutes
        timeManager.waitFor(Duration.ofSeconds(30L));

        // Shutdown the last node for a period of time so that it falls behind.
        final Node nodeToReconnect = network.getNodes().getLast();
        nodeToReconnect.killImmediately();

        // Continue running the remaining nodes until this node is far enough
        // that it cannot catch up via gossip and must reconnect (i.e. it has fallen behind).
        do {
            timeManager.waitFor(Duration.ofSeconds(1L));
        } while (nodeCanCatchUp(network, nodeToReconnect));

        // Restart the node that was killed
        nodeToReconnect.start();

        // Wait for thirty seconds minutes and allow the node to reconnect and become active again
        timeManager.waitFor(Duration.ofSeconds(30L));

        // Validations
        assertThat(network.getLogResults()).haveNoErrorLevelMessages();

        assertThat(network.getConsensusResults())
                .haveEqualCommonRounds()
                .haveMaxDifferenceInLastRoundNum(withPercentage(5));

        // All non-reconnected nodes should go through the normal status progression
        assertThat(network.getPlatformStatusResults().suppressingNode(nodeToReconnect))
                .haveSteps(target(ACTIVE).requiringInterim(REPLAYING_EVENTS, OBSERVING, CHECKING));

        // The reconnected node should have gone through the reconnect status progression
        assertThat(nodeToReconnect.getPlatformStatusResults())
                .hasSteps(target(ACTIVE)
                        .requiringInterim(
                                REPLAYING_EVENTS,
                                OBSERVING,
                                CHECKING,
                                ACTIVE,
                                REPLAYING_EVENTS,
                                OBSERVING,
                                BEHIND,
                                RECONNECT_COMPLETE,
                                CHECKING));
    }

    /**
     * For a node to fall behind, its minimum non-ancient round must be less than the minimum non-expired round of its
     * peers. Only a certain threshold of peers must meet this condition for the node to be considered behind, but it's
     * easier to just wait for all nodes.
     *
     * @param network         the network of this test
     * @param nodeToCheck the node to check if it can catch up via gossip
     * @return true if the {@code nodeToCheck} would still be able to gossip with at least one peer
     */
    private boolean nodeCanCatchUp(final Network network, final Node nodeToCheck) {
        // Get the minimum judge birth round of the latest consensus round reached on the node we want to check
        final long minNonAncientRound = nodeToCheck
                .getConsensusResult()
                .consensusRounds()
                .getLast()
                .getSnapshot()
                .minimumJudgeInfoList()
                .getFirst()
                .minimumJudgeAncientThreshold();

        for (final Node node : network.getNodes()) {
            if (node.selfId().equals(nodeToCheck.selfId())) {
                // Skip the node that is being checked
                continue;
            }
            final long minNonExpiredRound =
                    node.getConsensusResult().consensusRounds().getLast().getRoundNum() - ROUNDS_EXPIRED;
            if (minNonAncientRound >= minNonExpiredRound) {
                // The node to check is not yet behind compared to this node and is able to gossip with it
                return true;
            }
        }
        return false;
    }
}
