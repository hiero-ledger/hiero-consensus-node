// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.test;

import static com.swirlds.logging.legacy.LogMarker.SOCKET_EXCEPTIONS;
import static com.swirlds.logging.legacy.LogMarker.STARTUP;
import static com.swirlds.logging.legacy.LogMarker.TESTING_EXCEPTIONS_ACCEPTABLE_RECONNECT;
import static org.hiero.consensus.model.status.PlatformStatus.ACTIVE;
import static org.hiero.consensus.model.status.PlatformStatus.CHECKING;
import static org.hiero.consensus.model.status.PlatformStatus.OBSERVING;
import static org.hiero.consensus.model.status.PlatformStatus.REPLAYING_EVENTS;
import static org.hiero.otter.fixtures.OtterAssertions.assertContinuouslyThat;
import static org.hiero.otter.fixtures.OtterAssertions.assertThat;
import static org.hiero.otter.fixtures.assertions.StatusProgressionStep.target;

import java.time.Duration;
import java.util.List;
import org.apache.logging.log4j.Level;
import org.assertj.core.data.Percentage;
import org.hiero.otter.fixtures.InstrumentedNode;
import org.hiero.otter.fixtures.Network;
import org.hiero.otter.fixtures.Node;
import org.hiero.otter.fixtures.OtterTest;
import org.hiero.otter.fixtures.TestEnvironment;
import org.hiero.otter.fixtures.TimeManager;
import org.hiero.otter.fixtures.result.MultipleNodeLogResults;
import org.junit.jupiter.api.Disabled;

public class SandboxTest {

    private static final Duration TEN_SECONDS = Duration.ofSeconds(10);
    private static final Duration ONE_MINUTE = Duration.ofMinutes(1);
    private static final Duration TWO_MINUTES = Duration.ofMinutes(2);

    @OtterTest
    @Disabled
    void testConsistencyNDReconnect(TestEnvironment env) throws InterruptedException {
        final Network network = env.network();
        final TimeManager timeManager = env.timeManager();

        // Setup simulation
        final List<Node> nodes = network.addNodes(4);
        network.start();

        // Wait for two minutes
        timeManager.waitFor(TWO_MINUTES);

        // Kill node
        final Node node = nodes.getFirst();
        node.killImmediately();

        // Wait for two minutes
        timeManager.waitFor(TWO_MINUTES);

        // Revive node
        node.start();

        // Wait for two minutes
        timeManager.waitFor(TWO_MINUTES);

        // Validations
        assertThat(network.getLogResults()
                        .suppressingLogMarker(SOCKET_EXCEPTIONS)
                        .suppressingLogMarker(TESTING_EXCEPTIONS_ACCEPTABLE_RECONNECT))
                .haveNoMessagesWithLevelHigherThan(Level.INFO);
    }

    @OtterTest
    @Disabled
    void testBranching(TestEnvironment env) throws InterruptedException {
        final Network network = env.network();
        final TimeManager timeManager = env.timeManager();

        // Setup simulation
        network.addNodes(3);
        final InstrumentedNode nodeX = network.addInstrumentedNode();
        network.start();

        // Wait for ten seconds
        timeManager.waitFor(TEN_SECONDS);

        // Start branching
        nodeX.setBranchingProbability(0.5);

        // Wait for one minute
        timeManager.waitFor(ONE_MINUTE);
    }

    @Disabled
    @OtterTest
    void testHappyPath(TestEnvironment env) throws InterruptedException {
        final Network network = env.network();
        final TimeManager timeManager = env.timeManager();

        // Setup simulation
        network.addNodes(4);
        assertContinuouslyThat(network.getConsensusResults()).haveEqualRounds();
        network.start();

        // Wait for two minutes
        timeManager.waitFor(Duration.ofMinutes(1L));

        // Validations
        final MultipleNodeLogResults logResults = network.getLogResults()
                .suppressingNode(network.getNodes().getFirst())
                .suppressingLogMarker(STARTUP);
        assertThat(logResults).haveNoMessagesWithLevelHigherThan(Level.WARN);

        assertThat(network.getPlatformStatusResults())
                .haveSteps(target(ACTIVE).requiringInterim(REPLAYING_EVENTS, OBSERVING, CHECKING));

        assertThat(network.getPcesResults()).haveAllBirthRoundsEqualTo(1);

        assertThat(network.getConsensusResults()).haveEqualRoundsIgnoringLast(Percentage.withPercentage(1));
    }
}
