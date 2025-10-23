// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.util;

import static java.util.Objects.requireNonNull;
import static org.hiero.otter.fixtures.tools.GenerateStateTool.PCES_DIRECTORY;
import static org.hiero.otter.fixtures.tools.GenerateStateTool.SAVE_STATE_DIRECTORY;

import com.hedera.hapi.node.base.SemanticVersion;
import com.swirlds.common.io.utility.FileUtils;
import com.swirlds.platform.state.snapshot.SavedStateMetadata;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.BufferedReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.stream.Stream;
import org.hiero.consensus.model.node.NodeId;
import org.hiero.otter.fixtures.app.OtterApp;

/**
 * Utility methods for Otter to handle saved states
 * <p>
 * This class provides helper functions to find saved state directories and copy them to output locations, renaming
 * subdirectories as needed for test scenarios.
 */
public class OtterSavedStateUtils {
    /** Name of the version file */
    public static final String VERSION_FILE_NAME = "version.txt";

    /**
     * Private constructor to prevent instantiation.
     */
    private OtterSavedStateUtils() {
        // Utility class
    }

    /**
     * Finds the path to a saved state directory within the test resources.
     *
     * @param savedStateDirectory the path of the saved state directory, either relative to
     * {@code consensus-otter-tests/saved-states} or an absolute path
     * @return the {@link Path} to the saved state directory
     * @throws IllegalArgumentException if the directory does not exist
     */
    @NonNull
    public static Path findSaveState(@NonNull final Path savedStateDirectory) {
        if (Files.exists(savedStateDirectory) && Files.isDirectory(savedStateDirectory)) {
            return savedStateDirectory;
        }

        final Path fallbackPath = Path.of(SAVE_STATE_DIRECTORY).resolve(savedStateDirectory);
        if (Files.exists(fallbackPath) && Files.isDirectory(fallbackPath)) {
            return fallbackPath;
        }

        throw new IllegalArgumentException("Saved state directory not found");
    }

    /**
     * Copies the saved state directory to the output directory, renaming subdirectories to match the given node ID.
     *
     * @param nodeId the {@link NodeId} to use for renaming subdirectories
     * @param savedState the path to the saved state directory to copy
     * @param outputDir the output directory where the state should be copied
     * @throws IOException if an I/O error occurs during copying or renaming
     */
    public static void copySaveState(
            @NonNull NodeId nodeId, @NonNull final Path savedState, @NonNull final Path outputDir) throws IOException {
        requireNonNull(nodeId);
        requireNonNull(outputDir);
        requireNonNull(savedState);

        final Path targetPath = outputDir.resolve("data").resolve("saved");
        FileUtils.copyDirectory(savedState, targetPath);

        final Path appPath = targetPath.resolve(OtterApp.APP_NAME);
        final Path pcesPath = targetPath.resolve(PCES_DIRECTORY);
        renameToNodeId(nodeId, appPath);
        renameToNodeId(nodeId, pcesPath);
    }

    /**
     * Renames a numeric subdirectory within the given path to match the node ID, if necessary.
     *
     * @param nodeId the {@link NodeId} to use for renaming
     * @param appPath the path containing the subdirectory to rename
     * @throws IOException if an I/O error occurs during renaming
     */
    private static void renameToNodeId(final @NonNull NodeId nodeId, @NonNull final Path appPath) throws IOException {
        requireNonNull(nodeId);
        requireNonNull(appPath);
        try (final Stream<Path> stream = Files.list(appPath)) {
            stream.filter(Files::isDirectory)
                    .filter(p -> p.getFileName().toString().matches("\\d+"))
                    .findFirst()
                    .ifPresent(dir -> {
                        try {
                            final int number =
                                    Integer.parseInt(dir.getFileName().toString());
                            if (number != nodeId.id()) {
                                FileUtils.moveDirectory(dir, appPath.resolve(nodeId.id() + ""));
                            }
                        } catch (IOException e) {
                            throw new RuntimeException(e);
                        }
                    });
        }
    }

