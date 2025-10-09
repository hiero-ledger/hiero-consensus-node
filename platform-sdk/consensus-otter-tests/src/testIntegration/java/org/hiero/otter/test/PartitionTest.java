// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.test;

import static org.hiero.consensus.model.status.PlatformStatus.ACTIVE;
import static org.hiero.consensus.model.status.PlatformStatus.CHECKING;
import static org.hiero.consensus.model.status.PlatformStatus.OBSERVING;
import static org.hiero.consensus.model.status.PlatformStatus.REPLAYING_EVENTS;
import static org.hiero.otter.fixtures.OtterAssertions.assertThat;
import static org.hiero.otter.fixtures.assertions.StatusProgressionStep.target;

import com.swirlds.logging.legacy.LogMarker;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Duration;
import java.util.List;
import org.hiero.otter.fixtures.Network;
import org.hiero.otter.fixtures.Node;
import org.hiero.otter.fixtures.OtterTest;
import org.hiero.otter.fixtures.TestEnvironment;
import org.hiero.otter.fixtures.TimeManager;
import org.hiero.otter.fixtures.result.MultipleNodeLogResults;
import org.hiero.otter.fixtures.result.SingleNodeConsensusResult;

/**
 * Tests for network partitions and their resolution.
 */
public class PartitionTest {

    /**
     * Tests network partitions where the partitioned nodes are a strong minority of the total weight, but not a
     * supermajority. Neither partition should be able to make consensus progress, and no reconnects should be required
     * upon partition healing.
     */
    @OtterTest
    void testStrongMinorityNetworkPartition(@NonNull final TestEnvironment env) {
        testNetworkPartition(env, List.of(625L, 3200L, 3175L, 3000L), List.of(2, 3));
    }

    /**
     * Runs a single network partition test with the given node weights and partition indices.
     *
     * @param env the environment to test in
     * @param weights the weights of every node in the network
     * @param partitionIndices the indices of the nodes to partition from the rest of the network, which must constitute
     * a strong minority but not a supermajority
     */
    private void testNetworkPartition(
            @NonNull final TestEnvironment env,
            @NonNull final List<Long> weights,
            @NonNull final List<Integer> partitionIndices) {
        final Network network = env.network();
        final TimeManager timeManager = env.timeManager();

        network.addNodes(weights.size());
        for (int i = 0; i < weights.size(); i++) {
            network.nodes().get(i).weight(weights.get(i));
        }

        network.start();

        assertThat(network.newLogResults()).haveNoErrorLevelMessages();

        final List<Node> nodesToPartition =
                partitionIndices.stream().map(i -> network.nodes().get(i)).toList();
        network.createNetworkPartition(nodesToPartition);

        final long partitionRound = network.newConsensusResults().results().stream()
                .mapToLong(SingleNodeConsensusResult::lastRoundNum)
                .max()
                .orElseThrow();

        timeManager.waitFor(Duration.ofSeconds(10));

        // Assert that no nodes make consensus progress during the partition
        assertThat(network.newConsensusResults()).haveLastRoundNum(partitionRound);

        // Network marker errors are acceptable during the partition, but no other errors.
        assertThat(network.newLogResults().suppressingLogMarker(LogMarker.NETWORK))
                .haveNoErrorLevelMessages();

        // Clear the log results so we can check for errors after healing the partition
        final MultipleNodeLogResults networkLogResults = network.newLogResults();
        networkLogResults.clear();

        network.restoreConnectivity();
        timeManager.waitFor(Duration.ofSeconds(10));

        // Check that there are no errors since healing the partition
        assertThat(networkLogResults).haveNoErrorLevelMessages();

        assertThat(network.newConsensusResults()).haveAdvancedSinceRound(partitionRound);
        assertThat(network.newPlatformStatusResults())
                .haveSteps(
                        target(ACTIVE).requiringInterim(REPLAYING_EVENTS, OBSERVING, CHECKING),
                        target(ACTIVE).requiringInterim(CHECKING));
        assertThat(network.newMarkerFileResults()).haveNoMarkerFiles();
        assertThat(network.newReconnectResults()).haveNoReconnects();
    }
}
