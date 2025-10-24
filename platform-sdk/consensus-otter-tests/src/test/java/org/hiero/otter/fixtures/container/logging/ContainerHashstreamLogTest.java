// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.container.logging;

import static com.swirlds.logging.legacy.LogMarker.MERKLE_DB;
import static com.swirlds.logging.legacy.LogMarker.PLATFORM_STATUS;
import static com.swirlds.logging.legacy.LogMarker.STARTUP;
import static com.swirlds.logging.legacy.LogMarker.STATE_TO_DISK;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.swirlds.logging.legacy.LogMarker;
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
 * Comprehensive integration tests for swirlds-hashstream.log content in the Container environment.
 *
 * <p>These tests verify that:
 * <ul>
 *     <li>Messages with allowed markers appear in swirlds-hashstream.log</li>
 *     <li>Only INFO level and above messages are logged</li>
 *     <li>Each node's logs are correctly routed to their respective directories</li>
 * </ul>
 *
 * <p>Note: Per-node log routing is guaranteed by container isolation, so no explicit routing test is needed.
 */
class ContainerHashstreamLogTest {

    /**
     * List of markers that commonly appear during normal Container node operation, but should not be present in the
     * swirlds-hashstream.log.
     */
    private static final List<LogMarker> MARKERS_NOT_APPEARING_IN_NORMAL_OPERATION =
            List.of(STARTUP, PLATFORM_STATUS, STATE_TO_DISK, MERKLE_DB);

    private static final String HASHSTREAM_LOG_DIR = "build/container/node-%d/output/swirlds-hashstream/";
    private static final String HASHSTREAM_LOG_FILENAME = "swirlds-hashstream.log";

    /**
     * Test with a single node and multiple nodes to verify that all allowed markers are logged correctly.
     *
     * <p>This test verifies that messages with all allowed markers appear in swirlds-hashstream.log
     * for each node in a multi-node network.
     */
    @ParameterizedTest
    @ValueSource(ints = {1, 4})
    void testBasicHashstreamLogFunctionality(final int numNodes) throws IOException {
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
            final Path logFile =
                    Path.of(String.format(HASHSTREAM_LOG_DIR, nodeId.id())).resolve(HASHSTREAM_LOG_FILENAME);
            awaitFile(logFile, Duration.ofSeconds(10L));

            final String logContent = Files.readString(logFile);

            // Markers Verification

            // Verify that markers not allowed to NOT appear
            for (final LogMarker marker : MARKERS_NOT_APPEARING_IN_NORMAL_OPERATION) {
                assertThat(logContent)
                        .as("Log from node %s should NOT contain %s marker", nodeId, marker.name())
                        .doesNotContain("[" + marker.name() + "]");
            }

            // Verify that STATE_HASH marker appears
            assertThat(logContent)
                    .as("Log from node %s should only contain the STATE_HASH marker", nodeId)
                    .contains("[STATE_HASH]");

            // Log Level Verification

            // Verify that INFO and WARN level messages are present
            // We look for the log level indicators in the log output
            assertThat(logContent).as("Log should contain INFO level messages").containsPattern("\\bINFO\\b");

            // Verify that DEBUG level messages do NOT appear
            // (DEBUG logs should be filtered out)
            assertThat(logContent)
                    .as("Log should NOT contain DEBUG level messages")
                    .doesNotContainPattern("\\bDEBUG\\b");

            assertThat(logContent)
                    .as("Log should NOT contain TRACE level messages")
                    .doesNotContainPattern("\\bTRACE\\b");
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