    /**
     * Retrieves the application version from either the system property or a version file. First attempts to read from
     * the {@code app.version} system property. If not set, searches for the {@link #VERSION_FILE_NAME} file by
     * traversing up to 5 parent directories from the current working directory. If neither is available or cannot be
     * parsed, returns {@link SemanticVersion#DEFAULT}.
     * <p>
     * The expected version format is "major.minor.patch" or "major.minor.patch-qualifier" (e.g., "0.58.0" or
     * "0.58.0-SNAPSHOT"). The qualifier portion, if present, is ignored.
     *
     * @return the semantic version parsed from the system property or version file, or the default version
     * @throws RuntimeException if the version file is not found within 5 parent directories
     */
    @NonNull
    public static SemanticVersion fetchApplicationVersion() {
        // First, try to use system property
        final String versionStringFromEnv = System.getProperty("app.version");
        if (versionStringFromEnv != null && !versionStringFromEnv.isEmpty()) {
            return parseVersion(versionStringFromEnv);
        }

        // Fall back to read from version.txt file
        final Path versionFilePath = FileUtils.searchFileUpwards(VERSION_FILE_NAME, 5);
        try (final BufferedReader reader = Files.newBufferedReader(versionFilePath)) {
            final String versionString = reader.readLine();
            if (versionString != null && !versionString.isEmpty()) {
                return parseVersion(versionString);
            }
        } catch (final IOException e) {
            System.err.println("Failed to load version.txt: " + e.getMessage());
        }

        System.out.println("No version found in properties file or system property, using default version");
        return SemanticVersion.DEFAULT;
    }

    /**
     * Loads the WALL_CLOCK_TIME from the saved state metadata file.
     *
     * <p>This method:
     * <ol>
     *     <li>Locates the saved state directory (relative or absolute path)</li>
     *     <li>Finds the stateMetadata.txt file within the saved state structure</li>
     *     <li>Parses the WALL_CLOCK_TIME field and returns it</li>
     * </ol>
     *
     * @param savedStateDirectory the path to the saved state directory
     * @return the computed start instant (WALL_CLOCK_TIME + offset)
     * @throws IllegalArgumentException if the saved state directory does not exist
     * @throws IOException if an I/O error occurs while reading the metadata file
     */
    @NonNull
    public static Instant loadSavedStateWallClockTime(@NonNull final Path savedStateDirectory) throws IOException {
        requireNonNull(savedStateDirectory);

        // Locate stateMetadata.txt within the saved state
        // The structure is: stateDir/OtterApp/<nodeId>/<swirldId>/<roundNumber>/stateMetadata.txt
        // Since there's only one node's state, we can find the first metadata file
        final Path metadataFile;
        try (final var stream = Files.walk(savedStateDirectory, 5)) {
            metadataFile = stream.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().equals(SavedStateMetadata.FILE_NAME))
                    .findFirst()
                    .orElseThrow(() -> new IOException(
                            "stateMetadata.txt not found in saved state directory: " + savedStateDirectory));
        }

        final SavedStateMetadata metadata = SavedStateMetadata.parse(metadataFile);
        return metadata.wallClockTime();
    }

    /**
     * Parses a version string in "major.minor.patch" format into a {@link SemanticVersion}. Supports optional
     * qualifiers (e.g., "0.58.0-SNAPSHOT") which are ignored.
     *
     * @param versionString the version string to parse (e.g., "0.58.0" or "0.58.0-SNAPSHOT")
     * @return the parsed semantic version, or {@link SemanticVersion#DEFAULT} if parsing fails
     */
    @NonNull
    private static SemanticVersion parseVersion(@NonNull final String versionString) {
        try {
            // Strip optional qualifier (e.g., "-SNAPSHOT", "-RC1") by splitting on hyphen
            final String[] versionAndQualifier = versionString.split("-");
            final String versionNumbersOnly = versionAndQualifier[0];

            // Split version numbers by dot
            final String[] versionComponents = versionNumbersOnly.split("\\.");
            if (versionComponents.length < 3) {
                System.err.println("Invalid version format: " + versionString + ", expected format: major.minor.patch");
                return SemanticVersion.DEFAULT;
            }

            final int major = Integer.parseInt(versionComponents[0]);
            final int minor = Integer.parseInt(versionComponents[1]);
            final int patch = Integer.parseInt(versionComponents[2]);

            return SemanticVersion.newBuilder()
                    .major(major)
                    .minor(minor)
                    .patch(patch)
                    .build();
        } catch (final NumberFormatException e) {
            System.err.println("Failed to parse version from: " + versionString + ", using default");
            return SemanticVersion.DEFAULT;
        }
    }
}
