// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.test;

import static org.hiero.otter.fixtures.OtterAssertions.assertContinuouslyThat;
import static org.hiero.otter.fixtures.OtterAssertions.assertThat;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Duration;
import java.util.List;
import org.hiero.otter.fixtures.Network;
import org.hiero.otter.fixtures.Node;
import org.hiero.otter.fixtures.OtterTest;
import org.hiero.otter.fixtures.TestEnvironment;
import org.hiero.otter.fixtures.TimeManager;
import org.hiero.otter.fixtures.network.BidirectionalConnection;

/**
 * Tests that the network keeps reaching consensus when a single link between two nodes is cut.
 */
public class LinkCutTest {

    /**
     * Cuts the link between two of four nodes for 30 seconds. The events still travel through the other two nodes, so
     * all nodes must keep advancing consensus.
     *
     * @param env the environment to test in
     */
    @OtterTest
    void testCutSingleLink(@NonNull final TestEnvironment env) {
        final Network network = env.network();
        final TimeManager timeManager = env.timeManager();

        network.addNodes(4);

        assertContinuouslyThat(network.newLogResults()).haveNoErrorLevelMessages();
        assertContinuouslyThat(network.newReconnectResults()).doNotAttemptToReconnect();
        assertContinuouslyThat(network.newConsensusResults())
                .haveEqualCommonRounds()
                .haveConsistentRounds();

        network.start();
        timeManager.waitFor(Duration.ofSeconds(10));

        final List<Node> nodes = network.nodes();
        final BidirectionalConnection link = network.bidirectionalConnection(nodes.get(0), nodes.get(1));
        link.disconnect();

        final long roundBeforeCut =
                network.newConsensusResults().results().getFirst().lastRoundNum();
        timeManager.waitFor(Duration.ofSeconds(30));
        assertThat(network.newConsensusResults()).haveAdvancedSinceRound(roundBeforeCut);

        link.connect();
        final long roundAfterCut =
                network.newConsensusResults().results().getFirst().lastRoundNum();
        timeManager.waitFor(Duration.ofSeconds(30));
        assertThat(network.newConsensusResults()).haveAdvancedSinceRound(roundAfterCut);
    }
}
