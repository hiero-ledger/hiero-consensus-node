package org.hiero.otter.test;

import static org.assertj.core.data.Percentage.withPercentage;
import static org.hiero.otter.fixtures.OtterAssertions.assertThat;

import java.time.Duration;
import org.hiero.otter.fixtures.Network;
import org.hiero.otter.fixtures.OtterTest;
import org.hiero.otter.fixtures.TestEnvironment;

/**
 * This class contains examples that are used in the documentation. If you change the examples, please make sure
 * to update the documentation accordingly. This is done in an effort to ensure that the examples are up-to-date.
 */
class DocExamplesTest {

    // This test is used in the README.md file.
    @OtterTest
    void testConsensus(final TestEnvironment env) throws InterruptedException {
        // Define a network with 4 nodes
        final Network network = env.network();
        network.addNodes(4);

        // Start the network
        network.start();

        // Wait for 30 seconds to allow the consensus to progress
        env.timeManager().waitFor(Duration.ofSeconds(30));

        // Validate the results
        assertThat(network.getConsensusResults())
                .haveEqualCommonRounds()
                .haveMaxDifferenceInLastRoundNum(withPercentage(5));
    }
}
