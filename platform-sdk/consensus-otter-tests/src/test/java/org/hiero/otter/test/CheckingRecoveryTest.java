package org.hiero.otter.test;

import com.swirlds.common.test.fixtures.WeightGenerators;
import java.time.Duration;
import org.hiero.consensus.model.status.PlatformStatus;
import org.hiero.otter.fixtures.Network;
import org.hiero.otter.fixtures.Node;
import org.hiero.otter.fixtures.OtterTest;
import org.hiero.otter.fixtures.TestEnvironment;
import org.hiero.otter.fixtures.TimeManager;

import static org.assertj.core.api.Fail.fail;
import static org.hiero.consensus.model.status.PlatformStatus.ACTIVE;
import static org.hiero.consensus.model.status.PlatformStatus.CHECKING;
import static org.hiero.consensus.model.status.PlatformStatus.OBSERVING;
import static org.hiero.consensus.model.status.PlatformStatus.REPLAYING_EVENTS;
import static org.hiero.otter.fixtures.OtterAssertions.assertContinuouslyThat;
import static org.hiero.otter.fixtures.OtterAssertions.assertThat;
import static org.hiero.otter.fixtures.assertions.StatusProgressionStep.target;

public class CheckingRecoveryTest {

    @OtterTest
    void testCheckingRecovery(final TestEnvironment env) throws InterruptedException {
        final Network network = env.network();
        final TimeManager timeManager = env.timeManager();

        // Setup simulation

        // Add more than 3 nodes with balanced weights so that one node can be lost without halting consensus
        network.addNodes(4, WeightGenerators.BALANCED);

        assertContinuouslyThat(network.getConsensusResults()).haveEqualRounds();
        network.start();

        // Run the nodes for some time
        timeManager.waitFor(Duration.ofSeconds(30L));

        final Node nodeToThrottle = network.getNodes().getLast();
        assertThat(nodeToThrottle.getPlatformStatusResults())
                .hasSteps(target(ACTIVE).requiringInterim(REPLAYING_EVENTS, OBSERVING, CHECKING));
        nodeToThrottle.getPlatformStatusResults().clear();

        // Throttle the last node for a period of time so that it falls into CHECKING
        nodeToThrottle.startSyntheticBottleneck();
        if (!timeManager.waitForCondition(nodeToThrottle::isChecking, Duration.ofSeconds(30L))) {
            fail("Node did not enter CHECKING status within the expected time frame after synthetic bottleneck was enabled.");
        }
        nodeToThrottle.stopSyntheticBottleneck();

        // Verify that the node recovers when the bottleneck is lifted
        if (!timeManager.waitForCondition(nodeToThrottle::isActive, Duration.ofSeconds(30L))) {
            fail("Node did not recover from CHECKING status within the expected time frame after synthetic bottleneck was disabled.");
        }
    }
}
