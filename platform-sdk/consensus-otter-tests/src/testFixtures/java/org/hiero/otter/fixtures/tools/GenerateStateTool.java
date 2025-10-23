// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.tools;

import static java.util.Objects.requireNonNull;
import static org.hiero.otter.fixtures.app.OtterApp.SWIRLD_NAME;
import static org.hiero.otter.fixtures.util.OtterSavedStateUtils.fetchApplicationVersion;

import com.hedera.hapi.node.base.SemanticVersion;
import com.swirlds.common.config.StateCommonConfig;
import com.swirlds.common.io.utility.FileUtils;
import com.swirlds.config.api.Configuration;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;
import org.hiero.otter.fixtures.Network;
import org.hiero.otter.fixtures.Node;
import org.hiero.otter.fixtures.TestEnvironment;
import org.hiero.otter.fixtures.TimeManager;
import org.hiero.otter.fixtures.app.OtterApp;
import org.hiero.otter.fixtures.turtle.TurtleTestEnvironment;

/**
 * Utility tool that generates a saved platform state using a minimal "turtle" test environment
 * and installs it as the {@code previous-version-state} test resource for consensus otter tests.
 * <p>
 * This tool performs the following steps:
 * <ul>
 *   <li>Creates a 4-node network</li>
 *   <li>Runs it for some time</li>
 *   <li>Freezes the network and shuts it down</li>
 *   <li>Cleans up the saved state directory</li>
 *   <li>Moves the produced saved state to test resources</li>
 * </ul>
 * Intended to be run manually when refreshing the prior-version state used by tests.
 */
public class GenerateStateTool {

    /** Path relative to the project root */
    public static final String SAVE_STATE_DIRECTORY = "saved-states";

    /** Name of PCES directory */
    public static final String PCES_DIRECTORY = "preconsensus-events";

    /** Self-ID of the node */
    private static final long SELF_ID = 0L;

    /** List of file which will be removed before moving the state */
    private static final List<String> FILES_TO_CLEAN = List.of("emergencyRecovery.yaml", PCES_DIRECTORY);

    /** Test environment used to create and control the ephemeral network. */
    private final TestEnvironment environment;

    /** Software version for the state */
    private final SemanticVersion version;

    /**
     * Create a new tool bound to the given test environment.
     *
     * @param environment the test environment to use; must not be {@code null}
     * @param version the version in which the state should be written
     */
    public GenerateStateTool(@NonNull final TestEnvironment environment, @NonNull final SemanticVersion version) {
        this.environment = requireNonNull(environment, "environment cannot be null");
        this.version = requireNonNull(version, "version cannot be null");
    }

    /**
     * Retrieves a node from the test environment's network by its node ID.
     *
     * @param nodeId the ID of the node to retrieve
     * @return the node with the specified ID
     */
    @NonNull
    public Node getNode(final int nodeId) {
        return environment.network().nodes().get((nodeId));
    }

    /**
     * Generate a saved state by starting a 4-node network, letting it run for some time,
     * freezing it, and shutting it down.
     * <p>
     * Side effects: writes state files under {@code build/turtle/node-0/data/saved}.
     */
    public void generateState() {
        final Network network = environment.network();
        final TimeManager timeManager = environment.timeManager();

        network.addNodes(4);
        network.version(version);
        network.start();
        timeManager.waitFor(Duration.ofSeconds(10L));
        network.freeze();

        network.shutdown();
    }

    /**
     * Cleans up the saved state directory by removing unnecessary files and keeping only the latest round.
     * This method deletes all directories except the application directory and PCES directory, removes all
     * non-directory files, and cleans up the application state to keep only the maximum round directory.
     *
     * @param rootOutputDirectory the root directory containing the saved state to clean up
     * @throws IOException if file operations fail
     */
    public void cleanUpDirectory(@NonNull final Path rootOutputDirectory) throws IOException {
        requireNonNull(rootOutputDirectory, "root output directory cannot be null");

        try (final DirectoryStream<Path> stream = Files.newDirectoryStream(rootOutputDirectory)) {
            for (final Path path : stream) {
                if (Files.isDirectory(path)) {
                    if (path.getFileName().toString().equals(OtterApp.APP_NAME)) {
                        removeAllButLatestState(path);
                    } else {
                        if (!path.getFileName().toString().equals(PCES_DIRECTORY)) {
                            FileUtils.deleteDirectory(path);
                        }
                    }
                } else {
                    Files.delete(path);
                }
            }
        }
    }

