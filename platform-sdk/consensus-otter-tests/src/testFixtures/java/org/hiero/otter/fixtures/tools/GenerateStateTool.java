// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.tools;

import static org.hiero.otter.fixtures.app.OtterApp.SWIRLD_NAME;

import com.swirlds.common.io.utility.FileUtils;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.hiero.otter.fixtures.Network;
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

    /** Name of PCES directory */
    public static final String PCES_DIRECTORY = "preconsensus-events";

    private static final Pattern DATE_PREFIX = Pattern.compile("^(\\d{4})-(\\d{2})-(\\d{2}).*");

    /** Test environment used to create and control the ephemeral network. */
    private final TestEnvironment environment;

    /** Self-ID of the node */
    private static final long SELF_ID = 0L;

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

        network.addNodes(4);
        network.start();
        timeManager.waitFor(Duration.ofSeconds(10L));
        network.freeze();

        network.shutdown();
    }

    /**
     *
     * @param rootOutputDirectory directory of the state
     *
     * @throws IOException if file operations fail
     */
    public void cleanUpDirectory(final Path rootOutputDirectory) throws IOException {
        try (final DirectoryStream<Path> stream = Files.newDirectoryStream(rootOutputDirectory)) {
            for (final Path path : stream) {
                if (Files.isDirectory(path)) {
                    if (path.getFileName().toString().equals(OtterApp.APP_NAME)) {
                        final Path maxRoundPath = cleanUpState(path);
                        preparePces(rootOutputDirectory, maxRoundPath);
                    } else {
                        FileUtils.deleteDirectory(path);
                    }
                } else {
                    Files.delete(path);
                }
            }
        }
    }

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

    @NonNull
    private Path cleanUpState(@NonNull final Path path) throws IOException {
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
     * @param rootOutputDirectory directory of the state
     *
     * @throws IOException if file operations fail
     */
    public void copyFilesInPlace(@NonNull final Path rootOutputDirectory) throws IOException {
        final Path savedStateDirectory = Path.of(
                "platform-sdk", "consensus-otter-tests", "src", "testFixtures", "resources", "previous-version-state");

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
            final Path turtleDir = Path.of("build", "turtle");
            if (Files.exists(turtleDir)) {
                FileUtils.deleteDirectory(turtleDir);
            }

            final GenerateStateTool generateStateTool = new GenerateStateTool(new TurtleTestEnvironment("", SEED));
            generateStateTool.generateState();

            final Path rootOutputDirectory = Path.of("build", "turtle", "node-" + SELF_ID, "data", "saved");
            generateStateTool.cleanUpDirectory(rootOutputDirectory);
            generateStateTool.copyFilesInPlace(rootOutputDirectory);
        } catch (final RuntimeException | IOException exp) {
            System.err.println(exp.getMessage());
            System.exit(-1);
        }

        System.exit(0);
    }
}
