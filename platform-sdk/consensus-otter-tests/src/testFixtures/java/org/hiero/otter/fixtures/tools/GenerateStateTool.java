// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.tools;

import static java.util.Objects.requireNonNull;
import static org.hiero.otter.fixtures.app.OtterApp.SWIRLD_NAME;

import com.swirlds.common.config.StateCommonConfig;
import com.swirlds.common.io.utility.FileUtils;
import com.swirlds.config.api.Configuration;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
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
 *   <li>Creates a 1-node network</li>
 *   <li>Runs it for some time</li>
 *   <li>Freezes the network and shuts it down</li>
 *   <li>Moves the produced saved state</li>
 * </ul>
 * Intended to be run manually when refreshing the prior-version state used by tests.
 */
public class GenerateStateTool {

    /** Deterministic seed used to initialize the turtle test environment. */
    public static final long SEED = 5045275509048911830L;

    /** Path relative to the project root */
    public static final String SAVE_STATE_DIRECTORY = "saved-states";

    /** Name of PCES directory */
    public static final String PCES_DIRECTORY = "preconsensus-events";

    private static final Pattern DATE_PREFIX = Pattern.compile("^(\\d{4})-(\\d{2})-(\\d{2}).*");

    /** Test environment used to create and control the ephemeral network. */
    private final TestEnvironment environment;

    /** Self-ID of the node */
    private static final long SELF_ID = 0L;

    private static final List<String> FILES_TO_CLEAN = List.of("emergencyRecovery.yaml");

    /**
     * Create a new tool bound to the given test environment.
     *
     * @param environment the test environment to use; must not be {@code null}
     */
    public GenerateStateTool(@NonNull final TestEnvironment environment) {
        this.environment = requireNonNull(environment, "environment cannot be null");
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
        network.start();
        timeManager.waitFor(Duration.ofSeconds(10L));
        network.freeze();

        network.shutdown();
    }

    /**
     * Cleans up the saved state directory by removing unnecessary files and keeping only the latest round.
     * This method deletes all directories except the application directory, removes all non-directory files,
     * cleans up the application state to keep only the maximum round directory, and prepares the PCES directory.
     *
     * @param rootOutputDirectory the root directory containing the saved state to clean up
     * @throws IOException if file operations fail
     * @throws IllegalStateException if the maximum round path is not found
     */
    public void cleanUpDirectory(@NonNull final Path rootOutputDirectory) throws IOException {
        requireNonNull(rootOutputDirectory, "root output directory cannot be null");
        Path maxRoundPath = null;
        try (final DirectoryStream<Path> stream = Files.newDirectoryStream(rootOutputDirectory)) {
            for (final Path path : stream) {
                if (Files.isDirectory(path)) {
                    if (path.getFileName().toString().equals(OtterApp.APP_NAME)) {
                        maxRoundPath = removeAllButLatestState(path);
                    } else {
                        FileUtils.deleteDirectory(path);
                    }
                } else {
                    Files.delete(path);
                }
            }
        }

        if (maxRoundPath != null) {
            cleanUpStateDirectory(maxRoundPath);
            preparePces(rootOutputDirectory, maxRoundPath);
        } else {
            throw new IllegalStateException("Max round path not found");
        }
    }

    /**
     * Removes specific files from the state directory that should not be included in the saved state.
     * This method deletes files listed in {@link #FILES_TO_CLEAN} from the given directory.
     *
     * @param stateDirectory the state directory to clean up
     * @throws UncheckedIOException if an I/O error occurs while listing or deleting files
     */
    private void cleanUpStateDirectory(@NonNull final Path stateDirectory) {
        requireNonNull(stateDirectory, "state directory cannot be null");
        try (final Stream<Path> roundDirList = Files.list(stateDirectory)) {
            roundDirList
                    .filter(p -> FILES_TO_CLEAN.contains(p.getFileName().toString()))
                    .forEach(p -> {
                        try {
                            Files.deleteIfExists(p);
                        } catch (final IOException exception) {
                            throw new UncheckedIOException(exception);
                        }
                    });
        } catch (IOException e) {
            throw new UncheckedIOException("Exception while cleaning state directory", e);
        }
    }

    /**
     * Prepares the PCES directory for the saved state.
     * This method moves the PCES directory from the round directory to the root output directory,
     * then reorganizes PCES files into a date-based directory structure (YYYY/MM/DD).
     *
     * @param rootOutputDirectory the root directory where the PCES directory will be moved
     * @param maxRoundPath the path to the maximum round directory containing the PCES directory
     * @throws IOException if the PCES directory does not exist or if file operations fail
     */
    private void preparePces(@NonNull final Path rootOutputDirectory, @NonNull final Path maxRoundPath)
            throws IOException {
        final Path pcesDirectory = maxRoundPath.resolve(PCES_DIRECTORY);
        if (!Files.exists(pcesDirectory)) {
            throw new IOException("PCES directory does not exist: " + pcesDirectory);
        }
        FileUtils.moveDirectory(pcesDirectory, rootOutputDirectory.resolve(PCES_DIRECTORY));

        final Path nodePcesDirectory =
                rootOutputDirectory.resolve(PCES_DIRECTORY).resolve(Long.toString(SELF_ID));
        try (final DirectoryStream<Path> stream = Files.newDirectoryStream(nodePcesDirectory)) {
            for (Path entry : stream) {
                if (Files.isDirectory(entry)) {
                    continue;
                }

                final String fileName = entry.getFileName().toString();
                final Matcher matcher = DATE_PREFIX.matcher(fileName);
                if (!matcher.matches()) {
                    continue;
                }

                final Path targetDir =
                        nodePcesDirectory.resolve(Paths.get(matcher.group(1), matcher.group(2), matcher.group(3)));
                Files.createDirectories(targetDir);

                final Path targetFile = targetDir.resolve(fileName);
                Files.move(entry, targetFile, StandardCopyOption.REPLACE_EXISTING);
            }
        }
    }

    /**
     * Removes all saved state round directories except for the highest round.
     * This method identifies the maximum round number, deletes all other round directories,
     * and returns the path to the maximum round.
     *
     * @param path the application directory path containing the node and swirld subdirectories
     * @return the path to the maximum round directory
     * @throws IOException if no round directory is found or if file operations fail
     */
    @NonNull
    private Path removeAllButLatestState(@NonNull final Path path) throws IOException {
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

            return maxRound.get();
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

            final GenerateStateTool generateStateTool = new GenerateStateTool(new TurtleTestEnvironment(SEED, false));
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
