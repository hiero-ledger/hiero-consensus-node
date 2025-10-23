// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.container.logging;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.hiero.otter.fixtures.Network;
import org.hiero.otter.fixtures.Node;
import org.hiero.otter.fixtures.TestEnvironment;
import org.hiero.otter.fixtures.TimeManager;
import org.hiero.otter.fixtures.container.ContainerNode;
import org.hiero.otter.fixtures.container.ContainerTestEnvironment;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Test to validate that both the control process (DockerMain) and consensus node (ConsensusNodeMain)
 * print their output to the console when running in containers.
 */
class ContainerConsoleOutputTest {

    /**
     * Tests that console output from both apps contains expected log messages.
     */
    @ParameterizedTest
    @ValueSource(ints = {1, 4})
    void testContainerConsoleOutput(final int numNodes) {
        final TestEnvironment env = new ContainerTestEnvironment();
        try {
            final Network network = env.network();
            final TimeManager timeManager = env.timeManager();

            network.addNodes(numNodes);
            network.start();

            // Wait for nodes to be active and run for a bit
            timeManager.waitFor(Duration.ofSeconds(5L));

            // Check console logs for each node while containers are still running
            for (final Node node : network.nodes()) {
                // Cast to ContainerNode to access container-specific features
                assertThat(node).isInstanceOf(ContainerNode.class);
                final ContainerNode containerNode = (ContainerNode) node;

                // Get console logs using Testcontainers' built-in getLogs() method
                @SuppressWarnings("resource")
                final String consoleLogs = containerNode.container().getLogs();

                // Verify that the log contains expected log messages from otter.log
                assertThat(consoleLogs)
                        .as("Console output should contain 'Init request received' entry")
                        .contains("Init request received");
                assertThat(consoleLogs)
                        .as("Console output should contain 'Starting NodeCommunicationService' message")
                        .contains("Starting NodeCommunicationService");
                assertThat(consoleLogs)
                        .as("Console output should contain 'NodeCommunicationService initialized' message")
                        .contains("NodeCommunicationService initialized");
                assertThat(consoleLogs)
                        .as("Console output should contain 'Init request completed.' message")
                        .contains("Init request completed.");

                // Verify that the log contains expected log messages from swirlds.log
                assertThat(consoleLogs)
                        .as("Console output should contain 'No saved states were found on disk' entry")
                        .contains("No saved states were found on disk");
                assertThat(consoleLogs)
                        .as("Console output should contain 'Starting with roster history' message")
                        .contains("Starting with roster history");
                assertThat(consoleLogs)
                        .as("Console output should contain 'CHECKING. Now in ACTIVE' message")
                        .contains("CHECKING. Now in ACTIVE");
                //                assertThat(consoleLogs)
                //                        .as("Console output should contain '// Node is Starting //' message")
                //                        .contains("// Node is Starting //");
            }
        } finally {
            env.destroy();
        }
    }
}
