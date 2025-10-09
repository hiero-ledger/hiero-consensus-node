package org.hiero.otter.test;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Duration;
import java.util.List;
import java.util.stream.Stream;
import org.hiero.otter.fixtures.Network;
import org.hiero.otter.fixtures.Node;
import org.hiero.otter.fixtures.OtterTest;
import org.hiero.otter.fixtures.TestEnvironment;
import org.hiero.otter.fixtures.TimeManager;
import org.hiero.otter.fixtures.result.SingleNodeConsensusResult;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import static org.hiero.otter.fixtures.OtterAssertions.assertThat;

public class PartitionTest {

    public static Stream<Arguments> strongMinorityPartitions() {
        return Stream.of(Arguments.of(List.of(625, 3200, 3175, 3000), List.of(2, 3)));
    }

    @ParameterizedTest
    @MethodSource("strongMinorityPartitions")
    @OtterTest
    void testStrongMinorityNetworkPartition(@NonNull final TestEnvironment env, @NonNull final List<Long> weights,
            @NonNull final List<Integer> partitionIndices) {
        final Network network = env.network();
        final TimeManager timeManager = env.timeManager();

        network.addNodes(weights.size());
        for (int i = 0; i < weights.size(); i++) {
            network.nodes().get(i).weight(weights.get(i));
        }

        network.start();

        final List<Node> nodesToPartition = partitionIndices.stream().map(i -> network.nodes().get(i)).toList();

        network.createNetworkPartition(nodesToPartition);

        final long partitionRound = network.newConsensusResults().results().stream().mapToLong(
                SingleNodeConsensusResult::lastRoundNum).max().orElseThrow();

        timeManager.waitFor(Duration.ofSeconds(10));

        // Assert that no nodes make consensus progress during the partition
        assertThat(network.newConsensusResults()).haveLastRoundNum(partitionRound);

        network.restoreConnectivity();

        timeManager.waitFor(Duration.ofSeconds(10));

        assertThat(network.newConsensusResults()).haveAdvancedSinceRound(partitionRound);
    }
}
