// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.turtle.logging;

import static com.swirlds.logging.legacy.LogMarker.MERKLE_DB;
import static com.swirlds.logging.legacy.LogMarker.PLATFORM_STATUS;
import static com.swirlds.logging.legacy.LogMarker.STARTUP;
import static com.swirlds.logging.legacy.LogMarker.STATE_TO_DISK;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.swirlds.logging.legacy.LogMarker;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.hiero.otter.fixtures.Network;
import org.hiero.otter.fixtures.Node;
import org.hiero.otter.fixtures.OtterAssertions;
import org.hiero.otter.fixtures.TestEnvironment;
import org.hiero.otter.fixtures.TimeManager;
import org.hiero.otter.fixtures.turtle.TurtleTestEnvironment;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Comprehensive integration tests for swirlds.log content in the Turtle environment.
 *
 * <p>These tests verify that:
 * <ul>
 *     <li>Messages with allowed markers appear in swirlds.log</li>
 *     <li>Only INFO level and above messages are logged</li>
 *     <li>Each node's logs are correctly routed to their respective directories</li>
 *     <li>The build/turtle folder structure contains only node directories</li>
 * </ul>
 */
final class TurtleSwirdsLogTest {

    /**
     * List of markers that commonly appear during normal Turtle node operation.
     * These are the markers we verify are present in swirlds.log.
     */
    private static final List<LogMarker> MARKERS_APPEARING_IN_NORMAL_OPERATION =
            List.of(STARTUP, PLATFORM_STATUS, STATE_TO_DISK, MERKLE_DB);

    private static final String LOG_DIR = "build/turtle/node-%d/output/";
    private static final String LOG_FILENAME = "swirlds.log";

    /**
     * Test with multiple nodes to verify that all allowed markers are logged correctly.
     *
     * <p>This test verifies:
     * <ul>
     * <li>messages with all allowed markers appear in swirlds.log</li>
     * <li>messages with disallowed markers (e.g., STATE_HASH) do NOT appear in swirlds.log</li>
     * <li>only INFO level and above messages are logged</li>
     * </ul>
     *
     * @param numNodes the number of nodes to test with
     */
    @ParameterizedTest
    @ValueSource(ints = {1, 4})
    void testNodesLogAllAllowedMarkers(final int numNodes) throws Exception {
        final TestEnvironment env = new TurtleTestEnvironment();
        try {
            final Network network = env.network();
            final TimeManager timeManager = env.timeManager();

            final List<Node> nodes = network.addNodes(numNodes);
            network.start();

            // Let the nodes run for a bit to generate log messages
            timeManager.waitFor(Duration.ofSeconds(5L));

            // Verify each node's log file contains messages with allowed markers
            for (final Node node : nodes) {
                final long nodeId = node.selfId().id();
                final Path logFile = Path.of(String.format(LOG_DIR, nodeId), LOG_FILENAME);
                awaitFile(logFile, Duration.ofSeconds(5L));

                final String logContent = Files.readString(logFile);

                // Markers Verification

                // Verify all allowed markers that appear during normal operation are present
                for (final LogMarker marker : MARKERS_APPEARING_IN_NORMAL_OPERATION) {
                    assertThat(logContent)
                            .as("Node %d log should contain %s marker", nodeId, marker.name())
                            .contains("[" + marker.name() + "]");
                }

                // Verify that STATE_HASH marker does NOT appear (not in allowed list)
                assertThat(logContent)
                        .as("Node %d log should NOT contain STATE_HASH marker (not in allowed list)", nodeId)
                        .doesNotContain("[STATE_HASH]");

                // Log Level Verification

                // Verify that INFO and WARN level messages are present
                // We look for the log level indicators in the log output
                assertThat(logContent)
                        .as("Log should contain INFO level messages")
                        .containsPattern("\\bINFO\\b");
                assertThat(logContent)
                        .as("Log should contain WARN level message")
                        .containsPattern("\\bWARN\\b");

                // double-check that we have no errors in the log
                OtterAssertions.assertThat(node.newLogResult()).hasNoErrorLevelMessages();

                // Verify that DEBUG level messages do NOT appear
                // (DEBUG logs should be filtered out)
                assertThat(logContent)
                        .as("Log should NOT contain DEBUG level messages")
                        .doesNotContainPattern("\\bDEBUG\\b");

                assertThat(logContent)
                        .as("Log should NOT contain TRACE level messages")
                        .doesNotContainPattern("\\bTRACE\\b");
            }
        } finally {
            env.destroy();
        }
    }

