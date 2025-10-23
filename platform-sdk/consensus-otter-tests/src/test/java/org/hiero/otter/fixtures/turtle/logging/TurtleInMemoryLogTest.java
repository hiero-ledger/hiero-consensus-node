// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.turtle.logging;

import static com.swirlds.logging.legacy.LogMarker.MERKLE_DB;
import static com.swirlds.logging.legacy.LogMarker.PLATFORM_STATUS;
import static com.swirlds.logging.legacy.LogMarker.STARTUP;
import static com.swirlds.logging.legacy.LogMarker.STATE_HASH;
import static com.swirlds.logging.legacy.LogMarker.STATE_TO_DISK;
import static org.assertj.core.api.Assertions.assertThat;

import com.swirlds.logging.legacy.LogMarker;
import java.time.Duration;
import java.util.List;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.hiero.otter.fixtures.Network;
import org.hiero.otter.fixtures.Node;
import org.hiero.otter.fixtures.OtterAssertions;
import org.hiero.otter.fixtures.TestEnvironment;
import org.hiero.otter.fixtures.TimeManager;
import org.hiero.otter.fixtures.logging.StructuredLog;
import org.hiero.otter.fixtures.result.MultipleNodeLogResults;
import org.hiero.otter.fixtures.result.SingleNodeLogResult;
import org.hiero.otter.fixtures.turtle.TurtleTestEnvironment;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Comprehensive integration tests for in-memory logger content in the Turtle environment.
 *
 * <p>These tests verify that:
 * <ul>
 *     <li>Messages with allowed markers are captured by the in-memory logger</li>
 *     <li>Messages with disallowed markers (e.g., STATE_HASH) are filtered out (like swirlds.log)</li>
 *     <li>Only INFO level and above messages are captured in the in-memory logger</li>
 *     <li>Each node's logs are correctly tracked separately</li>
 * </ul>
 */
final class TurtleInMemoryLogTest {

    /**
     * List of markers that commonly appear during normal Turtle node operation.
     * These are the markers we verify are present in the in-memory logger.
     */
    private static final List<LogMarker> MARKERS_APPEARING_IN_NORMAL_OPERATION =
            List.of(STARTUP, PLATFORM_STATUS, STATE_TO_DISK, MERKLE_DB);

