// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hiero.consensus.model.status.PlatformStatus.BEHIND;
import static org.hiero.otter.fixtures.OtterAssertions.assertContinuouslyThat;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Duration;
import org.hiero.consensus.hashgraph.config.ConsensusConfig_;
import org.hiero.otter.fixtures.Capability;
import org.hiero.otter.fixtures.Network;
import org.hiero.otter.fixtures.Node;
import org.hiero.otter.fixtures.OtterTest;
import org.hiero.otter.fixtures.TestEnvironment;
import org.hiero.otter.fixtures.TimeManager;

/**
 * Tests that a node that falls behind, because it was isolated for long enough, notices it once it is reconnected to
 * the network: it goes to BEHIND, stops receiving events and stops advancing consensus.
 */
public class FallenBehindTurtleTest {

    /** Reducing the number of rounds non-expired allows a node to fall behind faster. */
    private static final long ROUNDS_EXPIRED = 100L;

    @OtterTest(requires = Capability.DETERMINISTIC_EXECUTION)
    void testIsolatedNodeFallsBehind(@NonNull final TestEnvironment env) {
        final Network network = env.network();
        final TimeManager timeManager = env.timeManager();

        network.addNodes(5);
        network.withConfigValue(ConsensusConfig_.ROUNDS_EXPIRED, ROUNDS_EXPIRED);

        final Node isolatedNode = network.nodes().getLast();

        assertContinuouslyThat(network.newLogResults()).haveNoErrorLevelMessages();
        assertContinuouslyThat(network.newConsensusResults().suppressingNode(isolatedNode))
                .haveConsistentRounds();
        assertContinuouslyThat(network.newPlatformStatusResults().suppressingNode(isolatedNode))
                .doNotEnterAnyStatusesOf(BEHIND);

        network.start();
        timeManager.waitFor(Duration.ofSeconds(5L));

        network.isolate(isolatedNode);
        timeManager.waitForCondition(
                () -> network.nodeIsBehindByNodeCount(isolatedNode),
                Duration.ofSeconds(120L),
                "Node did not fall behind in the time allotted.");

        network.restoreConnectivity();
        timeManager.waitForCondition(
                isolatedNode::isBehind, Duration.ofSeconds(60L), "Node did not enter BEHIND in the time allotted.");

        // The node refuses events from its peers, so its consensus must not advance anymore
        final long roundWhenBehind = isolatedNode.newConsensusResult().lastRoundNum();
        final long otherNodeRound =
                network.nodes().getFirst().newConsensusResult().lastRoundNum();
        timeManager.waitFor(Duration.ofSeconds(30L));

        assertThat(isolatedNode.isBehind()).isTrue();
        assertThat(isolatedNode.newConsensusResult().lastRoundNum()).isEqualTo(roundWhenBehind);
        assertThat(network.nodes().getFirst().newConsensusResult().lastRoundNum())
                .isGreaterThan(otherNodeRound);
    }
}
