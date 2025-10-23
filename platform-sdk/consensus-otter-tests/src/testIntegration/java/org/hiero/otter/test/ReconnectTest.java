// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.test;

import static com.swirlds.common.test.fixtures.WeightGenerators.TOTAL_NETWORK_WEIGHT;
import static org.hiero.consensus.model.status.PlatformStatus.ACTIVE;
import static org.hiero.consensus.model.status.PlatformStatus.BEHIND;
import static org.hiero.consensus.model.status.PlatformStatus.CHECKING;
import static org.hiero.consensus.model.status.PlatformStatus.OBSERVING;
import static org.hiero.consensus.model.status.PlatformStatus.RECONNECT_COMPLETE;
import static org.hiero.consensus.model.status.PlatformStatus.REPLAYING_EVENTS;
import static org.hiero.otter.fixtures.OtterAssertions.assertContinuouslyThat;
import static org.hiero.otter.fixtures.OtterAssertions.assertThat;
import static org.hiero.otter.fixtures.assertions.StatusProgressionStep.target;

import com.swirlds.common.merkle.synchronization.config.ReconnectConfig_;
import com.swirlds.platform.consensus.ConsensusConfig_;
import com.swirlds.platform.wiring.PlatformSchedulersConfig_;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.IntStream;
import org.hiero.otter.fixtures.Capability;
import org.hiero.otter.fixtures.Network;
import org.hiero.otter.fixtures.Node;
import org.hiero.otter.fixtures.OtterTest;
import org.hiero.otter.fixtures.TestEnvironment;
import org.hiero.otter.fixtures.TimeManager;
import org.hiero.otter.fixtures.network.utils.BandwidthLimit;
import org.hiero.otter.fixtures.result.MultipleNodePlatformStatusResults;
import org.hiero.otter.fixtures.result.SingleNodePlatformStatusResult;
import org.hiero.otter.fixtures.result.SubscriberAction;
import org.junit.jupiter.api.Disabled;

/**
 * Tests the reconnect functionality of a node that has fallen behind in the consensus rounds. The test ensures that the
 * node can successfully reconnect and catch up with the rest of the network.
 */
public class ReconnectTest {

    /** Reducing the number of rounds non-expired will allow nodes to require a reconnect faster. */
    private static final long ROUNDS_EXPIRED = 100L;

