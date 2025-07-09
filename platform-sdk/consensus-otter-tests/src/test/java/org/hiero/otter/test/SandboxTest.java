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

import edu.umd.cs.findbugs.annotations.NonNull;
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

/**
 * Collection of tests to try out the API. The tests are not meant to be run and most likely fail.
 */
public class SandboxTest {

    private static final Duration TEN_SECONDS = Duration.ofSeconds(10);
    private static final Duration ONE_MINUTE = Duration.ofMinutes(1);
    private static final Duration TWO_MINUTES = Duration.ofMinutes(2);

    /**
     * Example of a migrated JRS test.
     *
     * @param env the test environment for this test
     * @throws InterruptedException if an operation times out
     */
    @OtterTest
    @Disabled("Sandbox test, not meant to be run")
    void testConsistencyNDReconnect(@NonNull final TestEnvironment env) throws InterruptedException {
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

    /**
     * Example of a migrated {@code ConsensusTest}.
     *
     * @param env the test environment for this test
     * @throws InterruptedException if an operation times out
     */
    @OtterTest
    @Disabled("Sandbox test, not meant to be run")
    void testBranching(@NonNull final TestEnvironment env) throws InterruptedException {
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

    /**
     * A catch-all test to try out the API.
     *
     * @param env the test environment for this test
     * @throws InterruptedException if an operation times out
     */
    @Disabled("Sandbox test, not meant to be run")
    @OtterTest
    void testApi(@NonNull final TestEnvironment env) throws InterruptedException {
        final Network network = env.network();
        final TimeManager timeManager = env.timeManager();

        // Setup simulation
        network.addNodes(4);
        assertContinuouslyThat(network.getConsensusResults()).haveEqualRounds();
        assertContinuouslyThat(network.getLogResults())
                .haveNoMessageWithMarkers(STARTUP)
                .haveNoMessageWithLevelHigherThan(Level.INFO)
                .haveNoErrorLevelMessages();
        assertContinuouslyThat(network.getPlatformStatusResults())
                .doNotEnterAnyStatusesOf(CHECKING)
                .doOnlyEnterStatusesOf(ACTIVE, REPLAYING_EVENTS, OBSERVING);
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

        assertThat(network.getConsensusResults())
                .haveEqualCommonRounds()
                .haveMaxDifferenceInLastRoundNum(Percentage.withPercentage(1));
    }
}
