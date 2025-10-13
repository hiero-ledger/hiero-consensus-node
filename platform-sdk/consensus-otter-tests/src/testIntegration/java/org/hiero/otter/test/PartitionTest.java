// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.test;

import static com.swirlds.common.test.fixtures.WeightGenerators.TOTAL_WEIGHTS;
import static org.hiero.consensus.model.status.PlatformStatus.ACTIVE;
import static org.hiero.consensus.model.status.PlatformStatus.CHECKING;
import static org.hiero.consensus.model.status.PlatformStatus.OBSERVING;
import static org.hiero.consensus.model.status.PlatformStatus.REPLAYING_EVENTS;
import static org.hiero.otter.fixtures.OtterAssertions.assertThat;
import static org.hiero.otter.fixtures.assertions.StatusProgressionStep.target;

import com.swirlds.common.utility.Threshold;
import com.swirlds.logging.legacy.LogMarker;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Duration;
import java.util.List;
import java.util.stream.Stream;
import org.hiero.otter.fixtures.Network;
import org.hiero.otter.fixtures.Node;
import org.hiero.otter.fixtures.OtterTest;
import org.hiero.otter.fixtures.TestEnvironment;
import org.hiero.otter.fixtures.TimeManager;
import org.hiero.otter.fixtures.result.MultipleNodeLogResults;
import org.hiero.otter.fixtures.result.SingleNodeConsensusResult;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Tests for network partitions and their resolution.
 */
public class PartitionTest {

    /**
     * Various network weighting distributions and partitions that create a strong minority but not a supermajority.
     * @return the arguments for the parameterized test
     */
    public static Stream<Arguments> strongMinorityPartitions() {
        return Stream.of(
                // Min Stake profile
                Arguments.of(List.of(.3225, .2925, .05, .335), List.of(2, 3)),
                // Similar stake profile
                Arguments.of(List.of(.2625, .2375, .255, .245), List.of(1, 3)),
                // Uneven stake profile
                Arguments.of(List.of(.0625, .3200, .3175, .3000), List.of(2, 3))
        );
    }

    /**
     * Tests network partitions where the partitioned nodes are a strong minority of the total weight, but not a
     * supermajority. Neither partition should be able to make consensus progress, and no reconnects should be required
     * upon partition healing.
     *
     * @param weightFractions the fractions of total network weight for every node in the network - must sum to 1.0
     * @param partitionIndices the indices of the nodes to partition from the rest of the network, which must constitute
     * @param env the environment to test in
     * a strong minority but not a supermajority
     */
    @OtterTest
    @ParameterizedTest
    @MethodSource("strongMinorityPartitions")
    void testStrongMinorityNetworkPartition(@NonNull final List<Double> weightFractions,
            @NonNull final List<Integer> partitionIndices, @NonNull final TestEnvironment env) {
        throwIfInvalidArguments(weightFractions, partitionIndices);

        final Network network = env.network();
        final TimeManager timeManager = env.timeManager();

        network.addNodes(weightFractions.size());
        for (int i = 0; i < weightFractions.size(); i++) {
            network.nodes().get(i).weight(Math.round(TOTAL_WEIGHTS * weightFractions.get(i)));
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

    private void throwIfInvalidArguments(@NonNull final List<Double> weightFractions,
            @NonNull final List<Integer> partitionIndices) {
        final double totalWeightFraction = weightFractions.stream().mapToDouble(Double::doubleValue).sum();
        assertThat(totalWeightFraction)
                .withFailMessage("weight fractions do not sum to 1.0")
                .isEqualTo(1.0);
        final double partitionSum = partitionIndices.stream()
                .mapToDouble(weightFractions::get)
                .sum();
        assertThat(Threshold.STRONG_MINORITY.isSatisfiedBy(Math.round(TOTAL_WEIGHTS * partitionSum), TOTAL_WEIGHTS))
                .withFailMessage("partition is not a strong minority")
                .isTrue();
        assertThat(Threshold.SUPER_MAJORITY.isSatisfiedBy(Math.round(TOTAL_WEIGHTS * partitionSum), TOTAL_WEIGHTS))
                .withFailMessage("partition is a super majority")
                .isFalse();
    }
}