    /**
     * Tests that a node which is killed, kept down until it is behind, and then restarted is able to reconnect to the
     * network and catch up with consensus.
     *
     * @param env the test environment
     */
    @OtterTest(requires = Capability.RECONNECT)
    @Disabled
    void testNodeDeathReconnect(@NonNull final TestEnvironment env) {
        final Network network = env.network();
        final TimeManager timeManager = env.timeManager();

        // Setup simulation

        // Add nodes such that one node can be taken down without halting consensus
        IntStream.range(0, 4).forEach(i -> network.addNode().weight(Math.round(.25 * TOTAL_NETWORK_WEIGHT)));
        network.addNode().weight(0);

        // Set the rounds non-ancient and expired to smaller values to allow nodes to fall behind quickly
        network.withConfigValue(ConsensusConfig_.ROUNDS_EXPIRED, ROUNDS_EXPIRED);

        // Set the node we will force to reconnect
        final Node nodeToReconnect = network.nodes().getLast();

        // Setup continuous assertions
        assertContinuouslyThat(network.newLogResults()).haveNoErrorLevelMessages();
        assertContinuouslyThat(network.newReconnectResults().suppressingNode(nodeToReconnect))
                .doNotAttemptToReconnect();
        assertContinuouslyThat(nodeToReconnect.newReconnectResult())
                .hasNoFailedReconnects()
                .hasMaximumReconnectTime(Duration.ofSeconds(10))
                .hasMaximumTreeInitializationTime(Duration.ofSeconds(1));
        assertContinuouslyThat(network.newConsensusResults()).haveEqualCommonRounds();
        assertContinuouslyThat(network.newConsensusResults().suppressingNode(nodeToReconnect))
                .haveConsistentRounds();
        assertContinuouslyThat(network.newMarkerFileResults()).haveNoMarkerFiles();

        assertContinuouslyThat(network.newPlatformStatusResults().suppressingNode(nodeToReconnect))
                .doNotEnterAnyStatusesOf(BEHIND);
        network.start();

        // Allow the nodes to run for a short time
        timeManager.waitFor(Duration.ofSeconds(5L));

        // Shutdown the node for a period of time so that it falls behind.
        nodeToReconnect.killImmediately();

        // Verify that the node was healthy prior to being killed
        final SingleNodePlatformStatusResult nodeToReconnectStatusResults = nodeToReconnect.newPlatformStatusResult();
        assertThat(nodeToReconnectStatusResults)
                .hasSteps(target(ACTIVE).requiringInterim(REPLAYING_EVENTS, OBSERVING, CHECKING));
        nodeToReconnectStatusResults.clear();

        // Wait for the node we just killed to become behind enough to require a reconnect.
        timeManager.waitForCondition(
                () -> network.nodeIsBehindByNodeCount(nodeToReconnect),
                Duration.ofSeconds(120L),
                "Node did not fall behind in the time allotted.");

        final int numEventStreamFilesBeforeReconnect =
                nodeToReconnect.newEventStreamResult().eventStreamFiles().size();

        // Restart the node that was killed
        nodeToReconnect.start();

        // First, we must wait for the node to come back up and report that it is behind.
        // If we wait for it to be active, this check will pass immediately. That was the last status it had,
        // and we will check the value before the node has a change to tell us that it is behind.
        timeManager.waitForCondition(nodeToReconnect::isBehind, Duration.ofSeconds(120L));

        // Now we wait for the node to reconnect and become active again.
        timeManager.waitForCondition(nodeToReconnect::isActive, Duration.ofSeconds(120L));

        // Allow some additional time to ensure we have at least one event stream file after reconnect
        timeManager.waitForCondition(
                () -> nodeToReconnect.newEventStreamResult().eventStreamFiles().size()
                        > numEventStreamFilesBeforeReconnect,
                Duration.ofSeconds(120L));

        // Validations
        assertThat(nodeToReconnect.newReconnectResult()).hasExactSuccessfulReconnects(1);

        // All non-reconnected nodes should go through the normal status progression
        assertThat(network.newPlatformStatusResults().suppressingNode(nodeToReconnect))
                .haveSteps(target(ACTIVE).requiringInterim(REPLAYING_EVENTS, OBSERVING, CHECKING));

        // The reconnected node should have gone through the reconnect status progression since restarting
        assertThat(nodeToReconnectStatusResults)
                .hasSteps(target(ACTIVE)
                        .requiringInterim(REPLAYING_EVENTS, OBSERVING, BEHIND, RECONNECT_COMPLETE, CHECKING));

        assertThat(network.newEventStreamResults()).haveEqualFiles();
    }

