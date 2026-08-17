// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.test.falcon;

import static org.hiero.otter.fixtures.OtterAssertions.assertContinuouslyThat;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Duration;
import java.util.List;
import org.hiero.otter.fixtures.FalconTest;
import org.hiero.otter.fixtures.Network;
import org.hiero.otter.fixtures.Node;
import org.hiero.otter.fixtures.TestEnvironment;
import org.hiero.otter.fixtures.TimeManager;

/**
 * The simplest sanity test for falcon tests.
 */
public class SmokeTest {

    /**
     * Simple test that runs a network with 4 nodes for some time and does some basic validations.
     *
     * @param env the test environment for this test
     */
    @FalconTest
    void smokeTest(@NonNull final TestEnvironment env) {
        final Network network = env.network();
        final TimeManager timeManager = env.timeManager();

        // Setup simulation
        final List<Node> nodes = network.addNodes(4);

        // Setup continuous assertions
        assertContinuouslyThat(network.newConsensusResults())
                .haveEqualCommonRounds()
                .haveConsistentRounds();

        // Start simulation
        network.start();

        // Run the network for 5s simulated time
        timeManager.waitFor(Duration.ofSeconds(5L));
    }
}
