// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.test.falcon;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hiero.otter.fixtures.OtterAssertions.assertContinuouslyThat;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Duration;
import java.util.List;
import org.hiero.otter.fixtures.Network;
import org.hiero.otter.fixtures.Node;
import org.hiero.otter.fixtures.TestEnvironment;
import org.hiero.otter.fixtures.TimeManager;
import org.hiero.otter.fixtures.falcon.FalconTestEnvironment;
import org.hiero.otter.fixtures.network.BidirectionalConnection;
import org.junit.jupiter.api.Test;

/**
 * Integration tests for event forwarding in the Falcon environment.
 *
 * <p>Falcon nodes pass on every event they receive, so a node whose link to a peer is down still gets that peer's events
 * through the other nodes.
 */
class FalconEventForwardingTest {

    private static final long RANDOM_SEED = 42L;
    private static final Duration CUT_DURATION = Duration.ofSeconds(30L);
    private static final Duration CHECK_INTERVAL = Duration.ofSeconds(5L);

    /**
     * Cuts the link between two of four nodes and verifies that every node, including the two, keeps advancing
     * consensus while the link is down.
     */
    @Test
    void testConsensusAdvancesWhileLinkIsCut() {
        final TestEnvironment env = new FalconTestEnvironment(RANDOM_SEED);
        try {
            final Network network = env.network();
            final TimeManager timeManager = env.timeManager();

            // Setup network with 4 nodes
            final List<Node> nodes = network.addNodes(4);
            final BidirectionalConnection connection = network.bidirectionalConnection(nodes.get(0), nodes.get(1));

            assertContinuouslyThat(network.newConsensusResults())
                    .haveEqualCommonRounds()
                    .haveConsistentRounds();

            network.start();
            timeManager.waitFor(CHECK_INTERVAL);

            // Cut the link and verify that every node keeps advancing consensus
            connection.disconnect();
            assertThat(connection.isConnected()).isFalse();

            long[] lastRounds = lastRounds(nodes);
            for (long check = 1; check <= CUT_DURATION.dividedBy(CHECK_INTERVAL); check++) {
                timeManager.waitFor(CHECK_INTERVAL);
                final long[] currentRounds = lastRounds(nodes);
                for (int i = 0; i < nodes.size(); i++) {
                    assertThat(currentRounds[i])
                            .as(
                                    "Last round of node %s after check %d of the cut",
                                    nodes.get(i).selfId(), check)
                            .isGreaterThan(lastRounds[i]);
                }
                lastRounds = currentRounds;
            }
        } finally {
            env.destroy();
        }
    }

    @NonNull
    private static long[] lastRounds(@NonNull final List<Node> nodes) {
        return nodes.stream()
                .mapToLong(node -> node.newConsensusResult().lastRoundNum())
                .toArray();
    }
}
