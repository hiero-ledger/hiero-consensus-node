package org.hiero.otter.test;

import static org.assertj.core.data.Percentage.withPercentage;
import static org.hiero.otter.fixtures.OtterAssertions.assertThat;

import java.time.Duration;
import org.hiero.otter.fixtures.Network;
import org.hiero.otter.fixtures.OtterTest;
import org.hiero.otter.fixtures.TestEnvironment;
import org.hiero.otter.fixtures.TimeManager;

/**
 * This class contains examples that are used in the documentation. If you change the examples, please make sure
 * to update the documentation accordingly. This is done in an effort to ensure that the examples are up-to-date.
 */
class DocExamplesTest {

    // This test is used in the README.md and getting-started.md files.
    @OtterTest
    void testConsensus(final TestEnvironment env) throws InterruptedException {
        // 1. Get the network and time manager
        final Network network = env.network();
        final TimeManager timeManager = env.timeManager();

        // 2. Create a 4-node network
        network.addNodes(4);

        // 3. Start the network
        network.start();

        // 4. Wait 30 seconds while the network is running
        timeManager.waitFor(Duration.ofSeconds(30));

        // 5. Verify consensus was reached
        assertThat(network.getConsensusResults())
                .haveEqualCommonRounds()
                .haveMaxDifferenceInLastRoundNum(withPercentage(5));

        // 6. Check for no error-level log messages
        assertThat(network.getLogResults())
                .haveNoErrorLevelMessages();
    }
}
