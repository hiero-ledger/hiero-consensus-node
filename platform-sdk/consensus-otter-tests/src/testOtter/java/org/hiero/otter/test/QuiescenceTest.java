package org.hiero.otter.test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hiero.otter.fixtures.OtterAssertions.assertContinuouslyThat;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Duration;
import java.util.List;
import java.util.function.Function;
import org.hiero.consensus.model.event.PlatformEvent;
import org.hiero.consensus.model.quiescence.QuiescenceCommand;
import org.hiero.otter.fixtures.Network;
import org.hiero.otter.fixtures.OtterTest;
import org.hiero.otter.fixtures.TestEnvironment;
import org.hiero.otter.fixtures.TimeManager;
import org.hiero.otter.fixtures.result.SingleNodeConsensusResult;
import org.hiero.otter.fixtures.result.SingleNodePcesResult;

public class QuiescenceTest {

    @OtterTest
    void quiescenceTest(@NonNull final TestEnvironment env) {
        final Network network = env.network();
        network.addNodes(4);
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
        timeManager.waitFor(Duration.ofSeconds(20));

        // Send quiescence command to all nodes
        network.nodes().forEach(node -> node.sendQuiescenceCommand(QuiescenceCommand.QUIESCE));
        // Wait to ensure everything in the network has settled, all created events have been gossiped,
        // flushed to PCES, etc.
        timeManager.waitFor(Duration.ofSeconds(5));

        // Collect the data from all nodes
        final List<Long> lastRoundWhenQuiescedPerNode = network.newConsensusResults().results().stream()
                .map(SingleNodeConsensusResult::lastRoundNum).toList();
        for (int i = 1; i < lastRoundWhenQuiescedPerNode.size(); i++) {
            assertThat(lastRoundWhenQuiescedPerNode.get(0))
                    .withFailMessage("All nodes should have the same last round when quiesced")
                    .isEqualTo(lastRoundWhenQuiescedPerNode.get(i));
        }
        // Since we have asserted all are equal, just take the first
        final long lastRoundWhenQuiesced = lastRoundWhenQuiescedPerNode.getFirst();
        // Collect the last PCES event from each node
        final List<PlatformEvent> lastEventWhenQuiesced = network.newPcesResults().pcesResults().stream()
                .map(SingleNodePcesResult::lastPcesEvent).toList();

        // Wait a bit to ensure that the network is quiesced
        timeManager.waitFor(Duration.ofSeconds(30));

        // Assert that consensus has not advanced
        network.newConsensusResults().results().stream()
                .mapToLong(SingleNodeConsensusResult::lastRoundNum)
                .forEach(r-> assertThat(r)
                        .withFailMessage("No node should have advanced rounds while quiesced")
                        .isEqualTo(lastRoundWhenQuiesced));
        // Assert that no new PCES events have been created
        final List<PlatformEvent> lastEventAfterAWhile = network.newPcesResults().pcesResults().stream()
                .map(SingleNodePcesResult::lastPcesEvent).toList();
        assertThat(lastEventWhenQuiesced).isEqualTo(lastEventAfterAWhile);

        // Test the quiescence breaking command
        network.nodes().getFirst().sendQuiescenceCommand(QuiescenceCommand.BREAK_QUIESCENCE);
        // This should create an event and write to PCES, so wait for that
        timeManager.waitFor(Duration.ofSeconds(5));
        final List<PlatformEvent> lastEventAfterBreak = network.newPcesResults().pcesResults().stream()
                .map(SingleNodePcesResult::lastPcesEvent).toList();
        assertThat(lastEventWhenQuiesced.getFirst())
                .withFailMessage("A new event should have been created after breaking quiescence")
                .isNotEqualTo(lastEventAfterBreak.getFirst());

        // Stop quiescing all nodes
        network.nodes().forEach(node -> node.sendQuiescenceCommand(QuiescenceCommand.DONT_QUIESCE));
        // Wait for all nodes to advance at least 20 rounds beyond the quiescence round
        timeManager.waitForCondition(
                () -> network.newConsensusResults().allNodesAdvancedToRound(lastRoundWhenQuiesced + 20),
                Duration.ofSeconds(120L));
    }
}
