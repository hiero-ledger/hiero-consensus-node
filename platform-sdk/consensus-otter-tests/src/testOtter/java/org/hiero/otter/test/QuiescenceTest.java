package org.hiero.otter.test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hiero.otter.fixtures.OtterAssertions.assertContinuouslyThat;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import org.hiero.consensus.model.quiescence.QuiescenceCommand;
import org.hiero.otter.fixtures.Network;
import org.hiero.otter.fixtures.OtterTest;
import org.hiero.otter.fixtures.TestEnvironment;
import org.hiero.otter.fixtures.TimeManager;
import org.hiero.otter.fixtures.result.SingleNodeConsensusResult;

public class QuiescenceTest {

    @OtterTest
    void quiescenceTest(@NonNull final TestEnvironment env) {
        final int numberOfNodes = 4;

        final Network network = env.network();
        network.addNodes(numberOfNodes);
        final TimeManager timeManager = env.timeManager();

        // Setup continuous assertions
        assertContinuouslyThat(network.newLogResults()).haveNoErrorLevelMessages();
        assertContinuouslyThat(network.newReconnectResults()).doNotAttemptToReconnect();
        assertContinuouslyThat(network.newConsensusResults())
                .haveEqualCommonRounds()
                .haveConsistentRounds();
        assertContinuouslyThat(network.newMarkerFileResults()).haveNoMarkerFiles();

        // Start the network and wait a bit
        network.start();
        timeManager.waitForCondition(
                () -> network.newConsensusResults().allNodesAdvancedToRound(20),
                Duration.ofSeconds(120L));

        // Send quiescence command to all nodes
        network.nodes().forEach(node -> node.sendQuiescenceCommand(QuiescenceCommand.QUIESCE));
        // Wait to ensure everything in the network has settled, all created events have been gossiped and processed
        timeManager.waitFor(Duration.ofSeconds(5));

        assertThat(network.newConsensusResults().results().stream()
                .mapToLong(SingleNodeConsensusResult::lastRoundNum).distinct().count())
                .withFailMessage("All nodes should have the same last round when quiesced")
                .isEqualTo(1);
        final long lastRoundWhenQuiesced = network.newConsensusResults().results().getFirst().lastRoundNum();

        timeManager.waitFor(Duration.ofSeconds(10));
        // Assert that consensus has not advanced
        network.newConsensusResults().results().stream()
                .mapToLong(SingleNodeConsensusResult::lastRoundNum)
                .forEach(r -> assertThat(r)
                        .withFailMessage("No node should have advanced rounds while quiesced")
                        .isEqualTo(lastRoundWhenQuiesced));

        // Test the quiescence breaking command
        // This should create a single event, which we will verify with a log statement
        network.nodes().getFirst().sendQuiescenceCommand(QuiescenceCommand.BREAK_QUIESCENCE);

        final AtomicBoolean foundQBlog = network.nodes().getFirst().newLogResult()
                .onNextMatch(logEntry -> logEntry.message().contains("Created quiescence breaking event"));

        timeManager
                .waitForCondition(foundQBlog::get, Duration.ofSeconds(10),
                        "Could not find the log message indicating a quiescence breaking event was created");

        // Stop quiescing all nodes
        network.nodes().forEach(node -> node.sendQuiescenceCommand(QuiescenceCommand.DONT_QUIESCE));
        // Wait for all nodes to advance at least 20 rounds beyond the quiescence round
        timeManager.waitForCondition(
                () -> network.newConsensusResults().allNodesAdvancedToRound(lastRoundWhenQuiesced + 20),
                Duration.ofSeconds(120L));
        network.shutdown();

    }
}
