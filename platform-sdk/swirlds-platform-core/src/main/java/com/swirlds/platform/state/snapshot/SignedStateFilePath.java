// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.state.snapshot;

import static com.swirlds.common.io.utility.FileUtils.getAbsolutePath;
import static com.swirlds.common.merkle.utility.MerkleTreeSnapshotReader.SIGNED_STATE_FILE_NAME;
import static com.swirlds.logging.legacy.LogMarker.EXCEPTION;
import static java.nio.file.Files.exists;
import static java.nio.file.Files.isDirectory;

import com.swirlds.common.config.StateCommonConfig;
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
import org.hiero.consensus.model.node.NodeId;

/**
 * Utility methods for determining the path of signed states on disk.
 */
public class SignedStateFilePath {
    private static final Logger logger = LogManager.getLogger(SignedStateFilePath.class);
    final StateCommonConfig stateConfig;

    /**
     * Create a new instance of this class.
     *
     * @param stateConfig the config that contains the location of the saved state directory
     */
    public SignedStateFilePath(@NonNull final StateCommonConfig stateConfig) {
        this.stateConfig = stateConfig;
    }

    /**
     * <p>
     * Get the base directory where all states will be stored.
     * </p>
     *
     * <pre>
     * e.g. data/saved/
     *      |--------|
     *          |
     *       location where
     *       states are saved
     * </pre>
     *
     * @return the base directory for all signed state files
     */
    public @NonNull Path getSignedStatesBaseDirectory() {
        return getAbsolutePath(stateConfig.savedStateDirectory());
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
     * @param mainClassName the name of the app
     * @return the path of a directory, may not exist
     */
    public @NonNull Path getSignedStatesDirectoryForApp(final String mainClassName) {
        return getSignedStatesBaseDirectory().resolve(mainClassName);
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
     * @param mainClassName the name of the app
     * @param selfId        the ID of this node
     * @return the path of a directory, may not exist
     */
    public @NonNull Path getSignedStatesDirectoryForNode(final String mainClassName, final NodeId selfId) {
        return getSignedStatesDirectoryForApp(mainClassName).resolve(selfId.toString());
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
     * @param mainClassName the name of the app
     * @param selfId        the ID of this node
     * @param swirldName    the name of the swirld
     * @return the path of a directory, may not exist
     */
    public @NonNull Path getSignedStatesDirectoryForSwirld(
            final String mainClassName, final NodeId selfId, final String swirldName) {

        return getSignedStatesDirectoryForNode(mainClassName, selfId).resolve(swirldName);
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
     * @param mainClassName the name of the app
     * @param selfId        the ID of this node
     * @param swirldName    the name of the swirld
     * @param round         the round number of the state
     * @return the path of the signed state for the particular round
     */
    public @NonNull Path getSignedStateDirectory(
            final String mainClassName, final NodeId selfId, final String swirldName, final long round) {
        // FUTURE WORK: mainClass, selfId and swirldName never change during a run, so they should be constructor
        // parameters
        return getSignedStatesDirectoryForSwirld(mainClassName, selfId, swirldName)
                .resolve(Long.toString(round));
    }

    /**
     * Looks for saved state files locally and returns a list of them sorted from newest to oldest
     *
     * @param mainClassName
     * 		the name of the main app class
     * @param platformId
     * 		the ID of the platform
     * @param swirldName
     * 		the swirld name
     * @return Information about saved states on disk, or null if none are found
     */
    @SuppressWarnings("resource")
    @NonNull
    public List<SavedStateInfo> getSavedStateFiles(
            final String mainClassName, final NodeId platformId, final String swirldName) {

        try {
            final Path dir = getSignedStatesDirectoryForSwirld(mainClassName, platformId, swirldName);

            if (!exists(dir) || !isDirectory(dir)) {
                return List.of();
            }

            try (final Stream<Path> list = Files.list(dir)) {

                final List<Path> dirs = list.filter(Files::isDirectory).toList();

                final TreeMap<Long, SavedStateInfo> savedStates = new TreeMap<>();
                for (final Path subDir : dirs) {
                    try {
                        final long round = Long.parseLong(subDir.getFileName().toString());
                        final Path stateFile = subDir.resolve(SIGNED_STATE_FILE_NAME);
                        if (!exists(stateFile)) {
                            logger.warn(
                                    EXCEPTION.getMarker(),
                                    "Saved state file ({}) not found, but directory exists '{}'",
                                    stateFile.getFileName(),
                                    subDir.toAbsolutePath());
                            continue;
                        }

                        final Path metdataPath = subDir.resolve(SavedStateMetadata.FILE_NAME);
                        final SavedStateMetadata metadata;
                        try {
                            metadata = SavedStateMetadata.parse(metdataPath);
                        } catch (final IOException e) {
                            logger.error(
                                    EXCEPTION.getMarker(),
                                    "Unable to read saved state metadata file '{}'",
                                    metdataPath);
                            continue;
                        }

                        savedStates.put(round, new SavedStateInfo(stateFile, metadata));

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
