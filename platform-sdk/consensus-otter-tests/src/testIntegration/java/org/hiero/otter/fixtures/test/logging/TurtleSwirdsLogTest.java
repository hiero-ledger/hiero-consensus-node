// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.test.logging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.hiero.otter.fixtures.Network;
import org.hiero.otter.fixtures.Node;
import org.hiero.otter.fixtures.TestEnvironment;
import org.hiero.otter.fixtures.TimeManager;
import org.hiero.otter.fixtures.turtle.TurtleTestEnvironment;
import org.junit.jupiter.api.Test;

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

    private static final String LOG_FILE = "node-%d/output/swirlds.log";

    /**
     * Test that each node's logs are correctly routed to their respective directories.
     *
     * <p>This test verifies per-node log routing by killing and restarting a specific node,
     * then checking that only that node's log contains the restart messages while other nodes' logs don't.
     */
    @Test
    void testPerNodeLogRouting() throws IOException {
        final TestEnvironment env = new TurtleTestEnvironment();
        final Path rootOutputDir = env.outputDirectory();
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

            final Path log0 = rootOutputDir.resolve(String.format(LOG_FILE, nodeId0));
            final Path log1 = rootOutputDir.resolve(String.format(LOG_FILE, nodeId1));
            final Path log2 = rootOutputDir.resolve(String.format(LOG_FILE, nodeId2));
            final Path log3 = rootOutputDir.resolve(String.format(LOG_FILE, nodeId3));

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
