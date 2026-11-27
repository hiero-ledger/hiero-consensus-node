// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.test.falcon;

import static org.hiero.otter.fixtures.OtterAssertions.assertThat;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Duration;
import java.util.List;
import org.hiero.consensus.hashgraph.config.ConsensusConfig_;
import org.hiero.otter.fixtures.FalconTest;
import org.hiero.otter.fixtures.Network;
import org.hiero.otter.fixtures.Node;
import org.hiero.otter.fixtures.TestEnvironment;
import org.hiero.otter.fixtures.TimeManager;

/**
 * Checks that no events are lost while some of them become stale.
 */
public class StaleEventsTest {

    private static final long ROUNDS_NON_ANCIENT = 5L;
    private static final Duration SLOW_LINK_LATENCY = Duration.ofSeconds(2L);

    /**
     * Runs a network in which the events of one node reach the other nodes so late that most of them are ancient by
     * then. The other nodes reach consensus without them, so they become stale on the slow node.
     *
     * @param env the test environment for this test
     */
    @FalconTest(repetitions = 50)
    void testNoEventsLostWithStaleEvents(@NonNull final TestEnvironment env) {
        final Network network = env.network();
        final TimeManager timeManager = env.timeManager();

        // Setup simulation
        final List<Node> nodes = network.addNodes(10);
        network.withConfigValue(ConsensusConfig_.ROUNDS_NON_ANCIENT, ROUNDS_NON_ANCIENT);

        // Start simulation
        network.start();

        // Slow down the outgoing links of one node. This happens after the start, because the node would not become
        // ACTIVE otherwise.
        final Node slowNode = nodes.getFirst();
        for (final Node peer : nodes.subList(1, nodes.size())) {
            network.unidirectionalConnection(slowNode, peer).latency(SLOW_LINK_LATENCY);
        }

        // Run the network for 30s simulated time
        timeManager.waitFor(Duration.ofSeconds(30L));

        // Validations
        assertThat(network.newConsensusResults())
                .haveEqualCommonRounds()
                .haveConsistentRounds()
                .haveNoLostEvents()
                .haveNoUnannouncedEvents();

        // The events of the slow node, about a tenth of all events, become stale on the slow node only.
        // Apart from them, every node keeps reaching consensus.
        assertThat(slowNode.newConsensusResult())
                .hasStaleRatioBetween(0.05, 0.12)
                .hasConsensusRatioBetween(0.75, 0.95);
        assertThat(network.newConsensusResults().suppressingNode(slowNode))
                .haveStaleRatioBetween(0.0, 0.01)
                .haveConsensusRatioBetween(0.85, 1.0);
    }
}
