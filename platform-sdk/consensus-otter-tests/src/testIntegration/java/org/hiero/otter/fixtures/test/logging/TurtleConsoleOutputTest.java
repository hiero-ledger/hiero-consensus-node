// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.test.logging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hiero.otter.fixtures.test.logging.LogMessageParser.extractLogLevel;
import static org.hiero.otter.fixtures.test.logging.LogMessageParser.isLogMessage;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LoggerContext;
import org.hiero.otter.fixtures.Network;
import org.hiero.otter.fixtures.TestEnvironment;
import org.hiero.otter.fixtures.TimeManager;
import org.hiero.otter.fixtures.turtle.TurtleTestEnvironment;
import org.junit.jupiter.api.Test;

class TurtleConsoleOutputTest {

    /**
     * Tests that console output from the Turtle test environment contains expected log messages.
     * It also verifies that log messages are at INFO level or higher.
     */
    @Test
    void testBasicConsoleOutput() {
        // Capture console output
        final ByteArrayOutputStream consoleCapture = new ByteArrayOutputStream();
        final PrintStream originalOut = System.out;
        final PrintStream captureOut = new PrintStream(consoleCapture, true, StandardCharsets.UTF_8);

        try {
            // Redirect System.out BEFORE creating the test environment
            System.setOut(captureOut);

            // Force Log4j to reconfigure so ConsoleAppender picks up the new System.out
            final LoggerContext context = (LoggerContext) LogManager.getContext(false);
            context.reconfigure();

            final TestEnvironment env = new TurtleTestEnvironment();
            try {
                final Network network = env.network();
                final TimeManager timeManager = env.timeManager();

                // Start a 4-node network
                network.addNodes(4);
                network.start();

                System.out.println("Hello Otter!");
                LogManager.getLogger().info("Hello Hiero!");
                LogManager.getLogger("com.acme.ExternalOtterTest").info("Hello World!");

                // Wait 5 seconds
                timeManager.waitFor(Duration.ofSeconds(5L));

            } finally {
                env.destroy();
            }

            // Restore System.out before examining captured output
            System.setOut(originalOut);

            // Get the captured console output
            final String consoleOutput = consoleCapture.toString(StandardCharsets.UTF_8);

            // Verify that the console output contains expected log messages
            assertThat(consoleOutput)
                    .as("Console output should contain 'Random seed:' entry")
                    .contains("Random seed:");
            assertThat(consoleOutput)
                    .as("Console output should contain 'Random seed:' entry")
                    .doesNotContain("testcontainers"); // Container environment only
            assertThat(consoleOutput)
                    .as("Console output should contain 'Starting network...' message")
                    .contains("Starting network...");
            assertThat(consoleOutput)
                    .as("Console output should contain 'Network started.' message")
                    .contains("Network started.");
            assertThat(consoleOutput)
                    .as("Console output should contain 'Waiting for PT5S' message")
                    .contains("Waiting for PT5S");
            assertThat(consoleOutput)
                    .as("Console output should contain 'Destroying network...' message")
                    .contains("Destroying network...");
            assertThat(consoleOutput)
                    .as("Console output should contain 'Hello Otter!' message")
                    .contains("Hello Otter!");
            assertThat(consoleOutput)
                    .as("Console output should contain 'Hello Hiero!' message")
                    .contains("Hello Hiero!");
            assertThat(consoleOutput)
                    .as("Console output should contain 'Hello World!' message")
                    .contains("Hello World!");

            // Parse each line and verify log messages follow the expected pattern
            final String[] lines = consoleOutput.split("\n");
            for (final String line : lines) {
                if (line.trim().isEmpty()) {
                    continue; // Skip empty lines
                }

                // Check if this line is a log message (matches the log pattern)
                // Pattern: yyyy-MM-dd HH:mm:ss.SSS [thread] [optional marker] LEVEL  logger - message
                // Example: 2025-10-22 16:29:13.345 [Test worker] INFO  org.hiero.otter.fixtures... - ...
                if (isLogMessage(line)) {
                    // Extract log level and logger name
                    final String logLevel = extractLogLevel(line);

                    // Verify log level is INFO or more critical (not DEBUG or TRACE)
                    assertThat(logLevel)
                            .as("Log message should have INFO or higher level: %s", line)
                            .isIn("INFO", "WARN", "ERROR", "FATAL");
                }
            }

        } finally {
            // Ensure System.out is always restored even if an exception occurs
            System.setOut(originalOut);
        }
    }
}
