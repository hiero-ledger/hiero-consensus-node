// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.test;

import static org.hiero.consensus.model.status.PlatformStatus.ACTIVE;
import static org.hiero.consensus.model.status.PlatformStatus.CATASTROPHIC_FAILURE;
import static org.hiero.consensus.model.status.PlatformStatus.CHECKING;
import static org.hiero.consensus.model.status.PlatformStatus.OBSERVING;
import static org.hiero.consensus.model.status.PlatformStatus.REPLAYING_EVENTS;
import static org.hiero.otter.fixtures.OtterAssertions.assertThat;
import static org.hiero.otter.fixtures.assertions.StatusProgressionStep.target;
import static org.hiero.otter.fixtures.assertions.StatusProgressionStep.targets;

import com.swirlds.platform.config.StateConfig_;
import com.swirlds.platform.state.iss.DefaultIssDetector;
import edu.umd.cs.findbugs.annotations.NonNull;
import org.hiero.otter.fixtures.Network;
import org.hiero.otter.fixtures.Node;
import org.hiero.otter.fixtures.OtterTest;
import org.hiero.otter.fixtures.TestEnvironment;

public class IssTest {

    @OtterTest
    void testSelfIss(@NonNull final TestEnvironment env) {
        final Network network = env.network();

        // Setup simulation
        network.addNodes(4);

        //        network.withConfigValue(StateConfig_.HALT_ON_CATASTROPHIC_ISS, true);

        network.start();

        final Node issNode = network.nodes().getFirst();
        issNode.triggerSingleNodeIss();

        assertThat(issNode.newLogResult().suppressingLoggerName(DefaultIssDetector.class))
                .hasNoErrorLevelMessages();
        assertThat(network.newLogResults().suppressingNode(issNode)).haveNoErrorLevelMessages();
    }

    /**
     * Triggers a catastrophic ISS and verifies that all nodes in the network enter the CATASTROPHIC_FAILURE state. This
     * is expected when the network is not configured to halt on such an ISS.
     *
     * @param env the environment to test in
     */
    @OtterTest
    void testCatastrophicIssWithoutHalt(@NonNull final TestEnvironment env) {
        final Network network = env.network();

        // Setup simulation
        network.addNodes(4);

        network.withConfigValue(StateConfig_.HALT_ON_CATASTROPHIC_ISS, false);

        network.start();

        network.triggerCatastrophicIss();

        assertThat(network.newPlatformStatusResults())
                .haveSteps(
                        target(ACTIVE).requiringInterim(REPLAYING_EVENTS, OBSERVING, CHECKING),
                        target(CATASTROPHIC_FAILURE));
        assertThat(network.newLogResults().suppressingLoggerName(DefaultIssDetector.class))
                .haveNoErrorLevelMessages();
    }

    /**
     * Triggers a catastrophic ISS and verifies that all nodes in the network enter the CATASTROPHIC_FAILURE or CHECKING
     * state. This is expected when the network is configured to halt on such an ISS. One node will be the first to
     * detect the catastrophic ISS and halt gossip. Once enough nodes have done this, the other nodes will not be able
     * to proceed in consensus and may not detect the ISS. Therefore, they enter CHECKING instead.
     *
     * @param env the environment to test in
     */
    @OtterTest
    void testCatastrophicIssWithHalt(@NonNull final TestEnvironment env) {
        final Network network = env.network();

        network.addNodes(4);

        network.withConfigValue(StateConfig_.HALT_ON_CATASTROPHIC_ISS, true);

        network.start();

        network.triggerCatastrophicIss();

        assertThat(network.newPlatformStatusResults())
                .haveSteps(
                        target(ACTIVE).requiringInterim(REPLAYING_EVENTS, OBSERVING, CHECKING),
                        targets(CHECKING, CATASTROPHIC_FAILURE));
        assertThat(network.newLogResults().suppressingLoggerName(DefaultIssDetector.class))
                .haveNoErrorLevelMessages();
    }
}
