// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.test;

import static com.swirlds.logging.legacy.LogMarker.RECONNECT;
import static com.swirlds.logging.legacy.LogMarker.STARTUP;
import static org.hiero.otter.fixtures.OtterAssertions.assertContinuouslyThat;
import static org.hiero.otter.fixtures.OtterAssertions.assertThat;

import com.swirlds.platform.consensus.ConsensusConfig_;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Duration;
import java.util.List;
import org.hiero.otter.fixtures.Capability;
import org.hiero.otter.fixtures.Network;
import org.hiero.otter.fixtures.Node;
import org.hiero.otter.fixtures.OtterTest;
import org.hiero.otter.fixtures.TestEnvironment;
import org.hiero.otter.fixtures.TimeManager;
import org.hiero.otter.fixtures.assertions.MultipleNodeLogResultsContinuousAssert;
import org.hiero.otter.fixtures.result.SingleNodeConsensusResult;
import org.hiero.otter.fixtures.turtle.TurtleSpecs;

/**
 * This class contains examples that are used in the documentation. If you change the examples, please make sure to
 * update the documentation accordingly. This is done in an effort to ensure that the examples are up-to-date.
 */
class DocExamplesTest {

    // This test is used in the README.md, the getting-started.md, and the writing-tests.md files.
    @OtterTest
    void testConsensus(@NonNull final TestEnvironment env) {
        // 1. Get the network and time manager
        final Network network = env.network();
        final TimeManager timeManager = env.timeManager();

        // 2. Create a 4-node network
        network.addNodes(4);

        // 3. Start the network
        network.start();

        // 4. Wait 30 seconds while the network is running
        timeManager.waitFor(Duration.ofSeconds(30));

        // 5. Check for no error-level log messages
        assertThat(network.newLogResults()).haveNoErrorLevelMessages();
    }

    // This test is used in the turtle-environment.md file.
    @OtterTest
    @TurtleSpecs(randomSeed = 42)
    void testDeterministicBehavior(@NonNull final TestEnvironment env) {
        // This test will produce identical results every time
        final Network network = env.network();
        network.addNodes(4);
        network.start();

        env.timeManager().waitFor(Duration.ofSeconds(30));

        // Results will be identical across runs
        final long lastRound =
                network.newConsensusResults().results().getFirst().lastRoundNum();

        // This assertion will always pass with seed=42
        assertThat(lastRound).isEqualTo(37);
    }

    // This test is used in the writing-tests.md file.
    // This test requires the capability to reconnect nodes
    @OtterTest(requires = Capability.RECONNECT)
    void testSimpleNodeDeathReconnect(@NonNull final TestEnvironment env) {
        // ... more test logic here ...
    }

    // This test is used in writing-tests.md file.
    @OtterTest
    void testNodeConfiguration(@NonNull final TestEnvironment env) {
        final Network network = env.network();
        final List<Node> nodes = network.addNodes(4);

        // Set the rounds non-ancient and expired to smaller values to allow nodes to fall behind quickly
        for (final Node node : nodes) {
            node.configuration().set(ConsensusConfig_.ROUNDS_NON_ANCIENT, 5L).set(ConsensusConfig_.ROUNDS_EXPIRED, 10L);
        }

        network.start();

        // ... more test logic here ...
    }

    // This test is used in the writing-tests.md file.
    @OtterTest
    void testSuppressingResults(@NonNull final TestEnvironment env) {
        final Network network = env.network();
        final List<Node> nodes = network.addNodes(4);
        final Node node = nodes.getFirst();
        final Node problematicNode = nodes.getFirst();

        // Ignore specific nodes
        assertThat(network.newLogResults().suppressingNode(problematicNode)).haveNoErrorLevelMessages();

        // Filter out expected log markers
        assertThat(network.newLogResults().suppressingLogMarker(STARTUP)).haveNoErrorLevelMessages();

        // Clear accumulated data
        final SingleNodeConsensusResult consensusResults = node.newConsensusResult();
        consensusResults.clear();
        assertThat(consensusResults.consensusRounds()).isEmpty();
    }

    // This test is used in the writing-tests.md file.
    @OtterTest
    void testWithRegularAssertion(@NonNull final TestEnvironment env) {
        final Network network = env.network();
        network.addNodes(4);
        network.start();

        final TimeManager timeManager = env.timeManager();
        timeManager.waitFor(Duration.ofSeconds(30));

        // Fluent assertion with method chaining
        assertThat(network.newConsensusResults()).haveEqualCommonRounds().haveAdvancedSinceRound(2);

        assertThat(network.newLogResults().suppressingLogMarker(STARTUP)).haveNoErrorLevelMessages();
    }

    // This test is used in the writing-tests.md file.
    @OtterTest
    void testWithContinuousAssertion(@NonNull final TestEnvironment env) {
        final Network network = env.network();
        network.addNodes(4);

        // Set up monitoring before starting the network
        assertContinuouslyThat(network.newConsensusResults()).haveEqualRounds();

        assertContinuouslyThat(network.newLogResults().suppressingLogMarker(STARTUP))
                .haveNoErrorLevelMessages();

        network.start();

        final TimeManager timeManager = env.timeManager();
        timeManager.waitFor(Duration.ofSeconds(30));
    }

    // This test is used in the writing-tests.md file.
    @OtterTest
    void testWithContinuousSuppression(@NonNull final TestEnvironment env) {
        final Network network = env.network();

        // Continuous assertion with that checks no errors are written to the log
        final MultipleNodeLogResultsContinuousAssert assertion =
                assertContinuouslyThat(network.newLogResults()).haveNoErrorLevelMessages();

        // Suppress RECONNECT log marker during the test
        assertion.startSuppressingLogMarker(RECONNECT);

        // ... test logic that is expected to generate RECONNECT error messages

        // Stop suppressing the RECONNECT log marker
        assertion.stopSuppressingLogMarker(RECONNECT);
    }
}
