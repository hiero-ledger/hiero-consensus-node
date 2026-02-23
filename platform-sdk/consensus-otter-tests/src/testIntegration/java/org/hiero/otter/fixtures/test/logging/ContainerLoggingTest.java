// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.test.logging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hiero.otter.fixtures.test.logging.LoggingTestUtils.LOG_LEVELS_APPEARING_IN_NORMAL_OPERATION;
import static org.hiero.otter.fixtures.test.logging.LoggingTestUtils.MARKERS_APPEARING_IN_NORMAL_OPERATION;
import static org.hiero.otter.fixtures.test.logging.LoggingTestUtils.awaitFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LoggerContext;
import org.hiero.consensus.model.node.NodeId;
import org.hiero.otter.fixtures.Network;
import org.hiero.otter.fixtures.Node;
import org.hiero.otter.fixtures.TestEnvironment;
import org.hiero.otter.fixtures.TimeManager;
import org.hiero.otter.fixtures.container.ContainerNode;
import org.hiero.otter.fixtures.container.ContainerTestEnvironment;
import org.hiero.otter.fixtures.logging.StructuredLog;
import org.hiero.otter.fixtures.result.SingleNodeLogResult;
import org.junit.jupiter.api.Test;

/**
 * Comprehensive integration test for containerized node logging.
 */
class ContainerLoggingTest {

    private static final String SWIRLDS_LOG_PATH = "node-%d/output/swirlds.log";
    private static final String HASHSTREAM_LOG_PATH = "node-%d/output/swirlds-hashstream/swirlds-hashstream.log";
    private static final String OTTER_LOG_PATH = "node-%d/output/otter.log";

