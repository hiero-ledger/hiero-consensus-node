package org.hiero.otter.test;

import com.swirlds.platform.config.StateConfig_;
import com.swirlds.platform.state.iss.DefaultIssDetector;
import edu.umd.cs.findbugs.annotations.NonNull;
import org.hiero.otter.fixtures.Network;
import org.hiero.otter.fixtures.OtterTest;
import org.hiero.otter.fixtures.TestEnvironment;
import org.hiero.otter.fixtures.TimeManager;

import static org.hiero.consensus.model.status.PlatformStatus.ACTIVE;
import static org.hiero.consensus.model.status.PlatformStatus.CATASTROPHIC_FAILURE;
import static org.hiero.consensus.model.status.PlatformStatus.CHECKING;
import static org.hiero.consensus.model.status.PlatformStatus.OBSERVING;
import static org.hiero.consensus.model.status.PlatformStatus.REPLAYING_EVENTS;
import static org.hiero.otter.fixtures.OtterAssertions.assertThat;
import static org.hiero.otter.fixtures.assertions.StatusProgressionStep.target;
import static org.hiero.otter.fixtures.assertions.StatusProgressionStep.targets;

public class IssTest {

    @OtterTest
    void testSelfIss(@NonNull final TestEnvironment env) {
        final Network network = env.network();

        // Setup simulation
        network.addNodes(4);

//        network.withConfigValue(StateConfig_.HALT_ON_CATASTROPHIC_ISS, true);

        network.start();

        network.triggerSingleNodeIss(network.nodes().getFirst());

        assertThat(network.newLogResults().suppressingLoggerClass(DefaultIssDetector.class)).haveNoErrorLevelMessages();

    }

    @OtterTest
    void testCatastrophicIssWithoutHalt(@NonNull final TestEnvironment env) {
        final Network network = env.network();

        // Setup simulation
        network.addNodes(4);

        network.withConfigValue(StateConfig_.HALT_ON_CATASTROPHIC_ISS, false);

        network.start();

        network.triggerCatastrophicIss();

        assertThat(network.newPlatformStatusResults()).haveSteps(
                target(ACTIVE).requiringInterim(REPLAYING_EVENTS, OBSERVING, CHECKING),
                target(CATASTROPHIC_FAILURE));
        assertThat(network.newLogResults().suppressingLoggerClass(DefaultIssDetector.class)).haveNoErrorLevelMessages();
    }

    @OtterTest
    void testCatastrophicIssWithHalt(@NonNull final TestEnvironment env) {
        final Network network = env.network();

        // Setup simulation
        network.addNodes(4);

        network.withConfigValue(StateConfig_.HALT_ON_CATASTROPHIC_ISS, true);

        network.start();

        network.triggerCatastrophicIss();

        assertThat(network.newPlatformStatusResults()).haveSteps(
                target(ACTIVE).requiringInterim(REPLAYING_EVENTS, OBSERVING, CHECKING),
                targets(CHECKING, CATASTROPHIC_FAILURE));
        assertThat(network.newLogResults().suppressingLoggerClass(DefaultIssDetector.class)).haveNoErrorLevelMessages();
    }
}
