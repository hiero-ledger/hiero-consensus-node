// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.falcon;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import org.hiero.consensus.model.hashgraph.ConsensusRound;
import org.hiero.consensus.model.node.NodeId;
import org.hiero.otter.fixtures.network.simulation.SimulatedNetworkConnectivity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tests that a {@link FalconObserver} follows what the nodes of a {@link FalconNetwork} do.
 */
class FalconObserverTest {

    @Test
    @DisplayName("An observer is told about the nodes, the start of the network and the rounds of the full nodes")
    void observerFollowsTheNetwork() {
        final FalconTestEnvironment env = new FalconTestEnvironment(1L);
        try {
            runObservedNetwork(env);
        } finally {
            env.destroy();
        }
    }

    /**
     * Runs a network of two full nodes and two mini-nodes for a while, followed by an observer that records what it is
     * told, and also follows the gossip of the simulated network.
     *
     * @param env the test environment
     */
    private static void runObservedNetwork(@NonNull final FalconTestEnvironment env) {
        final FalconNetwork network = env.network();
        final RecordingObserver observer = new RecordingObserver();
        network.registerObserver(observer);

        network.addNodes(2);
        network.addMiniNodes(2);
        assertThatThrownBy(() -> network.registerObserver(new FalconObserver() {}))
                .isInstanceOf(IllegalStateException.class);
        assertThat(observer.fullNodes).hasSize(2);
        assertThat(observer.miniNodes).hasSize(2);
        assertThat(observer.startedAt).isNull();

        final Instant startTime = env.timeManager().now();
        network.start();
        assertThat(observer.startedAt).isEqualTo(startTime);

        env.timeManager().waitFor(Duration.ofSeconds(5));

        // every full node reports its own rounds; mini-nodes run no consensus engine
        assertThat(observer.roundsByNode.keySet()).isEqualTo(observer.fullNodes);
        assertThat(observer.roundsByNode.values())
                .allSatisfy(rounds -> assertThat(rounds).isPositive());
        // every node, mini-nodes included, submits the events it creates and receives to the simulated network
        assertThat(observer.submitters).hasSize(4);
    }

    @Test
    @DisplayName("An observer must be set before nodes are added")
    void observerMustBeSetBeforeNodesAreAdded() {
        final FalconTestEnvironment env = new FalconTestEnvironment(1L);
        try {
            final FalconNetwork network = env.network();
            network.addMiniNodes(1);

            assertThatThrownBy(() -> network.registerObserver(new FalconObserver() {}))
                    .isInstanceOf(IllegalStateException.class);
        } finally {
            env.destroy();
        }
    }

    /**
     * Records what it is told.
     */
    private static final class RecordingObserver implements FalconObserver {
        private final Set<NodeId> fullNodes = new HashSet<>();
        private final Set<NodeId> miniNodes = new HashSet<>();
        private final Map<NodeId, Integer> roundsByNode = new HashMap<>();
        private final Set<Integer> submitters = new HashSet<>();
        private Instant startedAt;

        @Override
        public void onNodeAdded(@NonNull final NodeId nodeId, final boolean fullNode) {
            (fullNode ? fullNodes : miniNodes).add(nodeId);
        }

        @Override
        public void onNetworkStarting(@NonNull final SimulatedNetworkConnectivity network, @NonNull final Instant now) {
            assertThat(roundsByNode).isEmpty();
            startedAt = now;
            network.addObserver((event, submitter, time) -> submitters.add(submitter));
        }

        @Override
        public void onConsensusRound(@NonNull final NodeId nodeId, @NonNull final ConsensusRound round) {
            roundsByNode.merge(nodeId, 1, Integer::sum);
        }
    }
}