    /**
     * Comprehensive integration test for containerized node logging.
     */
    @Test
    @SuppressWarnings("resource")
    void testContainerLogging() throws IOException {
        // Capture console output
        final SystemOutCapturer capturer = new SystemOutCapturer();
        capturer.start();

        // Force Log4j to reconfigure so ConsoleAppender picks up the new System.out
        final LoggerContext context = (LoggerContext) LogManager.getContext(false);
        context.reconfigure();

        final TestEnvironment env = new ContainerTestEnvironment();
        final Path rootOutputDir = env.outputDirectory();
        final List<NodeId> nodeIds;
        final Map<NodeId, SingleNodeLogResult> logResults;
        final Map<NodeId, String[]> consoleOutputs;

        try {
            final Network network = env.network();
            final TimeManager timeManager = env.timeManager();

            network.addNodes(4);
            network.start();

            // Generate log messages in the test. These should not appear in the log.
            System.out.println("Hello Otter!");
            LogManager.getLogger().info("Hello Hiero!");
            LogManager.getLogger("com.acme.ExternalOtterTest").info("Hello World!");

            // Let the nodes run for a bit to generate log messages
            timeManager.waitFor(Duration.ofSeconds(5L));

            // Capture node IDs and log results for later verification
            nodeIds = network.nodes().stream().map(Node::selfId).toList();
            logResults = network.nodes().stream().collect(Collectors.toMap(Node::selfId, Node::newLogResult));
            consoleOutputs = network.nodes().stream()
                    .collect(Collectors.toMap(
                            Node::selfId,
                            node -> ((ContainerNode) node).container().getLogs().split("\n")));
        } finally {
            try {
                env.destroy();
            } finally {
                capturer.stop();
            }
        }

        final String[] hostConsoleOutput = capturer.getCapturedOutput().split("\n");

        // Verify that the console output contains expected log messages
        assertThat(hostConsoleOutput)
                .as("Host console output should NOT contain 'Random seed:'")
                .noneMatch(line -> line.contains("Random seed:")); // Turtle environment only
        assertThat(hostConsoleOutput)
                .as("Host console output should contain 'testcontainers'")
                .anyMatch(line -> line.contains("testcontainers")); // Container environment only

        // After destroy, verify each node's log file contains messages with allowed markers
        for (final NodeId nodeId : nodeIds) {
            final Path swirldsLogFile = rootOutputDir.resolve(String.format(SWIRLDS_LOG_PATH, nodeId.id()));
            awaitFile(swirldsLogFile, Duration.ofSeconds(10L));
            final Path hashstreamLogFile = rootOutputDir.resolve(String.format(HASHSTREAM_LOG_PATH, nodeId.id()));
            awaitFile(hashstreamLogFile, Duration.ofSeconds(10L));
            final Path otterLogFile = rootOutputDir.resolve(String.format(OTTER_LOG_PATH, nodeId.id()));
            awaitFile(otterLogFile, Duration.ofSeconds(10L));

            final List<StructuredLog> inMemoryLog = logResults.get(nodeId).logs();
            final String[] containerConsoleOutput = consoleOutputs.get(nodeId);
            final String[] swirldsLogContent = Files.readString(swirldsLogFile).split("\n");
            final String[] hashstreamLogContent =
                    Files.readString(hashstreamLogFile).split("\n");
            final String[] otterLogContent = Files.readString(otterLogFile).split("\n");

            // Verify the in-memory log contains only entries for this node
            assertThat(inMemoryLog)
                    .as("Node %s in-memory log should only contain logs from this node", nodeId)
                    .allMatch(log -> log.nodeId() == null || Objects.equals(log.nodeId(), nodeId));

            // Verify only allowed markers that appear during normal operation are present
            assertThat(inMemoryLog)
                    .as("Node %s in-memory log should contain only allowed markers", nodeId)
                    .allMatch(logEntry -> MARKERS_APPEARING_IN_NORMAL_OPERATION.contains(logEntry.marker()));
            assertThat(containerConsoleOutput)
                    .as("Node %s console output should contain only allowed markers", nodeId)
                    .allMatch(LoggingTestUtils::lineHasRegularMarkersOnly);
            assertThat(swirldsLogContent)
                    .as("Node %s swirlds.log should contain only allowed markers", nodeId)
                    .allMatch(LoggingTestUtils::lineHasRegularMarkersOnly);
            assertThat(hashstreamLogContent)
                    .as("Node %s hashstream.log should only contain the STATE_HASH marker", nodeId)
                    .allMatch(LoggingTestUtils::lineHasStateHashMarkerOnly);
            // otter.log and host console have no requirements on markers

            // Verify that only INFO and WARN level messages are present
            assertThat(inMemoryLog)
                    .as("Node %s in-memory log should contain only INFO and WARN level messages", nodeId)
                    .allMatch(logEntry -> LOG_LEVELS_APPEARING_IN_NORMAL_OPERATION.contains(logEntry.level()));
            assertThat(containerConsoleOutput)
                    .as("Node %s console output should contain only INFO and WARN level messages", nodeId)
                    .allMatch(logLine -> LoggingTestUtils.lineHasLogLevels(logLine, Level.WARN, Level.INFO));
            assertThat(swirldsLogContent)
                    .as("Node %s swirlds.log should contain only INFO and WARN level messages", nodeId)
                    .allMatch(logLine -> LoggingTestUtils.lineHasLogLevels(logLine, Level.WARN, Level.INFO));
            assertThat(hashstreamLogContent)
                    .as("Node %s hashstream.log should only contain INFO level messages", nodeId)
                    .allMatch(logLine -> LoggingTestUtils.lineHasLogLevels(logLine, Level.INFO));
            assertThat(otterLogContent)
                    .as("Node %s otter.log should NOT contain any log levels", nodeId)
                    .allMatch(logLine -> LoggingTestUtils.lineHasLogLevels(logLine, Level.INFO));
            assertThat(hostConsoleOutput)
                    .as("Host console output should only contain INFO log levels")
                    .allMatch(logLine -> LoggingTestUtils.lineHasLogLevels(logLine, Level.INFO));

            // Verify that the log messages from the platform only appear in the in-memory log, swirlds.log, and the
            // container console
            assertThat(inMemoryLog)
                    .as("Node %s in-memory log should contain '// Node is Starting //' message", nodeId)
                    .anyMatch(log -> log.message().contains("// Node is Starting //"));
            assertThat(containerConsoleOutput)
                    .as("Node %s console output should contain '// Node is Starting //' message", nodeId)
                    .anyMatch(line -> line.contains("// Node is Starting //"));
            assertThat(swirldsLogContent)
                    .as("Node %s swirlds.log should contain '// Node is Starting //' message", nodeId)
                    .anyMatch(line -> line.contains("// Node is Starting //"));
            assertThat(hashstreamLogContent)
                    .as("Node %s hashstream.log should NOT contain '// Node is Starting //' message", nodeId)
                    .noneMatch(line -> line.contains("// Node is Starting //"));
            assertThat(otterLogContent)
                    .as("Node %s otter.log should NOT contain '// Node is Starting //' message", nodeId)
                    .noneMatch(line -> line.contains("// Node is Starting //"));
            assertThat(hostConsoleOutput)
                    .as("Host console output should NOT contain '// Node is Starting //' message")
                    .noneMatch(line -> line.contains("// Node is Starting //"));

            // Verify that the log messages from system services only appear in the in-memory log, swirlds.log, and the
            // container console
            assertThat(inMemoryLog)
                    .as("Node %s in-memory log should contain 'RosterService initialized' message", nodeId)
                    .anyMatch(log -> log.message().contains("RosterService initialized"));
            assertThat(containerConsoleOutput)
                    .as("Node %s console output should contain 'RosterService initialized' message", nodeId)
                    .anyMatch(line -> line.contains("RosterService initialized"));
            assertThat(swirldsLogContent)
                    .as("Node %s swirlds.log should contain 'RosterService initialized' message", nodeId)
                    .anyMatch(line -> line.contains("RosterService initialized"));
            assertThat(hashstreamLogContent)
                    .as("Node %s hashstream.log should NOT contain 'RosterService initialized' message", nodeId)
                    .noneMatch(line -> line.contains("RosterService initialized"));
            assertThat(otterLogContent)
                    .as("Node %s otter.log should NOT contain 'RosterService initialized' message", nodeId)
                    .noneMatch(line -> line.contains("RosterService initialized"));
            assertThat(hostConsoleOutput)
                    .as("Host console output should NOT contain 'RosterService initialized' message")
                    .noneMatch(line -> line.contains("RosterService initialized"));

            // Verify that the log messages from app services only appear in the in-memory log, swirlds.log, and the
            // container console
            assertThat(inMemoryLog)
                    .as("Node %s in-memory log should contain 'ConsistencyService initialized' message", nodeId)
                    .anyMatch(log -> log.message().contains("ConsistencyService initialized"));
            assertThat(containerConsoleOutput)
                    .as("Node %s console output should contain 'ConsistencyService initialized' message", nodeId)
                    .anyMatch(line -> line.contains("ConsistencyService initialized"));
            assertThat(swirldsLogContent)
                    .as("Node %s swirlds.log should contain 'ConsistencyService initialized' message", nodeId)
                    .anyMatch(line -> line.contains("ConsistencyService initialized"));
            assertThat(hashstreamLogContent)
                    .as("Node %s hashstream.log should NOT contain 'ConsistencyService initialized' message", nodeId)
                    .noneMatch(line -> line.contains("ConsistencyService initialized"));
            assertThat(otterLogContent)
                    .as("Node %s otter.log should NOT contain 'ConsistencyService initialized' message", nodeId)
                    .noneMatch(line -> line.contains("ConsistencyService initialized"));
            assertThat(hostConsoleOutput)
                    .as("Host console output should NOT contain 'ConsistencyService initialized' message")
                    .noneMatch(line -> line.contains("ConsistencyService initialized"));

            // Verify that log messages from the DockerManager only appear in the container console and otter.log
            assertThat(inMemoryLog)
                    .as("Node %s in-memory log should NOT contain 'Init request received' message", nodeId)
                    .noneMatch(log -> log.message().contains("Init request received"));
            assertThat(containerConsoleOutput)
                    .as("Node %s console output should contain 'Init request received' message", nodeId)
                    .anyMatch(line -> line.contains("Init request received"));
            assertThat(swirldsLogContent)
                    .as("Node %s swirlds.log should NOT contain 'Init request received' message", nodeId)
                    .noneMatch(line -> line.contains("Init request received"));
            assertThat(hashstreamLogContent)
                    .as("Node %s hashstream.log should NOT contain 'Init request received' message", nodeId)
                    .noneMatch(line -> line.contains("Init request received."));
            assertThat(otterLogContent)
                    .as("Node %s otter.log should contain 'Init request received' message", nodeId)
                    .anyMatch(line -> line.contains("Init request received"));
            assertThat(hostConsoleOutput)
                    .as("Host console output should NOT contain 'Init request received' message")
                    .noneMatch(line -> line.contains("Init request received"));

            // Verify that log messages from the Otter framework only appear in the host console
            assertThat(inMemoryLog)
                    .as("Node %s in-memory log should NOT contain 'Starting network...'", nodeId)
                    .noneMatch(log -> log.message().contains("Starting network..."));
            assertThat(containerConsoleOutput)
                    .as("Node %s console output should NOT contain 'Starting network...'", nodeId)
                    .noneMatch(line -> line.contains("Starting network..."));
            assertThat(swirldsLogContent)
                    .as("Node %s swirlds.log should NOT contain 'Starting network...'", nodeId)
                    .noneMatch(line -> line.contains("Starting network..."));
            assertThat(hashstreamLogContent)
                    .as("Node %s hashstream.log should NOT contain 'Starting network...'", nodeId)
                    .noneMatch(line -> line.contains("Starting network..."));
            assertThat(otterLogContent)
                    .as("Node %s otter.log should NOT contain 'Starting network...'", nodeId)
                    .noneMatch(line -> line.contains("Starting network..."));
            assertThat(hostConsoleOutput)
                    .as("Host console output should contain 'Starting network...'")
                    .anyMatch(line -> line.contains("Starting network..."));

            // Verify that the user messages only appear in the host console
            assertThat(inMemoryLog)
                    .as("Node %s in-memory log should NOT contain test log message 'Hello Otter!'", nodeId)
                    .noneMatch(log -> log.message().contains("Hello Otter!"));
            assertThat(containerConsoleOutput)
                    .as("Node %s console output should NOT contain test log message 'Hello Otter!'", nodeId)
                    .noneMatch(line -> line.contains("Hello Otter!"));
            assertThat(swirldsLogContent)
                    .as("Node %s swirlds.log should NOT contain test log message 'Hello Otter!'", nodeId)
                    .noneMatch(line -> line.contains("Hello Otter!"));
            assertThat(hashstreamLogContent)
                    .as("Node %s hashstream.log should NOT contain test log message 'Hello Otter!'", nodeId)
                    .noneMatch(line -> line.contains("Hello Otter!"));
            assertThat(otterLogContent)
                    .as("Node %s otter.log should NOT contain test log message 'Hello Otter!'", nodeId)
                    .noneMatch(line -> line.contains("Hello Otter!"));
            assertThat(hostConsoleOutput)
                    .as("Host console output should contain test log message 'Hello Otter!'")
                    .anyMatch(line -> line.contains("Hello Otter!"));

            assertThat(inMemoryLog)
                    .as("Node %s in-memory log should NOT contain test log message 'Hello Hiero!'", nodeId)
                    .noneMatch(log -> log.message().contains("Hello Hiero!"));
            assertThat(containerConsoleOutput)
                    .as("Node %s console output should NOT contain test log message 'Hello Hiero!'", nodeId)
                    .noneMatch(line -> line.contains("Hello Hiero!"));
            assertThat(swirldsLogContent)
                    .as("Node %s swirlds.log should NOT contain test log message 'Hello Hiero!'", nodeId)
                    .noneMatch(line -> line.contains("Hello Hiero!"));
            assertThat(hashstreamLogContent)
                    .as("Node %s hashstream.log should NOT contain test log message 'Hello Hiero!'", nodeId)
                    .noneMatch(line -> line.contains("Hello Hiero!"));
            assertThat(otterLogContent)
                    .as("Node %s otter.log should NOT contain test log message 'Hello Hiero!'", nodeId)
                    .noneMatch(line -> line.contains("Hello Hiero!"));
            assertThat(hostConsoleOutput)
                    .as("Host console output should contain test log message 'Hello Hiero!'")
                    .anyMatch(line -> line.contains("Hello Hiero!"));

            assertThat(inMemoryLog)
                    .as("Node %s in-memory log should NOT contain test log message 'Hello World!'", nodeId)
                    .noneMatch(log -> log.message().contains("Hello World!"));
            assertThat(containerConsoleOutput)
                    .as("Node %s console output should NOT contain test log message 'Hello World!'", nodeId)
                    .noneMatch(line -> line.contains("Hello World!"));
            assertThat(swirldsLogContent)
                    .as("Node %s swirlds.log should NOT contain test log message 'Hello World!'", nodeId)
                    .noneMatch(line -> line.contains("Hello World!"));
            assertThat(hashstreamLogContent)
                    .as("Node %s hashstream.log should NOT contain test log message 'Hello World!'", nodeId)
                    .noneMatch(line -> line.contains("Hello World!"));
            assertThat(otterLogContent)
                    .as("Node %s otter.log should NOT contain test log message 'Hello World!'", nodeId)
                    .noneMatch(line -> line.contains("Hello World!"));
            assertThat(hostConsoleOutput)
                    .as("Host console output should contain test log message 'Hello World!'")
                    .anyMatch(line -> line.contains("Hello World!"));
        }
    }
}
