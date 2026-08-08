// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.test.falcon;

import static org.hiero.otter.fixtures.OtterAssertions.assertContinuouslyThat;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import org.hiero.otter.fixtures.FalconTest;
import org.hiero.otter.fixtures.Network;
import org.hiero.otter.fixtures.Node;
import org.hiero.otter.fixtures.TestEnvironment;
import org.hiero.otter.fixtures.TimeManager;
import org.hiero.otter.fixtures.result.SubscriberAction;

/**
 * The simplest sanity test for falcon tests.
 */
public class SmokeTest {

    /**
     * Simple test that runs a network with 4 nodes for some time and does some basic validations.
     *
     * @param env the test environment for this test
     */
    @FalconTest(repetition = 100)
    void smokeTest(@NonNull final TestEnvironment env) {
        final Network network = env.network();
        final TimeManager timeManager = env.timeManager();

        // Setup simulation
        final List<Node> nodes = network.addNodes(4);

        // Setup continuous assertions
        assertContinuouslyThat(network.newConsensusResults())
                .haveEqualCommonRounds()
                .haveConsistentRounds();

        // Setup counter
        final AtomicLong counter = new AtomicLong();
        nodes.getFirst().newConsensusResult().subscribe((_, round) -> {
            counter.addAndGet(round.getEventCount());
            return SubscriberAction.CONTINUE;
        });

        // Start simulation
        network.start();

        // Create 1000 events
        final BooleanSupplier condition = () -> counter.get() >= 10_000;
        timeManager.waitForCondition(condition, Duration.ofMinutes(10L));
    }
}