    /**
     * Tests that nodes which are throttled multiple times with a synthetic bottleneck are able to recover and return to
     * active status each time once the bottleneck is removed.
     *
     * @param env the test environment
     */
    @OtterTest(requires = Capability.BACK_PRESSURE)
    void testSyntheticBottleneckReconnect(final TestEnvironment env) {
        final int numReconnectCycles = 2;
        final Network network = env.network();
        final TimeManager timeManager = env.timeManager();

        IntStream.range(0, 10).forEach((i) -> network.addNode().weight(1));
        network.addNode().weight(0);

        // For this test to work, we need to lower the limit for the transaction handler component
        // With the new limit set, once the transaction handler has 100 pending transactions, the node will stop
        // gossipping and stop creating events. This will cause the node to go into the checking state.
        network.withConfigValue(
                        PlatformSchedulersConfig_.TRANSACTION_HANDLER,
                        "SEQUENTIAL_THREAD CAPACITY(100) FLUSHABLE SQUELCHABLE")
                .withConfigValue(ConsensusConfig_.ROUNDS_EXPIRED, 50L);

        // These nodes will be forced to reconnect
        final Node node0 = network.nodes().get(0);
        final Node node1 = network.nodes().get(1);
        final Node node2 = network.nodes().get(2);

        final List<Node> unstableNodes = List.of(node0, node1, node2);
        final List<Node> stableNodes = network.nodes().stream()
                .filter(n -> !Set.of(node0, node1, node2).contains(n))
                .toList();

        // Setup continuous assertions
        assertContinuouslyThat(network.newLogResults()).haveNoErrorLevelMessages();
        assertContinuouslyThat(network.newReconnectResults()).hasNoFailedReconnects();
        assertContinuouslyThat(network.newReconnectResults().suppressingNodes(unstableNodes))
                .doNotAttemptToReconnect();
        assertContinuouslyThat(network.newConsensusResults())
                .haveEqualCommonRounds()
                .haveConsistentRounds();
        assertContinuouslyThat(network.newMarkerFileResults()).haveNoMarkerFiles();

        network.start();

        // Run the nodes for some time
        timeManager.waitFor(Duration.ofSeconds(5L));

        final MultipleNodePlatformStatusResults reconnectingNodeStatusResults =
                network.newPlatformStatusResults().suppressingNodes(stableNodes);

        assertThat(reconnectingNodeStatusResults)
                .haveSteps(target(ACTIVE).requiringInterim(REPLAYING_EVENTS, OBSERVING, CHECKING));
        reconnectingNodeStatusResults.clear();

        for (int i = 0; i < numReconnectCycles; i++) {
            // Throttle the last node for a period of time so that it falls into CHECKING
            enableSyntheticBottleneck(Duration.ofMinutes(10), node0, node1, node2);

            timeManager.waitForCondition(
                    () -> unstableNodes.stream().allMatch(Node::isBehind),
                    Duration.ofMinutes(5),
                    String.format(
                            "Node did not fall behind within the expected time "
                                    + "frame after synthetic bottleneck was enabled on iteration %d.",
                            i));

            disableSyntheticBottleneck(node0, node1, node2);

            // Verify that the node recovers when the bottleneck is lifted
            timeManager.waitForCondition(
                    network::allNodesAreActive,
                    Duration.ofSeconds(180L),
                    String.format(
                            "One or more nodes did not become ACTIVE within the expected time "
                                    + "frame after synthetic bottleneck was disabled on iteration %d.",
                            i));

            assertThat(reconnectingNodeStatusResults)
                    .haveSteps(target(ACTIVE).requiringInterim(CHECKING, BEHIND, RECONNECT_COMPLETE, CHECKING));
            reconnectingNodeStatusResults.clear();
        }

        // Allow the nodes to run for a short time after completing the last reconnect cycle
        timeManager.waitFor(Duration.ofSeconds(5L));

        // Validations
        assertThat(network.newLogResults()).haveNoErrorLevelMessages();
        assertThat(network.newConsensusResults()).haveConsistentRounds().haveEqualCommonRounds();

        for (final Node node : Set.of(node0, node1, node2)) {
            assertThat(node.newReconnectResult())
                    .hasExactSuccessfulReconnects(numReconnectCycles)
                    .hasNoFailedReconnects();
        }

        assertThat(network.newPlatformStatusResults().suppressingNodes(node0, node1, node2))
                .haveSteps(target(ACTIVE).requiringInterim(REPLAYING_EVENTS, OBSERVING, CHECKING));
    }

    private void enableSyntheticBottleneck(@NonNull final Duration duration, @NonNull final Node... nodesToThrottle) {
        Arrays.stream(nodesToThrottle).forEach(n -> n.startSyntheticBottleneck(duration));
    }

    private void disableSyntheticBottleneck(@NonNull final Node... nodesToThrottle) {
        Arrays.stream(nodesToThrottle).forEach(Node::stopSyntheticBottleneck);
    }

