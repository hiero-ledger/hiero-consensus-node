// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.test;

import static org.assertj.core.data.Percentage.withPercentage;
import static org.hiero.otter.fixtures.OtterAssertions.assertThat;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Duration;
import org.hiero.otter.fixtures.Network;
import org.hiero.otter.fixtures.OtterTest;
import org.hiero.otter.fixtures.TestEnvironment;
import org.hiero.otter.fixtures.TimeManager;
import org.hiero.otter.fixtures.turtle.TurtleSpecs;

/**
 * This class contains examples that are used in the documentation. If you change the examples, please make sure
 * to update the documentation accordingly. This is done in an effort to ensure that the examples are up-to-date.
 */
class DocExamplesTest {

    // This test is used in the README.md and getting-started.md files.
    @OtterTest
    void testConsensus(@NonNull final TestEnvironment env) throws InterruptedException {
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
        assertThat(network.getLogResults()).haveNoErrorLevelMessages();
    }

    // This test is used in the turtle-environment.md
    @OtterTest
    @TurtleSpecs(randomSeed = 42)
    void testDeterministicBehavior(@NonNull final TestEnvironment env) throws InterruptedException {
        // This test will produce identical results every time
        final Network network = env.network();
        network.addNodes(4);
        network.start();

        env.timeManager().waitFor(Duration.ofSeconds(30));

        // Results will be identical across runs
        final long lastRound = network.getConsensusResults().results().get(0).lastRoundNum();

        // This assertion will always pass with seed=42
        assertThat(lastRound).isEqualTo(35);
    }
}
