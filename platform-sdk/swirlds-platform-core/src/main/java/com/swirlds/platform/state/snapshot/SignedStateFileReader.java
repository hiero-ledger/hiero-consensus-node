// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.state.snapshot;

import static com.swirlds.common.io.streams.StreamDebugUtils.deserializeAndDebugOnFailure;
import static com.swirlds.platform.state.snapshot.SignedStateFileUtils.SIGNATURE_SET_FILE_NAME;
import static com.swirlds.platform.state.snapshot.SignedStateFileUtils.SUPPORTED_SIGSET_VERSIONS;
import static java.nio.file.Files.exists;
import static java.util.Objects.requireNonNull;

import com.swirlds.common.context.PlatformContext;
import com.swirlds.common.io.streams.MerkleDataInputStream;
import com.swirlds.common.merkle.utility.MerkleTreeSnapshotReader;
import com.swirlds.config.api.Configuration;
import com.swirlds.metrics.api.Metrics;
import com.swirlds.platform.crypto.CryptoStatic;
import com.swirlds.platform.state.MerkleNodeState;
import com.swirlds.platform.state.service.PlatformStateFacade;
import com.swirlds.platform.state.service.PlatformStateService;
import com.swirlds.platform.state.service.schemas.V0540PlatformStateSchema;
import com.swirlds.platform.state.service.schemas.V0540RosterBaseSchema;
import com.swirlds.platform.state.signed.SigSet;
import com.swirlds.platform.state.signed.SignedState;
import com.swirlds.state.lifecycle.Schema;
import com.swirlds.state.lifecycle.StateDefinition;
import com.swirlds.state.lifecycle.StateMetadata;
import com.swirlds.virtualmap.VirtualMap;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.function.Function;
import org.hiero.consensus.roster.RosterStateId;

/**
 * Utility methods for reading a signed state from disk.
 */
public final class SignedStateFileReader {
    private SignedStateFileReader() {}

    /**
     * Reads a SignedState from disk. If the reader throws an exception, it is propagated by this method to the caller.
     *
     * @param stateFile                     the file to read from
     * @param createStateFromVirtualMap     a function to instantiate the state object from a Virtual Map
     * @param stateFacade                   the facade to access the platform state
     * @return a signed state with it's associated hash (as computed when the state was serialized)
     * @throws IOException if there is any problems with reading from a file
     */
    public static @NonNull DeserializedSignedState readStateFile(
            @NonNull final Path stateFile,
            @NonNull final Function<VirtualMap, MerkleNodeState> createStateFromVirtualMap,
            @NonNull final PlatformStateFacade stateFacade,
            @NonNull final PlatformContext platformContext)
            throws IOException {

        requireNonNull(stateFile);
        requireNonNull(platformContext);
        final Configuration conf = platformContext.getConfiguration();

        checkSignedStatePath(stateFile);

        final DeserializedSignedState returnState;
        final MerkleTreeSnapshotReader.StateFileData data = MerkleTreeSnapshotReader.readStateFileData(conf, stateFile);
        final File sigSetFile =
                stateFile.getParent().resolve(SIGNATURE_SET_FILE_NAME).toFile();
        final SigSet sigSet = deserializeAndDebugOnFailure(
                () -> new BufferedInputStream(new FileInputStream(sigSetFile)), (final MerkleDataInputStream in) -> {
                    readAndCheckSigSetFileVersion(in);
                    return in.readSerializable();
                });

        final VirtualMap virtualMap = (VirtualMap) data.stateRoot();
        final Metrics metrics = platformContext.getMetrics();
        virtualMap.registerMetrics(metrics);
        final MerkleNodeState merkleNodeState = createStateFromVirtualMap.apply(virtualMap);

        final SignedState newSignedState = new SignedState(
                conf,
                CryptoStatic::verifySignature,
                merkleNodeState,
                "SignedStateFileReader.readStateFile()",
                false,
                false,
                false,
                stateFacade);
        newSignedState.init(platformContext);

        registerServiceStates(newSignedState);

        newSignedState.setSigSet(sigSet);

        returnState = new DeserializedSignedState(
                newSignedState.reserve("SignedStateFileReader.readStateFile()"), data.hash());

        return returnState;
    }

    /**
     * Check the path of a signed state file
     *
     * @param stateFile the path to check
     * @throws IOException if the path is not valid
     */
    private static void checkSignedStatePath(@NonNull final Path stateFile) throws IOException {
        if (!exists(stateFile)) {
            throw new IOException("File " + stateFile.toAbsolutePath() + " does not exist!");
        }
        if (!Files.isRegularFile(stateFile)) {
            throw new IOException("File " + stateFile.toAbsolutePath() + " is not a file!");
        }
    }

    /**
     * Read the version from a signature set file and check it
     *
     * @param in the stream to read from
     * @throws IOException if the version is invalid
     */
    private static void readAndCheckSigSetFileVersion(@NonNull final MerkleDataInputStream in) throws IOException {
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
     * service states if it finds the PlatformState is already registered. For this reason, a call to
     * {@code SignedStateFileReader.unregisterServiceStates(SignedState)} below needs to be made to remove the stubs.
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
    public static void registerServiceStates(@NonNull final MerkleNodeState state) {
        registerServiceState(state, new V0540PlatformStateSchema(), PlatformStateService.NAME);
        registerServiceState(state, new V0540RosterBaseSchema(), RosterStateId.NAME);
    }

    private static void registerServiceState(
            @NonNull final MerkleNodeState state, @NonNull final Schema schema, @NonNull final String name) {
        schema.statesToCreate().stream()
                .sorted(Comparator.comparing(StateDefinition::stateKey))
                .forEach(def -> {
                    final var md = new StateMetadata<>(name, schema, def);
                    if (def.singleton() || def.onDisk()) {
                        state.initializeState(md);
                    } else {
                        throw new IllegalStateException(
                                "Only singletons and onDisk virtual maps are supported as stub states");
                    }
                });
    }

    /**
     * Unregister the PlatformStateService and RosterService so that the app
     * can initialize States API eventually. Currently, it wouldn't initialize it
     * if it sees the PlatformStateService already present. This check occurs at
     * Hedera.onStateInitialized().
     *
     * See the doc for registerServiceStates above for more details on why
     * we initialize these stub states in the first place.
     *
     * @param signedState a signed state to unregister services from
     */
    public static void unregisterServiceStates(@NonNull final SignedState signedState) {
        final MerkleNodeState state = signedState.getState();
        state.unregisterService(PlatformStateService.NAME);
        state.unregisterService(RosterStateId.NAME);
    }
}
