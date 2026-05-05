// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.state.snapshot;

import static com.swirlds.logging.legacy.LogMarker.EXCEPTION;
import static java.nio.file.Files.exists;
import static java.nio.file.Files.isDirectory;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;
import java.util.stream.Stream;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hiero.base.file.FileSystemManager;
import org.hiero.consensus.config.PathsConfig;
import org.hiero.consensus.model.node.NodeId;

/**
 * Utility methods for determining the path of signed states on disk.
 */
public class SignedStateFilePath {
    private static final Logger logger = LogManager.getLogger();

    private final FileSystemManager fileSystemManager;
    private final String mainClassName;
    private final NodeId selfId;
    private final String swirldName;

    /**
     * Create a new instance of this class.
     *
     * @param fileSystemManager the file system manager to use for writing signed states and associated data. The root
     *                          location of the file system manager must be set to the location where signed states
     *                          should be saved according to {@link PathsConfig#savedStateDir}.
     */
    public SignedStateFilePath(
            @NonNull final FileSystemManager fileSystemManager,
            @NonNull final String mainClassName,
            @NonNull final NodeId selfId,
            @NonNull final String swirldName) {
        this.fileSystemManager = fileSystemManager;
        this.mainClassName = mainClassName;
        this.selfId = selfId;
        this.swirldName = swirldName;
    }

    /**
     * <p>
     * Get the directory that contains saved states for a particular app.
     * </p>
     *
     * <pre>
     * e.g. data/saved/com.swirlds.foobar
     *      |--------| |----------------|
     *          |             |
     *          |         mainClassName
     *          |
     *       location where
     *       states are saved
     * </pre>
     *
     * @return the path of a directory, may not exist
     */
    private @NonNull Path getSignedStatesDirectoryForApp() {
        return fileSystemManager.resolve(mainClassName);
    }

    /**
     * <p>
     * Get the directory that contains contains saved states for a particular node.
     * </p>
     *
     * <pre>
     * e.g. data/saved/com.swirlds.foobar/1234
     *      |--------| |----------------| |--|
     *          |             |            |
     *          |         mainClassName    |
     *          |                          |
     *       location where              selfId
     *       states are saved
     * </pre>
     *
     * @return the path of a directory, may not exist
     */
    private @NonNull Path getSignedStatesDirectoryForNode() {
        return getSignedStatesDirectoryForApp().resolve(selfId.toString());
    }

    /**
     * <p>
     * Get the directory that contains saved states for a particular swirld (i.e. an instance of an app).
     * </p>
     *
     * <pre>
     * e.g. data/saved/com.swirlds.foobar/1234/mySwirld
     *      |--------| |----------------| |--| |------|
     *          |             |            |       |
     *          |         mainClassName    |    swirldName
     *          |                          |
     *       location where              selfId
     *       states are saved
     * </pre>
     *
     * @return the path of a directory, may not exist
     */
    public @NonNull Path getSignedStatesDirectoryForSwirld() {

        return getSignedStatesDirectoryForNode().resolve(swirldName);
    }

    /**
     * <p>
     * Get the fully qualified path to the directory for a particular signed state. This directory might not exist.
     * </p>
     *
     * <pre>
     * e.g. data/saved/com.swirlds.foobar/1234/mySwirld/1000
     *      |--------| |----------------| |--| |------| |--|
     *          |             |            |      |      |
     *          |         mainClassName    |      |    round
     *          |                          |  swirldName
     *       location where              selfId
     *       states are saved
     *
     * </pre>
     *
     * @param round the round number of the state
     * @return the path of the signed state for the particular round
     */
    public @NonNull Path getSignedStateDirectory(final long round) {
        return getSignedStatesDirectoryForSwirld().resolve(Long.toString(round));
    }

    /**
     * Looks for saved state files locally and returns a list of them sorted from newest to oldest
     *
     * @return Information about saved states on disk, or null if none are found
     */
    @SuppressWarnings("resource")
    @NonNull
    public List<SavedStateInfo> getSavedStateFiles() {
        final Path dir = getSignedStatesDirectoryForSwirld();
        return getSavedStateFiles(dir);
    }

    /**
     * Looks for saved state files locally and returns a list of them sorted from newest to oldest
     *
     * @param dir the path for reading
     * @return Information about saved states on disk, or null if none are found
     */
    public static List<SavedStateInfo> getSavedStateFiles(final Path dir) {
        if (!exists(dir) || !isDirectory(dir)) {
            return List.of();
        }
        try {
            try (final Stream<Path> list = Files.list(dir)) {

                final List<Path> dirs = list.filter(Files::isDirectory).toList();

                final TreeMap<Long, SavedStateInfo> savedStates = new TreeMap<>();
                for (final Path subDir : dirs) {
                    try {
                        final long round = Long.parseLong(subDir.getFileName().toString());
                        final Path stateMetadataPath = subDir.resolve(SavedStateMetadata.FILE_NAME);
                        final SavedStateMetadata metadata;
                        try {
                            metadata = SavedStateMetadata.parse(stateMetadataPath);
                        } catch (final IOException e) {
                            logger.error(
                                    EXCEPTION.getMarker(),
                                    "Unable to read saved state metadata file '{}'",
                                    stateMetadataPath);
                            continue;
                        }

                        savedStates.put(round, new SavedStateInfo(subDir, metadata));

                    } catch (final NumberFormatException e) {
                        logger.warn(
                                EXCEPTION.getMarker(),
                                "Unexpected directory '{}' in '{}'",
                                subDir.getFileName(),
                                dir.toAbsolutePath());
                    }
                }
                return new ArrayList<>(savedStates.descendingMap().values());
            }
        } catch (final IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
