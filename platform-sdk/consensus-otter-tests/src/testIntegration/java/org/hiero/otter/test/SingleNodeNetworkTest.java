package org.hiero.otter.test;

import static org.hiero.otter.fixtures.assertions.MultipleNodeLogResultsAssert.assertThat;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Duration;
import org.assertj.core.api.Assertions;
import org.hiero.otter.fixtures.Network;
import org.hiero.otter.fixtures.OtterTest;
import org.hiero.otter.fixtures.TestEnvironment;
import org.hiero.otter.fixtures.TimeManager;

/**
 * Tests basic functionality of a single node network. Single node networks would probably never be used in production,
 * but they can be useful for testing and are officially supported.
 */
public class SingleNodeNetworkTest {

    /**
     * A basic test that a single node network can reach consensus and freeze correctly.
     */
    @OtterTest
    void testSingleNodeNetwork(@NonNull final TestEnvironment env) {
        final Network network = env.network();
        final TimeManager timeManager = env.timeManager();

        network.addNodes(1);
        network.start();
        // Let the single node run for a short time
        timeManager.waitFor(Duration.ofSeconds(10));
        network.freeze();
        final long freezeRound = network.nodes().getFirst().newConsensusResult().lastRoundNum();
        network.shutdown();
        Assertions
                .assertThat(freezeRound)
                .withFailMessage("10 seconds should be enough time for a single node to reach at least round 20")
                .isGreaterThan(20);
        assertThat(network.newLogResults()).haveNoErrorLevelMessages();
    }
}
