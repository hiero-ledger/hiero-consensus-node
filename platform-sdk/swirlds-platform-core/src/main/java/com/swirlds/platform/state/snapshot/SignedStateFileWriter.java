// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.state.snapshot;

import static com.swirlds.common.io.utility.FileUtils.executeAndRename;
import static com.swirlds.common.io.utility.FileUtils.writeAndFlush;
import static com.swirlds.logging.legacy.LogMarker.EXCEPTION;
import static com.swirlds.logging.legacy.LogMarker.STATE_TO_DISK;
import static com.swirlds.platform.config.internal.PlatformConfigUtils.writeSettingsUsed;
import static com.swirlds.platform.event.preconsensus.BestEffortPcesFileCopy.copyPcesFilesRetryOnFailure;
import static com.swirlds.platform.state.snapshot.SignedStateFileUtils.CURRENT_ROSTER_FILE_NAME;
import static com.swirlds.platform.state.snapshot.SignedStateFileUtils.HASH_INFO_FILE_NAME;
import static com.swirlds.platform.state.snapshot.SignedStateFileUtils.INIT_SIG_SET_FILE_VERSION;
import static com.swirlds.platform.state.snapshot.SignedStateFileUtils.SIGNATURE_SET_FILE_NAME;

import com.hedera.hapi.node.state.roster.Roster;
import com.swirlds.common.context.PlatformContext;
import com.swirlds.common.io.streams.MerkleDataOutputStream;
import com.swirlds.common.merkle.utility.MerkleTreeVisualizer;
import com.swirlds.logging.legacy.payload.StateSavedToDiskPayload;
import com.swirlds.platform.config.StateConfig;
import com.swirlds.platform.recovery.emergencyfile.EmergencyRecoveryFile;
import com.swirlds.platform.state.MerkleNodeState;
import com.swirlds.platform.state.service.PlatformStateFacade;
import com.swirlds.platform.state.signed.SigSet;
import com.swirlds.platform.state.signed.SignedState;
import com.swirlds.state.State;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Objects;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hiero.consensus.model.node.NodeId;

/**
 * Utility methods for writing a signed state to disk.
 */
public final class SignedStateFileWriter {

    private static final Logger logger = LogManager.getLogger(SignedStateFileWriter.class);

    private SignedStateFileWriter() {}

    /**
     * Write a file that contains information about the hash of the state. A useful nugget of information for when a
     * human needs to decide what is contained within a signed state file. If the file already exists in the given
     * directory then it is overwritten.
     *
     * @param platformContext the platform context
     * @param state           the state that is being written
     * @param directory       the directory where the state is being written
     */
    public static void writeHashInfoFile(
            @NonNull final PlatformContext platformContext,
            @NonNull final Path directory,
            @NonNull final MerkleNodeState state,
            @NonNull final PlatformStateFacade platformStateFacade)
            throws IOException {
        final StateConfig stateConfig = platformContext.getConfiguration().getConfigData(StateConfig.class);
        final String platformInfo = platformStateFacade.getInfoString(state, stateConfig.debugHashDepth());

        logger.info(
                STATE_TO_DISK.getMarker(),
                """
                        Information for state written to disk:
                        {}""",
                platformInfo);

        final Path hashInfoFile = directory.resolve(HASH_INFO_FILE_NAME);

        final String hashInfo = new MerkleTreeVisualizer(state.getRoot())
                .setDepth(stateConfig.debugHashDepth())
                .render();
        try (final BufferedWriter writer = new BufferedWriter(new FileWriter(hashInfoFile.toFile()))) {
            writer.write(hashInfo);
        }
    }

    /**
     * Write the signed state metadata file
     *
     * @param selfId      the id of the platform
     * @param directory   the directory to write to
     * @param signedState the signed state being written
     */
    public static void writeMetadataFile(
            @Nullable final NodeId selfId,
            @NonNull final Path directory,
            @NonNull final SignedState signedState,
            @NonNull final PlatformStateFacade platformStateFacade)
            throws IOException {
        Objects.requireNonNull(directory, "directory must not be null");
        Objects.requireNonNull(signedState, "signedState must not be null");

        final Path metadataFile = directory.resolve(SavedStateMetadata.FILE_NAME);

        SavedStateMetadata.create(signedState, selfId, Instant.now(), platformStateFacade)
                .write(metadataFile);
    }

    /**
     * Write a {@link SigSet} to a stream.
     *
     * @param out         the stream to write to
     * @param signedState the signed state to write
     */
    private static void writeSignatureSetToStream(final MerkleDataOutputStream out, final SignedState signedState)
            throws IOException {
        out.writeInt(INIT_SIG_SET_FILE_VERSION);
        out.writeProtocolVersion();
        out.writeSerializable(signedState.getSigSet(), true);
    }

