// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.util;

import static org.hiero.otter.fixtures.tools.GenerateStateTool.PCES_DIRECTORY;
import static org.hiero.otter.fixtures.tools.GenerateStateTool.SAVE_STATE_DIRECTORY;

import com.swirlds.common.io.utility.FileUtils;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.stream.Stream;
import org.hiero.consensus.model.node.NodeId;
import org.hiero.otter.fixtures.app.OtterApp;

/**
 * Utility methods for Otter
 * <p>
 * This class provides helper functions to find saved state directories and copy them to output locations,
 * renaming subdirectories as needed for test scenarios.
 */
public class OtterUtils {
    /**
     * Private constructor to prevent instantiation.
     */
    private OtterUtils() {
        // Utility class
    }

    /**
     * Finds the path to a saved state directory within the test resources.
     *
     * @param savedStateDirectory the name or path of the saved state directory, either relative to
     *                            {@code consensus-otter-tests/saved-states} or an absolute path
     * @return the {@link Path} to the saved state directory, or {@code null} if the input is empty
     * @throws IllegalArgumentException if the directory does not exist
     */
    @NonNull
    public static Path findSaveState(@NonNull final String savedStateDirectory) {
        if (savedStateDirectory.isEmpty()) {
            throw new IllegalArgumentException("Saved state directory is empty");
        }

        final Path directPath = Path.of(savedStateDirectory);
        if (Files.exists(directPath)) {
            return directPath;
        }

        final Path fallbackPath = Path.of(SAVE_STATE_DIRECTORY, savedStateDirectory);
        if (Files.exists(fallbackPath)) {
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
        Objects.requireNonNull(nodeId);
        Objects.requireNonNull(outputDir);
        Objects.requireNonNull(savedState);

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
    private static void renameToNodeId(final @NonNull NodeId nodeId, final Path appPath) throws IOException {
        try (final Stream<Path> stream = Files.list(appPath)) {
            stream.filter(Files::isDirectory)
                    .filter(p -> p.getFileName().toString().matches("\\d+"))
                    .findFirst()
                    .ifPresent(dir -> {
                        try {
                            int number = Integer.parseInt(dir.getFileName().toString());
                            if (number != nodeId.id()) {
                                FileUtils.moveDirectory(dir, appPath.resolve(nodeId.id() + ""));
                            }
                        } catch (IOException e) {
                            throw new RuntimeException(e);
                        }
                    });
        }
    }
}
