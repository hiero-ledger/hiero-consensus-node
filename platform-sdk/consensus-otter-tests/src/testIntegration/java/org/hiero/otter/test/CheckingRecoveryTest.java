// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.test;

import static org.hiero.consensus.model.status.PlatformStatus.ACTIVE;
import static org.hiero.consensus.model.status.PlatformStatus.CHECKING;
import static org.hiero.consensus.model.status.PlatformStatus.OBSERVING;
import static org.hiero.consensus.model.status.PlatformStatus.REPLAYING_EVENTS;
import static org.hiero.otter.fixtures.OtterAssertions.assertContinuouslyThat;
import static org.hiero.otter.fixtures.OtterAssertions.assertThat;
import static org.hiero.otter.fixtures.assertions.StatusProgressionStep.target;

import com.swirlds.common.test.fixtures.WeightGenerators;
import com.swirlds.platform.wiring.PlatformSchedulersConfig_;
import java.time.Duration;
import java.util.List;
import org.hiero.otter.fixtures.Capability;
import org.hiero.otter.fixtures.Network;
import org.hiero.otter.fixtures.Node;
import org.hiero.otter.fixtures.OtterTest;
import org.hiero.otter.fixtures.TestEnvironment;
import org.hiero.otter.fixtures.TimeManager;

/**
 * Tests to verify that a node can recover from {@link org.hiero.consensus.model.status.PlatformStatus#CHECKING} status
 * after a period of synthetic bottlenecking.
 */
public class CheckingRecoveryTest {

    /**
     * Test to verify that a node can recover from {@link org.hiero.consensus.model.status.PlatformStatus#CHECKING}
     * status after a period of synthetic bottlenecking.
     *
     * @param env the test environment for this test
     */
    @OtterTest(requires = Capability.BACK_PRESSURE)
    void testCheckingRecovery(final TestEnvironment env) {
        final Network network = env.network();
        final TimeManager timeManager = env.timeManager();

        // Setup simulation

        // Add more than 3 nodes with balanced weights so that one node can be lost without halting consensus
        network.setWeightGenerator(WeightGenerators.BALANCED);
        final List<Node> nodes = network.addNodes(4);
        // For this test to work, we need to lower the limit for the transaction handler component
        // With the new limit set, once the transaction handler has 100 pending transactions, the node will stop
        // gossipping and stop creating events. This will cause the node to go into the checking state.
        nodes.stream()
                .map(Node::configuration)
                .forEach(c -> c.set(
                        PlatformSchedulersConfig_.TRANSACTION_HANDLER,
                        "SEQUENTIAL_THREAD CAPACITY(100) FLUSHABLE SQUELCHABLE"));

        assertContinuouslyThat(network.newConsensusResults()).haveEqualRounds();
        network.start();

        // Run the nodes for some time
        timeManager.waitFor(Duration.ofSeconds(30L));

        final Node nodeToThrottle = network.nodes().getLast();
        assertThat(nodeToThrottle.newPlatformStatusResult())
                .hasSteps(target(ACTIVE).requiringInterim(REPLAYING_EVENTS, OBSERVING, CHECKING));

        // Throttle the last node for a period of time so that it falls into CHECKING
        nodeToThrottle.startSyntheticBottleneck(Duration.ofSeconds(30));
        timeManager.waitForCondition(
                nodeToThrottle::isChecking,
                Duration.ofMinutes(2),
                "Node did not enter CHECKING status within the expected time frame after synthetic bottleneck was enabled.");
        nodeToThrottle.stopSyntheticBottleneck();

        // Verify that the node recovers when the bottleneck is lifted
        timeManager.waitForCondition(
                nodeToThrottle::isActive,
                Duration.ofSeconds(60L),
                "Node did not recover from CHECKING status within the expected time frame after synthetic bottleneck was disabled.");
    }
}
