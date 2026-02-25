// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.test;

import static org.hiero.otter.fixtures.OtterAssertions.assertContinuouslyThat;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Duration;
import java.util.List;
import org.hiero.otter.fixtures.Network;
import org.hiero.otter.fixtures.Node;
import org.hiero.otter.fixtures.OtterTest;
import org.hiero.otter.fixtures.TestEnvironment;
import org.hiero.otter.fixtures.TimeManager;
import org.hiero.otter.fixtures.network.LatencyRange;
import org.junit.jupiter.api.Disabled;

/**
 * Test the rpc connectivity, but continuously connecting and disconnecting one, while it has increased latency. This
 * test tries to stress handling of cleanup of old sync state and setting up new one.
 */
@Disabled
public class RpcDisconnectionStressTest {

    @OtterTest
    void testRpcDisconnections(@NonNull final TestEnvironment env) {
        final Network network = env.network();
        final TimeManager timeManager = env.timeManager();

        final LatencyRange higherLatency = LatencyRange.of(Duration.ofMillis(800));

        // Setup simulation
        network.addNodes(10);

        // Setup continuous assertions
        assertContinuouslyThat(network.newLogResults()).haveNoErrorLevelMessages();
        assertContinuouslyThat(network.newReconnectResults()).doNotAttemptToReconnect();

        network.start();

        final List<Node> candidates = network.nodes().stream().toList();

        final Node targetNode = candidates.get(candidates.size() / 2);

        network.setLatencyForAllConnections(targetNode, higherLatency);

        timeManager.waitFor(Duration.ofSeconds(5L));

        for (int i = 0; i < 100; i++) {
            network.isolate(targetNode);
            timeManager.waitFor(Duration.ofSeconds(1L));
            network.rejoin(targetNode);
            network.setLatencyForAllConnections(targetNode, higherLatency);
            timeManager.waitFor(Duration.ofMillis(1500 + (int) (Math.random() * 1000)));
        }
    }
}