    /**
     * Tests that a node which goes through a failed reconnect attempt is able to recover and successfully reconnect.
     *
     * @param env the test environment
     */
    @OtterTest(requires = Capability.RECONNECT)
    @Disabled
    void testReconnectSucceedsAfterFailure(@NonNull final TestEnvironment env) {
        final Network network = env.network();
        final TimeManager timeManager = env.timeManager();

        network.addNodes(5);

        // For this test to work, we need to lower the limit for the transaction handler component
        // With the new limit set, once the transaction handler has 100 pending transactions, the node will stop
        // gossipping and stop creating events. This will cause the node to go into the checking state.
        network.withConfigValue(
                        PlatformSchedulersConfig_.TRANSACTION_HANDLER,
                        "SEQUENTIAL_THREAD CAPACITY(100) FLUSHABLE SQUELCHABLE")
                .withConfigValue(ConsensusConfig_.ROUNDS_EXPIRED, ROUNDS_EXPIRED)
                .withConfigValue(ReconnectConfig_.ASYNC_STREAM_TIMEOUT, Duration.ofSeconds(1));

        network.start();

        final Node nodeToReconnect = network.nodes().getLast();

        nodeToReconnect.newLogResult().subscribe(logEntry -> {
            if (logEntry.message().contains("Starting reconnect in role of the receiver.")) {
                network.isolate(nodeToReconnect);
                return SubscriberAction.UNSUBSCRIBE;
            }
            return SubscriberAction.CONTINUE;
        });

        enableSyntheticBottleneck(Duration.ofMinutes(10), nodeToReconnect);
        timeManager.waitForCondition(
                nodeToReconnect::isBehind,
                Duration.ofSeconds(120L),
                "Node did not enter BEHIND status within the expected time "
                        + "frame after synthetic bottleneck was enabled");

        network.setBandwidthForAllConnections(nodeToReconnect, BandwidthLimit.ofKilobytesPerSecond(1));

        disableSyntheticBottleneck(nodeToReconnect);

        timeManager.waitForCondition(
                () -> nodeToReconnect.newReconnectResult().numFailedReconnects() == 1,
                Duration.ofMinutes(2L),
                "Node did not record a failed reconnect attempt within the expected time frame after synthetic "
                        + "bottleneck was disabled");

        network.restoreConnectivity();

        timeManager.waitForCondition(
                nodeToReconnect::isActive,
                Duration.ofMinutes(2L),
                "Node did not become ACTIVE within the expected time frame after restoring normal connectivity");
    }

    /**
     * Tests that a node which exceeds the maximum number of allowed failed reconnect attempts shuts down as expected.
     *
     * @param env the test environment
     */
    @OtterTest(requires = {Capability.RECONNECT, Capability.SINGLE_NODE_JVM_SHUTDOWN})
    @Disabled
    void testNodeShutsDownAfterMaxFailedReconnects(@NonNull final TestEnvironment env) {
        final Network network = env.network();
        final TimeManager timeManager = env.timeManager();

        network.addNodes(5);

        final int maxFailedReconnects = 3;

        // For this test to work, we need to lower the limit for the transaction handler component
        // With the new limit set, once the transaction handler has 100 pending transactions, the node will stop
        // gossipping and stop creating events. This will cause the node to go into the checking state.
        network.withConfigValue(
                        PlatformSchedulersConfig_.TRANSACTION_HANDLER,
                        "SEQUENTIAL_THREAD CAPACITY(100) FLUSHABLE SQUELCHABLE")
                .withConfigValue(ConsensusConfig_.ROUNDS_EXPIRED, ROUNDS_EXPIRED)
                .withConfigValue(ReconnectConfig_.ASYNC_STREAM_TIMEOUT, Duration.ofSeconds(1))
                .withConfigValue(ReconnectConfig_.MAXIMUM_RECONNECT_FAILURES_BEFORE_SHUTDOWN, maxFailedReconnects);

        network.start();

        final Node nodeToReconnect = network.nodes().getLast();

        nodeToReconnect.newLogResult().subscribe(logEntry -> {
            if (logEntry.message().contains("Starting reconnect in role of the receiver.")) {
                network.isolate(nodeToReconnect);
                return SubscriberAction.UNSUBSCRIBE;
            }
            return SubscriberAction.CONTINUE;
        });

        enableSyntheticBottleneck(Duration.ofMinutes(10), nodeToReconnect);
        timeManager.waitForCondition(
                nodeToReconnect::isBehind,
                Duration.ofSeconds(120L),
                "Node did not enter BEHIND status within the expected time "
                        + "frame after synthetic bottleneck was enabled");

        network.setBandwidthForAllConnections(nodeToReconnect, BandwidthLimit.ofKilobytesPerSecond(1));

        disableSyntheticBottleneck(nodeToReconnect);

        timeManager.waitForCondition(
                () -> nodeToReconnect.newReconnectResult().numFailedReconnects() >= maxFailedReconnects,
                Duration.ofMinutes(2L),
                "Node did not record the expected number of failed reconnect attempts within the expected time frame.");
        timeManager.waitForCondition(
                () -> !nodeToReconnect.isAlive(),
                Duration.ofMinutes(2L),
                "Node did not shut down within the expected time frame after exceeding the maximum number of failed reconnects.");
    }
}