    /**
     * Test with multiple nodes to verify that all allowed markers are logged correctly in the in-memory logger.
     *
     * <p>This test verifies:
     * <ul>
     * <li>messages with all allowed markers appear in the in-memory logger</li>
     * <li>messages with disallowed markers (e.g., STATE_HASH) do NOT appear in the in-memory logger</li>
     * <li>only INFO level and above messages are captured in the in-memory logger</li>
     * </ul>
     *
     * @param numNodes the number of nodes to test with
     */
    @ParameterizedTest
    @ValueSource(ints = {1, 4})
    void testBasicInMemoryLogging(final int numNodes) {
        final TestEnvironment env = new TurtleTestEnvironment();
        try {
            final Network network = env.network();
            final TimeManager timeManager = env.timeManager();

            final List<Node> nodes = network.addNodes(numNodes);
            network.start();

            // Generate log messages in the test. These should not appear in the log.
            System.out.println("Hello Otter!");
            LogManager.getLogger().info("Hello Hiero!");
            LogManager.getLogger("com.acme.ExternalOtterTest").info("Hello World!");

            // Let the nodes run for a bit to generate log messages
            timeManager.waitFor(Duration.ofSeconds(5L));

            // Verify each node's in-memory log contains messages with expected markers
            for (final Node node : nodes) {
                final long nodeId = node.selfId().id();
                final SingleNodeLogResult logResult = node.newLogResult();

                // Markers Verification

                // Verify all allowed markers that appear during normal operation are present
                for (final LogMarker marker : MARKERS_APPEARING_IN_NORMAL_OPERATION) {
                    OtterAssertions.assertThat(logResult)
                            .as("Node %d in-memory log should contain %s marker", nodeId, marker.name())
                            .hasMessageWithMarker(marker);
                }

                // Verify that STATE_HASH marker does NOT appear (not in allowed list)
                final boolean hasStateHashMarker =
                        logResult.logs().stream().anyMatch(log -> log.marker() == STATE_HASH.getMarker());
                assertThat(hasStateHashMarker)
                        .as("Node %d in-memory log should NOT contain STATE_HASH marker (not in allowed list)", nodeId)
                        .isFalse();

                // Log Level Verification

                // Verify that INFO and WARN level messages are present by checking the log levels
                final boolean hasInfoLogs = logResult.logs().stream().anyMatch(log -> log.level() == Level.INFO);
                final boolean hasWarnLogs = logResult.logs().stream().anyMatch(log -> log.level() == Level.WARN);

                assertThat(hasInfoLogs)
                        .as("Node %d in-memory log should contain INFO level messages", nodeId)
                        .isTrue();
                assertThat(hasWarnLogs)
                        .as("Node %d in-memory log should contain WARN level messages", nodeId)
                        .isTrue();

                // Verify that we have no errors in the log
                OtterAssertions.assertThat(logResult).hasNoErrorLevelMessages();

                // Verify that DEBUG and TRACE level messages do NOT appear
                // (only INFO and above should be captured)
                final boolean hasDebugLogs = logResult.logs().stream().anyMatch(log -> log.level() == Level.DEBUG);
                final boolean hasTraceLogs = logResult.logs().stream().anyMatch(log -> log.level() == Level.TRACE);

                assertThat(hasDebugLogs)
                        .as("Node %d in-memory log should NOT contain DEBUG level messages", nodeId)
                        .isFalse();
                assertThat(hasTraceLogs)
                        .as("Node %d in-memory log should NOT contain TRACE level messages", nodeId)
                        .isFalse();

                // Test Message Verification

                // Verify that our test log messages do NOT appear in the log
                OtterAssertions.assertThat(logResult)
                        .as("Log should NOT contain test log message 'Hello Otter!'")
                        .hasNoMessageContaining("Hello Otter!");
                OtterAssertions.assertThat(logResult)
                        .as("Log should NOT contain test log message 'Hello Hiero!'")
                        .hasNoMessageContaining("Hello Hiero!");
                OtterAssertions.assertThat(logResult)
                        .as("Log should NOT contain test log message 'Hello World!'")
                        .hasNoMessageContaining("Hello World!");
            }
        } finally {
            env.destroy();
        }
    }

    /**
     * Test that each node's in-memory logs are correctly tracked separately.
     *
     * <p>This test verifies per-node log tracking by checking that each node's log results
     * only contain logs with that node's ID, ensuring logs are not mixed between nodes.
     */
    @Test
    void testPerNodeLogTracking() {
        final TestEnvironment env = new TurtleTestEnvironment();
        try {
            final Network network = env.network();
            final TimeManager timeManager = env.timeManager();

            // Spin up 4 nodes (standard default)
            final List<Node> nodes = network.addNodes(4);

            // Start the network
            network.start();

            // Let the nodes run for a bit to generate log messages
            timeManager.waitFor(Duration.ofSeconds(5));

            // Verify each node's logs only contain that node's ID
            for (final Node node : nodes) {
                final long nodeId = node.selfId().id();
                final SingleNodeLogResult logResult = node.newLogResult();

                // Verify that logs were captured
                assertThat(logResult.logs())
                        .as("Node %d should have log messages", nodeId)
                        .isNotEmpty();

                // Verify that all logs have the correct node ID
                // Note: Some logs might have null nodeId (e.g., test framework logs), so we only check non-null ones
                final long logsWithWrongNodeId = logResult.logs().stream()
                        .filter(log -> log.nodeId() != null)
                        .filter(log -> log.nodeId().id() != nodeId)
                        .count();

                assertThat(logsWithWrongNodeId)
                        .as("Node %d logs should only contain logs from node %d", nodeId, nodeId)
                        .isEqualTo(0);

                // Verify that there are logs with the correct node ID
                final long logsWithCorrectNodeId = logResult.logs().stream()
                        .filter(log -> log.nodeId() != null)
                        .filter(log -> log.nodeId().id() == nodeId)
                        .count();

                assertThat(logsWithCorrectNodeId)
                        .as("Node %d should have logs with node ID %d", nodeId, nodeId)
                        .isGreaterThan(0);
            }
        } finally {
            env.destroy();
        }
    }

