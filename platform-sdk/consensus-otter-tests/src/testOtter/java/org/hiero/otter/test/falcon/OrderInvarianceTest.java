// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.test.falcon;

import static org.hiero.consensus.model.status.PlatformStatus.ACTIVE;
import static org.hiero.consensus.model.status.PlatformStatus.CHECKING;
import static org.hiero.consensus.model.status.PlatformStatus.OBSERVING;
import static org.hiero.consensus.model.status.PlatformStatus.REPLAYING_EVENTS;
import static org.hiero.consensus.test.fixtures.WeightGenerators.BALANCED;
import static org.hiero.consensus.test.fixtures.WeightGenerators.INCREMENTING;
import static org.hiero.otter.fixtures.OtterAssertions.assertThat;
import static org.hiero.otter.fixtures.assertions.StatusProgressionStep.target;
import static org.junit.jupiter.api.Named.named;
import static org.junit.jupiter.params.provider.Arguments.arguments;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Duration;
import java.util.stream.Stream;
import org.hiero.consensus.test.fixtures.WeightGenerator;
import org.hiero.otter.fixtures.FalconTest;
import org.hiero.otter.fixtures.Network;
import org.hiero.otter.fixtures.TestEnvironment;
import org.hiero.otter.fixtures.TimeManager;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Checks that all nodes reach the same consensus, no matter in which order they receive the events, over a range of
 * network shapes. This is the Falcon counterpart of {@code ConsensusTests.orderInvarianceTests} in
 * consensus-hashgraph-impl: every node of the simulated network receives the events of the others in its own order.
 */
public class OrderInvarianceTest {

    /**
     * The network shapes of {@code ConsensusTestArgs.orderInvarianceTests} in consensus-hashgraph-impl.
     *
     * @return the node count and weight generator of every network shape
     */
    static Stream<Arguments> networkShapes() {
        return Stream.of(
                arguments(2, named("Balanced Weight", BALANCED)),
                arguments(2, named("Incremental Node Weight", INCREMENTING)),
                arguments(4, named("Incremental Node Weight", INCREMENTING)));

        // Falcon is currently not capable of running tests with large networks efficiently,
        // so we skip the larger networks for now. The following tests are disabled until Falcon is improved.
        // spotless:off
        //      arguments(9, named("One Third of Nodes Have Zero Weight", ONE_THIRD_ZERO_WEIGHT)),
        //      arguments(50, named("Random Weight, Real Total Weight Value", RANDOM_REAL_WEIGHT)),
        //      arguments(50, named("Random Weight", RANDOM)));
        // spotless:on
    }

    /**
     * Runs a network of the given shape for some time and checks that all nodes agree on the consensus rounds.
     *
     * @param nodeCount the number of nodes in the network
     * @param weightGenerator the generator of the weights of the nodes
     * @param env the test environment for this test
     */
    @FalconTest
    @MethodSource("networkShapes")
    void orderInvariance(
            final int nodeCount, @NonNull final WeightGenerator weightGenerator, @NonNull final TestEnvironment env) {
        final Network network = env.network();
        final TimeManager timeManager = env.timeManager();

        // Setup simulation
        network.weightGenerator(weightGenerator);
        network.addNodes(nodeCount);

        // Start simulation
        network.start();

        // Run the network for 10s simulated time
        timeManager.waitFor(Duration.ofSeconds(10L));

        // Validations
        assertThat(network.newConsensusResults()).haveEqualCommonRounds().haveConsistentRounds();
        assertThat(network.newPlatformStatusResults())
                .haveSteps(target(ACTIVE).requiringInterim(REPLAYING_EVENTS, OBSERVING, CHECKING));
    }
}
