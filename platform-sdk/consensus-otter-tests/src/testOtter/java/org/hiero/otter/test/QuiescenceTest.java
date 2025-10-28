package org.hiero.otter.test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;
import static org.hiero.otter.fixtures.OtterAssertions.assertContinuouslyThat;

import com.swirlds.platform.event.preconsensus.PcesMultiFileIterator;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
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
    void quiescenceTest(@NonNull final TestEnvironment env) throws IOException {
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
        // Wait to ensure everything in the network has settled, all created events have been gossiped and processed
        timeManager.waitFor(Duration.ofSeconds(5));
        // quiescenceStartTime will be a bit after the actual time to avoid flakiness
        final Instant quiescenceStartTime = timeManager.now();

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
        // This should create a single event, which we will verify at the end with the PCES results
        network.nodes().getFirst().sendQuiescenceCommand(QuiescenceCommand.BREAK_QUIESCENCE);

        // Wait a bit more to create distance from the quiescence breaking event and others
        timeManager.waitFor(Duration.ofSeconds(10));
        // quiescenceEndTime will be a bit before the actual time to avoid flakiness
        final Instant quiescenceEndTime = timeManager.now();
        timeManager.waitFor(Duration.ofSeconds(5));

        // Stop quiescing all nodes
        network.nodes().forEach(node -> node.sendQuiescenceCommand(QuiescenceCommand.DONT_QUIESCE));
        // Wait for all nodes to advance at least 20 rounds beyond the quiescence round
        timeManager.waitForCondition(
                () -> network.newConsensusResults().allNodesAdvancedToRound(lastRoundWhenQuiesced + 20),
                Duration.ofSeconds(120L));
        network.shutdown();

        System.out.println("Quiescence started at: " + quiescenceStartTime);
        System.out.println("Quiescence ended at: " + quiescenceEndTime);
        // Verify PCES events
        for (final SingleNodePcesResult pcesResult : network.newPcesResults().pcesResults()) {
            boolean foundQuiescenceBreakingEvent = false;
            System.out.println("Checking PCES events for node " + pcesResult.nodeId());
            try (final PcesMultiFileIterator eventIt = pcesResult.pcesEvents()) {
                while (eventIt.hasNext()) {
                    final PlatformEvent event = eventIt.next();
                    System.out.printf(
                            "Event from node %d created at %s%n", event.getCreatorId().id(), event.getTimeCreated());
                    if (event.getTimeCreated().isBefore(quiescenceStartTime)
                            || event.getTimeCreated().isAfter(quiescenceEndTime)) {
                        // Ignore events outside the quiescence window
                        continue;
                    }
                    if(foundQuiescenceBreakingEvent){
                        fail("Found multiple events created during quiescence for node " + pcesResult.nodeId());
                    }
                    foundQuiescenceBreakingEvent = true;
                }
            }
        }

    }
}