    /**
     * Test that Network.newLogResults() aggregates all node logs correctly.
     *
     * <p>This test verifies:
     * <ul>
     * <li>The network-level log results contain logs from all nodes</li>
     * <li>Common assertions can be applied across all nodes at once</li>
     * </ul>
     */
    @Test
    void testNetworkLogResults() {
        final TestEnvironment env = new TurtleTestEnvironment();
        try {
            final Network network = env.network();
            final TimeManager timeManager = env.timeManager();

            // Spin up 4 nodes
            network.addNodes(4);
            network.start();

            // Let the nodes run for a bit to generate log messages
            timeManager.waitFor(Duration.ofSeconds(5L));

            // Get network-level log results
            final MultipleNodeLogResults networkLogResults = network.newLogResults();

            // Verify that the network log results contain results from all nodes
            assertThat(networkLogResults.results())
                    .as("Network log results should contain logs from all 4 nodes")
                    .hasSize(4);

            // Verify that all nodes have no error-level messages
            OtterAssertions.assertThat(networkLogResults)
                    .as("All nodes should have no error-level messages")
                    .haveNoErrorLevelMessages();

            // Verify that none of the nodes have messages with levels higher than WARN
            OtterAssertions.assertThat(networkLogResults)
                    .as("All nodes should have no messages with level higher than WARN")
                    .haveNoMessagesWithLevelHigherThan(Level.WARN);
        } finally {
            env.destroy();
        }
    }

    /**
     * Test that log entries are added continuously in real-time as they are logged,
     * not buffered and added all at once.
     *
     * <p>This test verifies:
     * <ul>
     * <li>Logs are captured immediately as they are generated, not buffered until shutdown</li>
     * <li>Logs are available right after startup without waiting for node shutdown</li>
     * <li>Multiple calls to newLogResult() return consistent, accumulated log data</li>
     * </ul>
     */
    @Test
    void testLogsAddedContinuously() {
        final TestEnvironment env = new TurtleTestEnvironment();
        try {
            final Network network = env.network();
            final TimeManager timeManager = env.timeManager();

            // Spin up a single node
            final List<Node> nodes = network.addNodes(1);
            final Node node = nodes.getFirst();
            network.start();

            // Immediately check for logs after startup - if logs were buffered,
            // we wouldn't see any yet
            timeManager.waitFor(Duration.ofSeconds(5L));
            final SingleNodeLogResult logResult = node.newLogResult();
            final List<StructuredLog> firstSnapshot = logResult.logs();
            assertThat(firstSnapshot.size())
                    .as("Should have startup logs available immediately")
                    .isGreaterThan(0);

            // Verify we can see STARTUP markers in the logs already
            OtterAssertions.assertThat(logResult)
                    .as("Should have STARTUP marker visible immediately")
                    .hasMessageWithMarker(STARTUP);

            // Call newLogResult() again immediately - should get the same accumulated logs
            final List<StructuredLog> secondSnapshot = node.newLogResult().logs();
            assertThat(secondSnapshot)
                    .as("Multiple calls to newLogResult() should return accumulated logs")
                    .containsExactlyElementsOf(firstSnapshot);

            // Wait for more activity and verify log count stays consistent or increases
            network.freeze();
            assertThat(logResult.logs())
                    .as("Log count should be higher as before (accumulated continuously)")
                    .hasSizeGreaterThan(firstSnapshot.size());

            // Verify the logs from the first snapshot are still present
            // (they should be accumulated, not replaced)
            assertThat(logResult.logs())
                    .as("Later snapshots should contain all logs from earlier snapshots")
                    .containsAll(firstSnapshot);
        } finally {
            env.destroy();
        }
    }
}
