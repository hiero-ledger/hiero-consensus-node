// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.tools;

import com.swirlds.common.io.utility.FileUtils;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Objects;
import org.hiero.otter.fixtures.Network;
import org.hiero.otter.fixtures.TestEnvironment;
import org.hiero.otter.fixtures.TimeManager;
import org.hiero.otter.fixtures.turtle.TurtleTestEnvironment;

/**
 * Utility tool that generates a saved platform state using a minimal "turtle" test environment
 * and installs it as the {@code previous-version-state} test resource for consensus otter tests.
 * <p>
 * This tool performs the following steps:
 * <ul>
 *   <li>Creates a 1-node network</li>
 *   <li>Runs it for some time</li>
 *   <li>Freezes the network and shuts it down</li>
 *   <li>Moves the produced saved state</li>
 * </ul>
 * Intended to be run manually when refreshing the prior-version state used by tests.
 */
public class GenerateStateTool {

    /** Deterministic seed used to initialize the turtle test environment. */
    private static final long SEED = 5045275509048911830L;

    /** Test environment used to create and control the ephemeral network. */
    private final TestEnvironment environment;

    /**
     * Create a new tool bound to the given test environment.
     *
     * @param environment the test environment to use; must not be {@code null}
     */
    public GenerateStateTool(@NonNull final TestEnvironment environment) {
        this.environment = Objects.requireNonNull(environment, "environment cannot be null");
    }

    /**
     * Generate a saved state by starting a 1-node network, letting it run for some time,
     * freezing it, and shutting it down.
     * <p>
     * Side effects: writes state files under {@code build/turtle/node-0/data/saved}.
     */
    public void generateState() {
        final Network network = environment.network();
        final TimeManager timeManager = environment.timeManager();

        network.addNodes(1);
        network.start();
        timeManager.waitFor(Duration.ofMinutes(5L));
        network.freeze();

        network.shutdown();
    }

    /**
     * Replace the {@code previous-version-state} test resource with the most recently generated state.
     * <p>
     * Deletes the target directory if it already exists, then moves the content to the resources directory from the consensus-otter-tests module
     *
     * @throws IOException if file operations fail
     */
    public void copyFilesInPlace() throws IOException {
        final Path rootOutputDirectory = Path.of("build", "turtle", "node-0", "data", "saved");
        final Path savedStateDirectory =
                Path.of("platform-sdk", "consensus-otter-tests", "src", "test", "resources", "previous-version-state");

        if (Files.exists(savedStateDirectory)) {
            FileUtils.deleteDirectory(savedStateDirectory);
        }
        Files.createDirectories(savedStateDirectory);

        FileUtils.moveDirectory(rootOutputDirectory, savedStateDirectory);
    }

    /**
     * Command-line entry point. Generates a state using a deterministic turtle environment
     * and installs it into test resources.
     * <p>
     * Exit code {@code 0} on success, {@code -1} on failure.
     *
     * @param args ignored
     */
    public static void main(String[] args) {
        try {
            final GenerateStateTool generateStateTool = new GenerateStateTool(new TurtleTestEnvironment(SEED));
            generateStateTool.generateState();
            generateStateTool.copyFilesInPlace();
        } catch (final RuntimeException | IOException exp) {
            System.err.println(exp.getMessage());
            System.exit(-1);
        }

        System.exit(0);
    }
}
