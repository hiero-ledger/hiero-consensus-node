// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.logging;

import static com.swirlds.logging.legacy.LogMarker.MERKLE_DB;
import static com.swirlds.logging.legacy.LogMarker.PLATFORM_STATUS;
import static com.swirlds.logging.legacy.LogMarker.STARTUP;
import static com.swirlds.logging.legacy.LogMarker.STATE_TO_DISK;
import static org.assertj.core.api.Assertions.assertThat;
import static org.testcontainers.shaded.org.awaitility.Awaitility.await;

import com.swirlds.logging.legacy.LogMarker;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import java.util.stream.Stream;
import org.apache.logging.log4j.LogManager;
import org.hiero.consensus.model.node.NodeId;
import org.hiero.otter.fixtures.Network;
import org.hiero.otter.fixtures.Node;
import org.hiero.otter.fixtures.TestEnvironment;
import org.hiero.otter.fixtures.TimeManager;
import org.hiero.otter.fixtures.container.ContainerTestEnvironment;
import org.hiero.otter.fixtures.turtle.TurtleTestEnvironment;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Comprehensive integration tests for swirlds.log content in all environments.
 *
 * <p>These tests verify that:
 * <ul>
 *     <li>Messages with allowed markers appear in swirlds.log</li>
 *     <li>Messages with disallowed markers (e.g., STATE_HASH) do NOT appear in swirlds.log</li>
 *     <li>Only INFO level and above messages are logged</li>
 *     <li>Each node's logs are correctly routed to their respective directories</li>
 * </ul>
 *
 * <p>Note: Per-node log routing is guaranteed by container isolation, so no explicit routing test is needed.
 */
final class BasicSwirldsLogTest {

    /**
     * List of markers that commonly appear during normal Container node operation.
     * These are the markers we verify are present in swirlds.log.
     */
    private static final List<LogMarker> MARKERS_APPEARING_IN_NORMAL_OPERATION =
            List.of(STARTUP, PLATFORM_STATUS, STATE_TO_DISK, MERKLE_DB);

    @NonNull
    private static Stream<Arguments> arguments() {
        return Stream.of(
                Arguments.of(1, "turtle", (Supplier<TestEnvironment>) TurtleTestEnvironment::new),
                Arguments.of(4, "turtle", (Supplier<TestEnvironment>) TurtleTestEnvironment::new),
                Arguments.of(1, "container", (Supplier<TestEnvironment>) ContainerTestEnvironment::new),
                Arguments.of(4, "container", (Supplier<TestEnvironment>) ContainerTestEnvironment::new));
    }

    private static final String LOG_DIR = "build/%s/node-%d/output/";
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
    @MethodSource("arguments")
    void testBasicSwirldsLogFunctionality(
            final int numNodes, @NonNull final String pathElement, @NonNull final Supplier<TestEnvironment> envFactory)
            throws IOException {
        final TestEnvironment env = envFactory.get();
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

            // Generate log messages in the test. These should not appear in the log.
            System.out.println("Hello Otter!");
            LogManager.getLogger().info("Hello Hiero!");
            LogManager.getLogger("com.acme.ExternalOtterTest").info("Hello World!");

            // Let the nodes run for a bit to generate log messages
            timeManager.waitFor(Duration.ofSeconds(5L));
        } finally {
            // Destroy environment to trigger log download from containers
            env.destroy();
        }

        // After destroy, verify each node's log file contains messages with allowed markers
        for (final NodeId nodeId : nodeIds) {
            final Path logFile = Path.of(String.format(LOG_DIR, pathElement, nodeId.id()), LOG_FILENAME);
            awaitFile(logFile, Duration.ofSeconds(10L));

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
            assertThat(logContent).as("Log should contain INFO level messages").containsPattern("\\bINFO\\b");
            assertThat(logContent).as("Log should contain WARN level message").containsPattern("\\bWARN\\b");

            // double-check that we have no errors in the log
            assertThat(logContent)
                    .as("Log should NOT contain ERROR level messages")
                    .doesNotContainPattern("\\bERROR\\b");

            // Verify that DEBUG level messages do NOT appear
            // (DEBUG logs should be filtered out)
            assertThat(logContent)
                    .as("Log should NOT contain DEBUG level messages")
                    .doesNotContainPattern("\\bDEBUG\\b");

            assertThat(logContent)
                    .as("Log should NOT contain TRACE level messages")
                    .doesNotContainPattern("\\bTRACE\\b");

            // Test Message Verification

            // Verify that the log contains expected log messages from swirlds.log
            assertThat(logContent)
                    .as("Log should contain 'No saved states were found on disk' entry")
                    .contains("No saved states were found on disk");
            assertThat(logContent)
                    .as("Log should contain 'Starting with roster history' message")
                    .contains("Starting with roster history");
            assertThat(logContent)
                    .as("Log should contain 'CHECKING. Now in ACTIVE' message")
                    .contains("CHECKING. Now in ACTIVE");
            assertThat(logContent)
                    .as("Log should contain '// Node is Starting //' message")
                    .contains("// Node is Starting //");

            // Verify that our test log messages do NOT appear in the log
            assertThat(logContent)
                    .as("Log should NOT contain test log message 'Hello Otter!'")
                    .doesNotContain("Hello Otter!");
            assertThat(logContent)
                    .as("Log should NOT contain test log message 'Hello Hiero!'")
                    .doesNotContain("Hello Hiero!");
            assertThat(logContent)
                    .as("Log should NOT contain test log message 'Hello World!'")
                    .doesNotContain("Hello World!");
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