    /**
     * Write the signature set file.
     * @param directory the directory to write to
     * @param signedState the signature set file
     */
    public static void writeSignatureSetFile(final @NonNull Path directory, final @NonNull SignedState signedState)
            throws IOException {
        writeAndFlush(directory.resolve(SIGNATURE_SET_FILE_NAME), out -> writeSignatureSetToStream(out, signedState));
    }

    /**
     * Write all files that belong in the signed state directory into a directory.
     *
     * @param platformContext the platform context
     * @param selfId          the id of the platform
     * @param directory       the directory where all files should be placed
     * @param signedState     the signed state being written to disk
     */
    public static void writeSignedStateFilesToDirectory(
            @Nullable final PlatformContext platformContext,
            @Nullable final NodeId selfId,
            @NonNull final Path directory,
            @NonNull final SignedState signedState,
            @NonNull final PlatformStateFacade platformStateFacade)
            throws IOException {
        Objects.requireNonNull(platformContext);
        Objects.requireNonNull(directory);
        Objects.requireNonNull(signedState);

        final State state = signedState.getState();

        state.createSnapshot(directory);
        writeSignatureSetFile(directory, signedState);
        writeHashInfoFile(platformContext, directory, signedState.getState(), platformStateFacade);
        writeMetadataFile(selfId, directory, signedState, platformStateFacade);
        writeEmergencyRecoveryFile(directory, signedState);
        final Roster currentRoster = signedState.getRoster();
        if (currentRoster != null) {
            writeRosterFile(directory, currentRoster);
        }
        writeSettingsUsed(directory, platformContext.getConfiguration());

        if (selfId != null) {
            copyPcesFilesRetryOnFailure(
                    platformContext,
                    selfId,
                    directory,
                    platformStateFacade.ancientThresholdOf(signedState.getState()),
                    signedState.getRound());
        }
    }

    /**
     * Write the state's roster in human-readable form.
     *
     * @param directory the directory to write to
     * @param roster    the roster to write
     */
    private static void writeRosterFile(@NonNull final Path directory, @NonNull final Roster roster)
            throws IOException {
        final Path rosterFile = directory.resolve(CURRENT_ROSTER_FILE_NAME);

        try (final BufferedWriter writer = new BufferedWriter(new FileWriter(rosterFile.toFile()))) {
            writer.write(Roster.JSON.toJSON(roster));
        }
    }

    /**
     * Writes a SignedState to a file. Also writes auxiliary files such as "settingsUsed.txt". This is the top level
     * method called by the platform when it is ready to write a state.
     *
     * @param platformContext     the platform context
     * @param selfId              the id of the platform
     * @param savedStateDirectory the directory where the state will be stored
     * @param signedState         the object to be written
     * @param stateToDiskReason   the reason the state is being written to disk
     */
    public static void writeSignedStateToDisk(
            @NonNull final PlatformContext platformContext,
            @Nullable final NodeId selfId,
            @NonNull final Path savedStateDirectory,
            @NonNull final SignedState signedState,
            @Nullable final StateToDiskReason stateToDiskReason,
            @NonNull final PlatformStateFacade platformStateFacade)
            throws IOException {

        Objects.requireNonNull(platformContext);
        Objects.requireNonNull(savedStateDirectory);
        Objects.requireNonNull(signedState);

        try {
            logger.info(
                    STATE_TO_DISK.getMarker(),
                    "Started writing round {} state to disk. Reason: {}, directory: {}",
                    signedState.getRound(),
                    stateToDiskReason == null ? "UNKNOWN" : stateToDiskReason,
                    savedStateDirectory);

            executeAndRename(
                    savedStateDirectory,
                    directory -> writeSignedStateFilesToDirectory(
                            platformContext, selfId, directory, signedState, platformStateFacade),
                    platformContext.getConfiguration());

            logger.info(STATE_TO_DISK.getMarker(), () -> new StateSavedToDiskPayload(
                            signedState.getRound(),
                            signedState.isFreezeState(),
                            stateToDiskReason == null ? "UNKNOWN" : stateToDiskReason.toString(),
                            savedStateDirectory)
                    .toString());
        } catch (final Throwable e) {
            logger.error(
                    EXCEPTION.getMarker(),
                    "Exception when writing the signed state for round {} to disk:",
                    signedState.getRound(),
                    e);
            throw e;
        }
    }

    private static void writeEmergencyRecoveryFile(final Path savedStateDirectory, final SignedState signedState)
            throws IOException {
        new EmergencyRecoveryFile(
                        signedState.getRound(), signedState.getState().getHash(), signedState.getConsensusTimestamp())
                .write(savedStateDirectory);
    }
}
