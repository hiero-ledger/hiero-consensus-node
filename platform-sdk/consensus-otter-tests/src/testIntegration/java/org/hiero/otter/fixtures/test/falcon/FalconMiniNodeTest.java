// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.test.falcon;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import org.hiero.otter.fixtures.MiniNode;
import org.hiero.otter.fixtures.Network;
import org.hiero.otter.fixtures.Node;
import org.hiero.otter.fixtures.TestEnvironment;
import org.hiero.otter.fixtures.TimeManager;
import org.hiero.otter.fixtures.falcon.FalconTestEnvironment;
import org.junit.jupiter.api.Test;

/**
 * Integration tests for mini-nodes in the Falcon environment.
 */
class FalconMiniNodeTest {

    private static final long RANDOM_SEED = 42L;
    private static final Duration RUN_DURATION = Duration.ofSeconds(10L);

    /**
     * Runs one node with three mini-nodes of equal weight. The node holds only a quarter of the weight, so it becomes
     * active and keeps advancing consensus only if the mini-nodes create events and pass them on. The mini-nodes in
     * turn follow the event window of the node.
     */
    @Test
    void testMiniNodesTakePartInConsensus() {
        final TestEnvironment env = new FalconTestEnvironment(RANDOM_SEED);
        try {
            final Network network = env.network();
            final TimeManager timeManager = env.timeManager();

            final Node node = network.addNode();
            final List<MiniNode> miniNodes = network.addMiniNodes(3);
            network.nodeWeight(1L);

            network.start();
            final long roundAtStart = node.newConsensusResult().lastRoundNum();
            timeManager.waitFor(RUN_DURATION);

            assertThat(node.newConsensusResult().lastRoundNum()).isGreaterThan(roundAtStart);
            for (final MiniNode miniNode : miniNodes) {
                assertThat(miniNode.latestEventWindow().latestConsensusRound())
                        .as("Latest consensus round in the event window of mini-node %s", miniNode.selfId())
                        .isPositive();
            }
        } finally {
            env.destroy();
        }
    }
}