    /**
     * Test that each node's logs are correctly routed to their respective directories.
     *
     * <p>This test verifies per-node log routing by killing and restarting a specific node,
     * then checking that only that node's log contains the restart messages while other nodes' logs don't.
     */
    @Test
    void testPerNodeLogRouting() throws Exception {
        final TestEnvironment env = new TurtleTestEnvironment();
        try {
            final Network network = env.network();
            final TimeManager timeManager = env.timeManager();

            // Spin up 4 nodes (standard default)
            final List<Node> nodes = network.addNodes(4);

            // Start the network
            network.start();

            // Let the nodes run for a bit to establish initial state
            timeManager.waitFor(Duration.ofSeconds(5));

            // Get nodes and their log paths
            final Node node0 = nodes.get(0);
            final Node node1 = nodes.get(1);
            final Node node2 = nodes.get(2);
            final Node node3 = nodes.get(3);

            final long nodeId0 = node0.selfId().id();
            final long nodeId1 = node1.selfId().id();
            final long nodeId2 = node2.selfId().id();
            final long nodeId3 = node3.selfId().id();

            final Path log0 = Path.of("build/turtle/node-" + nodeId0 + "/output/swirlds.log");
            final Path log1 = Path.of("build/turtle/node-" + nodeId1 + "/output/swirlds.log");
            final Path log2 = Path.of("build/turtle/node-" + nodeId2 + "/output/swirlds.log");
            final Path log3 = Path.of("build/turtle/node-" + nodeId3 + "/output/swirlds.log");

            // Wait for initial log files to be created
            awaitFile(log0, Duration.ofSeconds(5L));
            awaitFile(log1, Duration.ofSeconds(5L));
            awaitFile(log2, Duration.ofSeconds(5L));
            awaitFile(log3, Duration.ofSeconds(5L));

            // Record initial log file sizes to identify new content after restart
            final long initialSize0 = Files.size(log0);
            final long initialSize1 = Files.size(log1);
            final long initialSize2 = Files.size(log2);
            final long initialSize3 = Files.size(log3);

            // Kill and restart node1 to generate unique log messages
            node1.killImmediately();
            timeManager.waitFor(Duration.ofSeconds(2));
            node1.start();
            timeManager.waitFor(Duration.ofSeconds(5));

            // Read only the new content added after node1's restart
            final String newLog0Content = Files.readString(log0).substring((int) initialSize0);
            final String newLog1Content = Files.readString(log1).substring((int) initialSize1);
            final String newLog2Content = Files.readString(log2).substring((int) initialSize2);
            final String newLog3Content = Files.readString(log3).substring((int) initialSize3);

            // Verify node1's new log content contains STARTUP marker from the restart
            assertThat(newLog1Content)
                    .as("Node %d should have STARTUP marker in log after restart", nodeId1)
                    .contains("[STARTUP]");

            // Verify other nodes' new log content does NOT contain STARTUP marker
            // (proving that node1's restart logs only went to node1's log file)
            assertThat(newLog0Content)
                    .as("Node %d should NOT have STARTUP marker in log (it did not restart)", nodeId0)
                    .doesNotContain("[STARTUP]");

            assertThat(newLog2Content)
                    .as("Node %d should NOT have STARTUP marker in log (it did not restart)", nodeId2)
                    .doesNotContain("[STARTUP]");

            assertThat(newLog3Content)
                    .as("Node %d should NOT have STARTUP marker in log (it did not restart)", nodeId3)
                    .doesNotContain("[STARTUP]");
        } finally {
            env.destroy();
        }
    }

    /**
     * Waits for a file to exist and have non-zero size, with a timeout.
     *
     * @param file the file to wait for
     * @param timeout the maximum time to wait
     */
    private static void awaitFile(@NonNull final Path file, @NonNull final Duration timeout) {
        await().atMost(timeout).pollInterval(Duration.ofMillis(100)).untilAsserted(() -> assertThat(file)
                .isNotEmptyFile());
    }
}
