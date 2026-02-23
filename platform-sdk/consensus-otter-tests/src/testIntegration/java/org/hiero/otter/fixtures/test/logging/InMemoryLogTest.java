// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.test.logging;

import static com.swirlds.logging.legacy.LogMarker.STARTUP;
import static org.assertj.core.api.Assertions.assertThat;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Duration;
import java.util.List;
import org.hiero.otter.fixtures.Network;
import org.hiero.otter.fixtures.Node;
import org.hiero.otter.fixtures.OtterAssertions;
import org.hiero.otter.fixtures.TestEnvironment;
import org.hiero.otter.fixtures.TimeManager;
import org.hiero.otter.fixtures.container.ContainerTestEnvironment;
import org.hiero.otter.fixtures.logging.StructuredLog;
import org.hiero.otter.fixtures.result.SingleNodeLogResult;
import org.hiero.otter.fixtures.turtle.TurtleTestEnvironment;
import org.junit.jupiter.api.Test;

/**
 * Integration tests for in-memory log capturing in nodes.
 */
class InMemoryLogTest {

    /**
     * Test that log entries are added continuously (Turtle environment).
     *
     * <p>This test verifies:
     * <ul>
     * <li>Logs are captured immediately as they are generated, not buffered until shutdown</li>
     * <li>Logs are available right after startup without waiting for node shutdown</li>
     * <li>Multiple calls to newLogResult() return consistent, accumulated log data</li>
     * </ul>
     */
    @Test
    void testLogsAddedContinuouslyTurtle() {
        testLogsAddedContinuously(new TurtleTestEnvironment());
    }

    /**
     * Test that log entries are added continuously (Container environment).
     *
     * <p>This test verifies:
     * <ul>
     * <li>Logs are captured immediately as they are generated, not buffered until shutdown</li>
     * <li>Logs are available right after startup without waiting for node shutdown</li>
     * <li>Multiple calls to newLogResult() return consistent, accumulated log data</li>
     * </ul>
     */
    @Test
    void testLogsAddedContinuouslyContainer() {
        testLogsAddedContinuously(new ContainerTestEnvironment());
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
    private void testLogsAddedContinuously(@NonNull final TestEnvironment env) {
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
