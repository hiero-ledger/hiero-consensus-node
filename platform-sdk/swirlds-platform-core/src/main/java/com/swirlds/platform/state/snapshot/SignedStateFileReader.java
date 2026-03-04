// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.state.snapshot;

import static com.swirlds.common.io.streams.StreamDebugUtils.deserializeAndDebugOnFailure;
import static com.swirlds.platform.state.snapshot.SignedStateFileUtils.SIGNATURE_SET_BIN_FILE_NAME;
import static com.swirlds.platform.state.snapshot.SignedStateFileUtils.SIGNATURE_SET_FILE_NAME;
import static com.swirlds.platform.state.snapshot.SignedStateFileUtils.SUPPORTED_SIGSET_VERSIONS;
import static java.nio.file.Files.exists;
import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.base.SemanticVersion;
import com.hedera.pbj.runtime.ParseException;
import com.hedera.pbj.runtime.io.stream.ReadableStreamingData;
import com.swirlds.common.context.PlatformContext;
import com.swirlds.config.api.Configuration;
import com.swirlds.platform.state.service.schemas.V0540RosterBaseSchema;
import com.swirlds.state.StateLifecycleManager;
import com.swirlds.state.lifecycle.Schema;
import com.swirlds.state.lifecycle.StateDefinition;
import com.swirlds.state.lifecycle.StateMetadata;
import com.swirlds.state.merkle.VirtualMapState;
import com.swirlds.virtualmap.VirtualMap;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Comparator;
import org.hiero.base.crypto.Hash;
import org.hiero.base.io.streams.SerializableDataInputStream;
import org.hiero.consensus.crypto.ConsensusCryptoUtils;
import org.hiero.consensus.platformstate.PlatformStateService;
import org.hiero.consensus.platformstate.V0540PlatformStateSchema;
import org.hiero.consensus.roster.RosterStateId;
import org.hiero.consensus.state.signed.SigSet;
import org.hiero.consensus.state.signed.SignedState;

/**
 * Utility methods for reading a signed state from disk.
 */
public final class SignedStateFileReader {
    private SignedStateFileReader() {}

    /**
     * Reads a SignedState from disk. If the reader throws an exception, it is propagated by this method to the caller.
     * <p>
     * This method delegates state loading to the {@link StateLifecycleManager}: it calls
     * {@link StateLifecycleManager#loadSnapshot(Path)} which loads the {@link VirtualMap} from disk,
     * wraps it in a state object, initializes the manager with it, and returns the hash of the original
     * immutable snapshot. The loaded state is then available via {@link StateLifecycleManager#getMutableState()}.
     *
     * @param stateDir              the directory to read from
     * @param platformContext       the platform context
     * @param stateLifecycleManager the state lifecycle manager
     * @return a signed state with its associated hash (as computed when the state was serialized)
     * @throws IOException    if there are any problems reading from a file
     * @throws ParseException if there are any problems parsing the signature set
     */
    public static @NonNull DeserializedSignedState readState(
            @NonNull final Path stateDir,
            @NonNull final PlatformContext platformContext,
            @NonNull final StateLifecycleManager<VirtualMapState, VirtualMap> stateLifecycleManager)
            throws IOException, ParseException {

        requireNonNull(stateDir);
        requireNonNull(platformContext);
        final Configuration conf = platformContext.getConfiguration();

        checkSignedStateFilePath(stateDir);

        // Load the snapshot: the manager wraps the VirtualMap in a VirtualMapStateImpl, initializes itself,
        // and returns the hash of the original immutable snapshot as stored on disk.
        final Hash originalHash = stateLifecycleManager.loadSnapshot(stateDir);
        final VirtualMapState virtualMapState = stateLifecycleManager.getMutableState();

        final SigSet sigSet;
        final File pbjFile = stateDir.resolve(SIGNATURE_SET_FILE_NAME).toFile();
        if (pbjFile.exists()) {
            sigSet = new SigSet();
            try (final ReadableStreamingData in = new ReadableStreamingData(new FileInputStream(pbjFile))) {
                sigSet.deserialize(in);
            }
        } else {
            final File sigSetFile =
                    stateDir.resolve(SIGNATURE_SET_BIN_FILE_NAME).toFile();
            sigSet = deserializeAndDebugOnFailure(
                    () -> new BufferedInputStream(new FileInputStream(sigSetFile)),
                    (final SerializableDataInputStream in) -> {
                        readAndCheckSigSetFileVersion(in);
                        return in.readSerializable();
                    });
        }

        final SignedState newSignedState = new SignedState(
                conf,
                ConsensusCryptoUtils::verifySignature,
                virtualMapState,
                "SignedStateFileReader.readState()",
                false,
                false,
                false);

        registerServiceStates(newSignedState);

        newSignedState.setSigSet(sigSet);

        return new DeserializedSignedState(newSignedState.reserve("SignedStateFileReader.readState()"), originalHash);
    }