    /**
     * Removes specific files and directories from the state directory that should not be included in the saved state.
     * This method deletes entries listed in {@link #FILES_TO_CLEAN} from the given directory.
     * Both files and directories can be deleted.
     *
     * @param stateDirectory the state directory to clean up
     * @throws UncheckedIOException if an I/O error occurs while listing or deleting entries
     */
    private void cleanUpStateDirectory(@NonNull final Path stateDirectory) {
        requireNonNull(stateDirectory, "state directory cannot be null");
        try (final Stream<Path> roundDirList = Files.list(stateDirectory)) {
            roundDirList
                    .filter(p -> FILES_TO_CLEAN.contains(p.getFileName().toString()))
                    .forEach(p -> {
                        try {
                            FileUtils.delete(p);
                        } catch (final IOException exception) {
                            throw new UncheckedIOException(exception);
                        }
                    });
        } catch (final IOException exception) {
            throw new UncheckedIOException("Exception while cleaning state directory", exception);
        }
    }

    /**
     * Removes all saved state round directories except for the highest round.
     * This method identifies the maximum round number, deletes all other round directories,
     * and cleans up the directory after.
     *
     * @param path the application directory path containing the node and swirld subdirectories
     * @throws IOException if no round directory is found or if file operations fail
     */
    private void removeAllButLatestState(@NonNull final Path path) throws IOException {
        final Path dir = path.resolve(String.valueOf(SELF_ID)).resolve(SWIRLD_NAME);
        try (final Stream<Path> list = Files.list(dir)) {
            final List<Path> roundDirectories = list.filter(Files::isDirectory)
                    .filter(p -> p.getFileName().toString().matches("\\d+"))
                    .toList();

            final Optional<Path> maxRound = roundDirectories.stream()
                    .max(Comparator.comparingInt(
                            p -> Integer.parseInt(p.getFileName().toString())));

            if (maxRound.isEmpty()) {
                throw new IOException("No round directory found for " + path);
            }

            for (final Path roundDirectory : roundDirectories) {
                if (!roundDirectory.equals(maxRound.get())) {
                    FileUtils.deleteDirectory(roundDirectory);
                }
            }

            cleanUpStateDirectory(maxRound.get());
        }
    }

    /**
     * Replace the {@code previous-version-state} test resource with the most recently generated state.
     * <p>
     * Deletes the target directory if it already exists, then moves the content to the resources directory from the consensus-otter-tests module
     *
     * @param rootOutputDirectory output directory of the node containing the state
     *
     * @throws IOException if file operations fail
     */
    public void copyFilesInPlace(@NonNull final Path rootOutputDirectory) throws IOException {
        final Path savedStateDirectory =
                Path.of("platform-sdk", "consensus-otter-tests", SAVE_STATE_DIRECTORY, "previous-version-state");

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
     * The version is read from the {@code version.txt} file in root directory. If not available,
     * it falls back to the system property {@code app.version}. If neither is available,
     * {@link SemanticVersion#DEFAULT} is used.
     * <p>
     * Exit code {@code 0} on success, {@code -1} on failure.
     *
     * @param args ignored
     */
    public static void main(final String[] args) {
        try {
            final Path turtleDir = Path.of("build", "turtle");
            if (Files.exists(turtleDir)) {
                FileUtils.deleteDirectory(turtleDir);
            }

            final SemanticVersion version = fetchApplicationVersion();
            final GenerateStateTool generateStateTool =
                    new GenerateStateTool(new TurtleTestEnvironment(0L, false), version);
            generateStateTool.generateState();

            final Node node = generateStateTool.getNode((int) SELF_ID);
            final Configuration configuration = node.configuration().current();
            final Path outputDirectory =
                    configuration.getConfigData(StateCommonConfig.class).savedStateDirectory();

            generateStateTool.cleanUpDirectory(outputDirectory);
            generateStateTool.copyFilesInPlace(outputDirectory);
        } catch (final RuntimeException | IOException exp) {
            System.err.println(exp.getMessage());
            System.exit(-1);
        }

        System.exit(0);
    }
}
