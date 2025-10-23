// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.container.logging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.hiero.consensus.model.node.NodeId;
import org.hiero.otter.fixtures.Network;
import org.hiero.otter.fixtures.Node;
import org.hiero.otter.fixtures.TestEnvironment;
import org.hiero.otter.fixtures.TimeManager;
import org.hiero.otter.fixtures.container.ContainerTestEnvironment;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Comprehensive integration tests for otter.log content in the Container environment.
 *
 * <p>These tests verify that:
 * <ul>
 *     <li>Only INFO level and above messages are logged</li>
 *     <li></li>
 * </ul>
 *
 * <p>Note: Per-node log routing is guaranteed by container isolation, so no explicit routing test is needed.
 */
final class ContainerOtterLogTest {

    private static final String LOG_DIR = "build/container/node-%d/output/";
    private static final String LOG_FILENAME = "otter.log";

    /**
     * Test with multiple nodes to verify that all allowed markers are logged correctly.
     *
     * <p>This test verifies:
     * <ul>
     * <li>only INFO level and above messages are logged</li>
     * <li>expected messages are in the log</li>
     * </ul>
     *
     * @param numNodes the number of nodes to test with
     */
    @ParameterizedTest
    @ValueSource(ints = {1, 4})
    void testBasicOtterLogFunctionality(final int numNodes) throws IOException {
        final TestEnvironment env = new ContainerTestEnvironment();
        final List<NodeId> nodeIds = new ArrayList<>();

        try {
            final Network network = env.network();
            final TimeManager timeManager = env.timeManager();

            final List<Node> nodes = network.addNodes(numNodes);
            network.start();

            // Capture node IDs for later verification
            for (final Node node : nodes) {
                nodeIds.add(node.selfId());
            }

            // Let the nodes run for a bit to generate log messages
            timeManager.waitFor(Duration.ofSeconds(5L));
        } finally {
            // Destroy environment to trigger log download from containers
            env.destroy();
        }

        // After destroy, verify each node's log file contains messages with allowed markers
        for (final NodeId nodeId : nodeIds) {
            final Path logFile = Path.of(String.format(LOG_DIR, nodeId.id()), LOG_FILENAME);
            awaitFile(logFile, Duration.ofSeconds(10L));

            final String logContent = Files.readString(logFile);

            // Log Level Verification

            // Verify that INFO and WARN level messages are present
            // We look for the log level indicators in the log output
            assertThat(logContent).as("Log should contain INFO level messages").containsPattern("\\bINFO\\b");

            // double-check that we have no errors and warnings in the log
            assertThat(logContent)
                    .as("Log should NOT contain ERROR level messages")
                    .doesNotContainPattern("\\bERROR\\b");
            assertThat(logContent)
                    .as("Log should NOT contain WARN level message")
                    .doesNotContainPattern("\\bWARN\\b");

            // Verify that DEBUG level messages do NOT appear
            // (DEBUG logs should be filtered out)
            assertThat(logContent)
                    .as("Log should NOT contain DEBUG level messages")
                    .doesNotContainPattern("\\bDEBUG\\b");

            assertThat(logContent)
                    .as("Log should NOT contain TRACE level messages")
                    .doesNotContainPattern("\\bTRACE\\b");

            // Test Message Verification

            // Verify that the log contains expected log messages
            assertThat(logContent)
                    .as("Log should contain 'Init request received' entry")
                    .contains("Init request received");
            assertThat(logContent)
                    .as("Log should contain 'Starting NodeCommunicationService' message")
                    .contains("Starting NodeCommunicationService");
            assertThat(logContent)
                    .as("Log should contain 'NodeCommunicationService initialized' message")
                    .contains("NodeCommunicationService initialized");
            assertThat(logContent)
                    .as("Log should contain 'Init request completed.' message")
                    .contains("Init request completed.");
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
