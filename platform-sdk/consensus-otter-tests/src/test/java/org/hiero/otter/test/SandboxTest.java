// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.test;

import static org.hiero.otter.fixtures.Validator.EventStreamConfig.ignoreNode;
import static org.hiero.otter.fixtures.Validator.LogErrorConfig.ignoreMarkers;
import static org.hiero.otter.fixtures.Validator.RatioConfig.within;

import com.swirlds.logging.legacy.LogMarker;
import java.time.Duration;
import java.util.List;
import org.hiero.otter.fixtures.InstrumentedNode;
import org.hiero.otter.fixtures.Network;
import org.hiero.otter.fixtures.Node;
import org.hiero.otter.fixtures.OtterTest;
import org.hiero.otter.fixtures.TestEnvironment;
import org.hiero.otter.fixtures.TimeManager;
import org.hiero.otter.fixtures.Validator.Profile;

public class SandboxTest {

    private static final Duration TEN_SECONDS = Duration.ofSeconds(10);
    private static final Duration ONE_MINUTE = Duration.ofMinutes(1);
    private static final Duration TWO_MINUTES = Duration.ofMinutes(2);

    @OtterTest
    void testConsistencyNDReconnect(TestEnvironment env) throws InterruptedException {
        final Network network = env.network();
        final TimeManager timeManager = env.timeManager();

        // Setup simulation
        final List<Node> nodes = network.addNodes(4);
        network.start(ONE_MINUTE);
        env.generator().start();

        // Wait for two minutes
        timeManager.waitFor(TWO_MINUTES);

        // Kill node
        final Node node = nodes.getFirst();
        node.kill(ONE_MINUTE);

        // Wait for two minutes
        timeManager.waitFor(TWO_MINUTES);

        // Revive node
        node.revive(ONE_MINUTE);

        // Wait for two minutes
        timeManager.waitFor(TWO_MINUTES);

        // Validations
        env.validator()
                .assertLogErrors(
                        ignoreMarkers(LogMarker.SOCKET_EXCEPTIONS, LogMarker.TESTING_EXCEPTIONS_ACCEPTABLE_RECONNECT))
                .assertStdOut()
                .eventStream(ignoreNode(node))
                .reconnectEventStream(node)
                .validateRemaining(Profile.DEFAULT);
    }

    @OtterTest
    void testBranching(TestEnvironment env) throws InterruptedException {
        final Network network = env.network();
        final TimeManager timeManager = env.timeManager();

        // Setup simulation
        network.addNodes(3);
        final InstrumentedNode nodeX = network.addInstrumentedNode();
        network.start(ONE_MINUTE);
        env.generator().start();

        // Wait for one minute
        timeManager.waitFor(TEN_SECONDS);

        // Start branching
        nodeX.setBranchingProbability(0.5);

        // Wait for one minute
        timeManager.waitFor(ONE_MINUTE);

        // Validations
        env.validator()
                .consensusRatio(within(0.8, 1.0))
                .staleRatio(within(0.0, 0.1))
                .validateRemaining(Profile.HASHGRAPH);
    }
}