    /**
     * Check the path of a signed state file
     *
     * @param stateDirectory the path to check
     */
    private static void checkSignedStateFilePath(@NonNull final Path stateDirectory) throws IOException {
        final Path signedStatePbjPath = stateDirectory.resolve(SIGNATURE_SET_FILE_NAME);
        final Path signedStateBinPath = stateDirectory.resolve(SIGNATURE_SET_BIN_FILE_NAME);
        if (!exists(signedStatePbjPath) && !exists(signedStateBinPath)) {
            throw new IOException(
                    "Directory " + stateDirectory.toAbsolutePath() + " does not contain a signature set!");
        }
    }

    /**
     * Read the version from a signature set file and check it
     *
     * @param in the stream to read from
     * @throws IOException if the version is invalid
     */
    private static void readAndCheckSigSetFileVersion(@NonNull final SerializableDataInputStream in)
            throws IOException {
        final int fileVersion = in.readInt();
        if (!SUPPORTED_SIGSET_VERSIONS.contains(fileVersion)) {
            throw new IOException("Unsupported file version: " + fileVersion);
        }
        in.readProtocolVersion();
    }

    /**
     * Register stub states for PlatformStateService and RosterService so that the State knows about them per the metadata and services registry.
     * <p>
     * Note that the state data objects associated with these services MUST ALREADY EXIST in the merkle tree (or on disk.)
     * These stubs WILL NOT create missing nodes in the state, or run any state migration code. The stubs assume that the
     * data structures present in the snapshot match the version of the software where this code runs.
     * <p>
     * These stubs are necessary to enable a state (a SignedState, in particular) to read the roster (or fall back
     * to reading the legacy AddressBook) from the state using the States API which would normally require
     * the complete initialization of services and all the schemas. However, only the PlatformState and RosterState/RosterMap
     * are really required to support reading the Roster (or AddressBook.) So we only initialize the schemas for these two.
     * <p>
     * If this SignedState object needs to become a real state to support the node operations later, the services/app
     * code will be responsible for initializing all the supported services. Note that the app skips registering
     * service states if it finds the PlatformState is already registered.
     *
     * @param signedState a signed state to register schemas in
     */
    public static void registerServiceStates(@NonNull final SignedState signedState) {
        registerServiceStates(signedState.getState());
    }

    /**
     * Register stub states for PlatformStateService and RosterService so that the State knows about them per the metadata and services registry.
     * See the doc for registerServiceStates(SignedState) above for more details.
     * @param state a State to register schemas in
     */
    public static void registerServiceStates(@NonNull final VirtualMapState state) {
        registerServiceState(state, new V0540PlatformStateSchema(), PlatformStateService.NAME);
        registerServiceState(state, new V0540RosterBaseSchema(), RosterStateId.SERVICE_NAME);
    }

    private static void registerServiceState(
            @NonNull final VirtualMapState state,
            @NonNull final Schema<SemanticVersion> schema,
            @NonNull final String name) {
        schema.statesToCreate().stream()
                .sorted(Comparator.comparing(StateDefinition::stateKey))
                .forEach(def -> {
                    final var md = new StateMetadata<>(name, def);
                    if (def.singleton() || def.keyValue()) {
                        state.initializeState(md);
                    } else {
                        throw new IllegalStateException(
                                "Only singletons and keyValue virtual maps are supported as stub states");
                    }
                });
    }
}
