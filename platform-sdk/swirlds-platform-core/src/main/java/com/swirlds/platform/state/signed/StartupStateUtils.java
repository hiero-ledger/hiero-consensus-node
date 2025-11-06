// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.state.signed;

import static com.swirlds.common.merkle.utility.MerkleUtils.rehashTree;
import static com.swirlds.logging.legacy.LogMarker.EXCEPTION;
import static com.swirlds.logging.legacy.LogMarker.STARTUP;
import static com.swirlds.platform.state.signed.ReservedSignedState.createNullReservation;
import static com.swirlds.platform.state.snapshot.SignedStateFileReader.readStateFile;
import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.base.SemanticVersion;
import com.hedera.hapi.util.HapiUtils;
import com.swirlds.common.config.StateCommonConfig;
import com.swirlds.common.context.PlatformContext;
import com.swirlds.common.io.utility.RecycleBin;
import com.swirlds.config.api.Configuration;
import com.swirlds.logging.legacy.payload.SavedStateLoadedPayload;
import com.swirlds.platform.config.BasicConfig;
import com.swirlds.platform.config.StateConfig;
import com.swirlds.platform.crypto.CryptoStatic;
import com.swirlds.platform.internal.SignedStateLoadingException;
import com.swirlds.platform.state.service.PlatformStateFacade;
import com.swirlds.platform.state.snapshot.DeserializedSignedState;
import com.swirlds.platform.state.snapshot.SavedStateInfo;
import com.swirlds.platform.state.snapshot.SignedStateFilePath;
import com.swirlds.state.MerkleNodeState;
import com.swirlds.state.State;
import com.swirlds.virtualmap.VirtualMap;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Instant;
import java.util.List;
import java.util.function.Function;
import java.util.function.Supplier;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hiero.base.crypto.Hash;
import org.hiero.consensus.model.node.NodeId;
import org.hiero.consensus.model.roster.AddressBook;
import org.hiero.consensus.roster.RosterRetriever;
import org.hiero.consensus.roster.RosterUtils;

/**
 * Utilities for loading and manipulating state files at startup time.
 */
public final class StartupStateUtils {

    private static final Logger logger = LogManager.getLogger(StartupStateUtils.class);

    private StartupStateUtils() {}

    /**
     * Used exclusively by {@link com.swirlds.platform.Browser} to get the initial state to be used by this node.
     * May return a state loaded from disk, or may return a genesis state if no valid state is found on disk.
     *
     * @param softwareVersion     the software version of the app
     * @param genesisStateBuilder a supplier that can build a genesis state
     * @param createStateFromVirtualMap   a function to instantiate the state object from a Virtual Map
     * @param mainClassName       the name of the app's SwirldMain class
     * @param swirldName          the name of this swirld
     * @param selfId              the node id of this node
     * @param configAddressBook   the address book from config.txt
     * @return the initial state to be used by this node
     * @throws SignedStateLoadingException if there was a problem parsing states on disk and we are not configured to
     *                                     delete malformed states
     */
    @NonNull
    @Deprecated(forRemoval = true)
    public static HashedReservedSignedState getInitialState(
            @NonNull final RecycleBin recycleBin,
            @NonNull final SemanticVersion softwareVersion,
            @NonNull final Supplier<MerkleNodeState> genesisStateBuilder,
            @NonNull final Function<VirtualMap, MerkleNodeState> createStateFromVirtualMap,
            @NonNull final String mainClassName,
            @NonNull final String swirldName,
            @NonNull final NodeId selfId,
            @NonNull final AddressBook configAddressBook,
            @NonNull final PlatformStateFacade platformStateFacade,
            @NonNull final PlatformContext platformContext)
            throws SignedStateLoadingException {

        requireNonNull(mainClassName);
        requireNonNull(swirldName);
        requireNonNull(selfId);
        requireNonNull(configAddressBook);
        requireNonNull(platformContext);
        requireNonNull(platformContext.getConfiguration());

        final ReservedSignedState loadedState = StartupStateUtils.loadStateFile(
                recycleBin,
                selfId,
                mainClassName,
                swirldName,
                createStateFromVirtualMap,
                softwareVersion,
                platformStateFacade,
                platformContext);

        try (loadedState) {
            if (loadedState.isNotNull()) {
                logger.info(
                        STARTUP.getMarker(),
                        new SavedStateLoadedPayload(
                                loadedState.get().getRound(), loadedState.get().getConsensusTimestamp()));

                return copyInitialSignedState(loadedState.get(), platformStateFacade, platformContext);
            }
        }

        final ReservedSignedState genesisState = buildGenesisState(
                configAddressBook, softwareVersion, genesisStateBuilder.get(), platformStateFacade, platformContext);

        try (genesisState) {
            return copyInitialSignedState(genesisState.get(), platformStateFacade, platformContext);
        }
    }

    /**
     * Looks at the states on disk, chooses one to load, and then loads the chosen state.
     *
     * @param selfId                   the ID of this node
     * @param mainClassName            the name of the main class
     * @param swirldName               the name of the swirld
     * @param createStateFromVirtualMap a function to instantiate the state object from a Virtual Map
     * @param currentSoftwareVersion   the current software version
     * @return a reserved signed state (wrapped state will be null if no state could be loaded)
     * @throws SignedStateLoadingException if there was a problem parsing states on disk and we are not configured to
     *                                     delete malformed states
     */
    @NonNull
    public static ReservedSignedState loadStateFile(
            @NonNull final RecycleBin recycleBin,
            @NonNull final NodeId selfId,
            @NonNull final String mainClassName,
            @NonNull final String swirldName,
            @NonNull final Function<VirtualMap, MerkleNodeState> createStateFromVirtualMap,
            @NonNull final SemanticVersion currentSoftwareVersion,
            @NonNull final PlatformStateFacade platformStateFacade,
            @NonNull final PlatformContext platformContext) {

        final Configuration config = platformContext.getConfiguration();
        final StateConfig stateConfig = config.getConfigData(StateConfig.class);
        final String actualMainClassName = stateConfig.getMainClassName(mainClassName);

        final List<SavedStateInfo> savedStateFiles = new SignedStateFilePath(
                        config.getConfigData(StateCommonConfig.class))
                .getSavedStateFiles(actualMainClassName, selfId, swirldName);
        logStatesFound(savedStateFiles);

        if (savedStateFiles.isEmpty()) {
            // No states were found on disk.
            return createNullReservation();
        }

        return loadLatestState(
                recycleBin,
                currentSoftwareVersion,
                savedStateFiles,
                createStateFromVirtualMap,
                platformStateFacade,
                platformContext);
    }

    /**
     * Create a copy of the initial signed state. There are currently data structures that become immutable after being
     * hashed, and we need to make a copy to force it to become mutable again.
     *
     * @param initialSignedState the initial signed state
     * @return a copy of the initial signed state
     */
    public static @NonNull HashedReservedSignedState copyInitialSignedState(
            @NonNull final SignedState initialSignedState,
            @NonNull final PlatformStateFacade platformStateFacade,
            @NonNull final PlatformContext platformContext) {
        requireNonNull(platformContext);
        requireNonNull(platformContext.getConfiguration());
        requireNonNull(initialSignedState);

        final MerkleNodeState stateCopy = initialSignedState.getState().copy();
        final SignedState signedStateCopy = new SignedState(
                platformContext.getConfiguration(),
                CryptoStatic::verifySignature,
                stateCopy,
                "StartupStateUtils: copy initial state",
                false,
                false,
                false,
                platformStateFacade);
        signedStateCopy.setSigSet(initialSignedState.getSigSet());

        // FUTURE WORK: To support MerkleStateRoot in the testing apps we still need to use `digestTreeAsync` instead of
        // just calling `initialSignedState.getState().getRoot().getHash()`. The latter option doesn't work for
        // `MerkleStateRoot` as it doesn't cause hash recalculation. Once we get rid of `MerkleStateRoot` entirely,
        // the following statement can be replaced. (see
        // https://github.com/hiero-ledger/hiero-consensus-node/issues/19307)
        final Hash hash = platformContext
                .getMerkleCryptography()
                .digestTreeSync(initialSignedState.getState().getRoot());
        return new HashedReservedSignedState(signedStateCopy.reserve("Copied initial state"), hash);
    }

    /**
     * Log the states that were discovered on disk.
     *
     * @param savedStateFiles the states that were discovered on disk
     */
    private static void logStatesFound(@NonNull final List<SavedStateInfo> savedStateFiles) {
        if (savedStateFiles.isEmpty()) {
            logger.info(STARTUP.getMarker(), "No saved states were found on disk.");
            return;
        }
        final StringBuilder sb = new StringBuilder();
        sb.append("The following saved states were found on disk:");
        for (final SavedStateInfo savedStateFile : savedStateFiles) {
            sb.append("\n  - ").append(savedStateFile.stateFile());
        }
        logger.info(STARTUP.getMarker(), sb.toString());
    }

    /**
     * Load the latest state. If the latest state is invalid, try to load the next latest state. Repeat until a valid
     * state is found or there are no more states to try.
     *
     * @param currentSoftwareVersion the current software version
     * @param savedStateFiles        the saved states to try
     * @param createStateFromVirtualMap a function to instantiate the state object from a Virtual Map
     * @return the loaded state
     */
    public static ReservedSignedState loadLatestState(
            @NonNull final RecycleBin recycleBin,
            @NonNull final SemanticVersion currentSoftwareVersion,
            @NonNull final List<SavedStateInfo> savedStateFiles,
            @NonNull final Function<VirtualMap, MerkleNodeState> createStateFromVirtualMap,
            @NonNull final PlatformStateFacade platformStateFacade,
            @NonNull final PlatformContext platformContext)
            throws SignedStateLoadingException {

        logger.info(STARTUP.getMarker(), "Loading latest state from disk.");

        for (final SavedStateInfo savedStateFile : savedStateFiles) {
            final ReservedSignedState state = loadStateFile(
                    recycleBin,
                    currentSoftwareVersion,
                    savedStateFile,
                    createStateFromVirtualMap,
                    platformStateFacade,
                    platformContext);
            if (state != null) {
                return state;
            }
        }

        logger.warn(STARTUP.getMarker(), "No valid saved states were found on disk. Starting from genesis.");
        return createNullReservation();
    }

    /**
     * Load the requested state from file. If state can not be loaded, recycle the file and return null.
     *
     * @param currentSoftwareVersion the current software version
     * @param savedStateFile         the state to load
     * @param createStateFromVirtualMap a function to instantiate the state root object from a Virtual Map
     * @return the loaded state, or null if the state could not be loaded. Will be fully hashed if non-null.
     */
    @Nullable
    private static ReservedSignedState loadStateFile(
            @NonNull final RecycleBin recycleBin,
            @NonNull final SemanticVersion currentSoftwareVersion,
            @NonNull final SavedStateInfo savedStateFile,
            @NonNull final Function<VirtualMap, MerkleNodeState> createStateFromVirtualMap,
            @NonNull final PlatformStateFacade platformStateFacade,
            @NonNull final PlatformContext platformContext)
            throws SignedStateLoadingException {

        logger.info(STARTUP.getMarker(), "Loading signed state from disk: {}", savedStateFile.stateFile());

        final DeserializedSignedState deserializedSignedState;
        final Configuration configuration = platformContext.getConfiguration();
        try {
            deserializedSignedState = readStateFile(
                    savedStateFile.stateFile(), createStateFromVirtualMap, platformStateFacade, platformContext);
        } catch (final IOException e) {
            logger.error(EXCEPTION.getMarker(), "unable to load state file {}", savedStateFile.stateFile(), e);

            final StateConfig stateConfig = configuration.getConfigData(StateConfig.class);
            if (stateConfig.deleteInvalidStateFiles()) {
                recycleState(recycleBin, savedStateFile);
                return null;
            } else {
                throw new SignedStateLoadingException("unable to load state, this is unrecoverable");
            }
        }

        final MerkleNodeState state =
                deserializedSignedState.reservedSignedState().get().getState();

        final Hash oldHash = deserializedSignedState.originalHash();
        final Hash newHash = rehashTree(platformContext.getMerkleCryptography(), state.getRoot());

        final SemanticVersion loadedVersion = platformStateFacade.creationSoftwareVersionOf(state);

        if (oldHash.equals(newHash)) {
            logger.info(STARTUP.getMarker(), "Loaded state's hash is the same as when it was saved.");
        } else if (HapiUtils.SEMANTIC_VERSION_COMPARATOR.compare(loadedVersion, currentSoftwareVersion) == 0) {
            logger.error(
                    EXCEPTION.getMarker(),
                    "The saved state file {} was created with the current version of the software, "
                            + "but the state hash has changed. Unless the state was intentionally modified, "
                            + "this is a good indicator that there is probably a bug.",
                    savedStateFile.stateFile());
        } else {
            logger.warn(
                    STARTUP.getMarker(),
                    "The saved state file {} was created with version {}, which is different than the "
                            + "current version {}. The hash of the loaded state is different than the hash of the "
                            + "state when it was first created, which is not abnormal if there have been data "
                            + "migrations.",
                    savedStateFile.stateFile(),
                    loadedVersion,
                    currentSoftwareVersion);
        }

        return deserializedSignedState.reservedSignedState();
    }

    /**
     * Recycle a state.
     *
     * @param recycleBin  the recycleBin
     * @param stateInfo  the state to recycle
     */
    private static void recycleState(@NonNull final RecycleBin recycleBin, @NonNull final SavedStateInfo stateInfo) {
        logger.warn(STARTUP.getMarker(), "Moving state {} to the recycle bin.", stateInfo.stateFile());
        try {
            recycleBin.recycle(stateInfo.getDirectory());
        } catch (final IOException e) {
            throw new UncheckedIOException("unable to recycle state", e);
        }
    }

    /**
     * Build and initialize a genesis state.
     * <p>
     * <b>Important:</b> Only used by {@link com.swirlds.platform.Browser}.
     * @param addressBook           the current address book
     * @param appVersion            the software version of the app
     * @param stateRoot             the merkle root node of the state
     * @return a reserved genesis signed state
     */
    private static ReservedSignedState buildGenesisState(
            @NonNull final AddressBook addressBook,
            @NonNull final SemanticVersion appVersion,
            @NonNull final MerkleNodeState stateRoot,
            @NonNull final PlatformStateFacade platformStateFacade,
            @NonNull final PlatformContext platformContext) {
        initGenesisState(platformContext.getConfiguration(), stateRoot, platformStateFacade, addressBook, appVersion);

        final SignedState signedState = new SignedState(
                platformContext.getConfiguration(),
                CryptoStatic::verifySignature,
                stateRoot,
                "genesis state",
                false,
                false,
                false,
                platformStateFacade);
        return signedState.reserve("initial reservation on genesis state");
    }

    /**
     * Initializes a genesis platform state and RosterService state.
     * @param configuration the configuration for this node
     * @param state the State instance to initialize
     * @param platformStateFacade the facade to access the platform state
     * @param addressBook the current address book
     * @param appVersion the software version of the app
     */
    private static void initGenesisState(
            final Configuration configuration,
            final State state,
            final PlatformStateFacade platformStateFacade,
            final AddressBook addressBook,
            final SemanticVersion appVersion) {
        final long round = 0L;

        platformStateFacade.bulkUpdateOf(state, v -> {
            v.setCreationSoftwareVersion(appVersion);
            v.setRound(round);
            v.setLegacyRunningEventHash(null);
            v.setConsensusTimestamp(Instant.ofEpochSecond(0L));

            final BasicConfig basicConfig = configuration.getConfigData(BasicConfig.class);
        });

        RosterUtils.setActiveRoster(state, RosterRetriever.buildRoster(addressBook), round);
    }

    /**
     * Get the initial state to be used by a node. May return a state loaded from disk, or may return a genesis state
     * if no valid state is found on disk.
     *
     * @param recycleBin          the recycle bin to use
     * @param softwareVersion     the software version of the app
     * @param stateRootSupplier   a supplier that can build a genesis state
     * @param mainClassName       the name of the app's SwirldMain class
     * @param swirldName          the name of this swirld
     * @param selfId              the node id of this node
     * @param platformStateFacade an object to access the platform state
     * @param platformContext     the platform context
     * @return the initial state to be used by this node
     */
    @NonNull
    public static HashedReservedSignedState loadInitialState(
            @NonNull final RecycleBin recycleBin,
            @NonNull final SemanticVersion softwareVersion,
            @NonNull final Supplier<MerkleNodeState> stateRootSupplier,
            @NonNull final String mainClassName,
            @NonNull final String swirldName,
            @NonNull final NodeId selfId,
            @NonNull final PlatformStateFacade platformStateFacade,
            @NonNull final PlatformContext platformContext,
            @NonNull final Function<VirtualMap, MerkleNodeState> createStateFromVirtualMap) {
        final var loadedState = loadStateFile(
                recycleBin,
                selfId,
                mainClassName,
                swirldName,
                createStateFromVirtualMap,
                softwareVersion,
                platformStateFacade,
                platformContext);
        try (loadedState) {
            if (loadedState.isNotNull()) {
                logger.info(
                        STARTUP.getMarker(),
                        new SavedStateLoadedPayload(
                                loadedState.get().getRound(), loadedState.get().getConsensusTimestamp()));
                return copyInitialSignedState(loadedState.get(), platformStateFacade, platformContext);
            }
        }
        final var stateRoot = stateRootSupplier.get();
        final var signedState = new SignedState(
                platformContext.getConfiguration(),
                CryptoStatic::verifySignature,
                stateRoot,
                "genesis state",
                false,
                false,
                false,
                platformStateFacade);
        final var reservedSignedState = signedState.reserve("initial reservation on genesis state");
        try (reservedSignedState) {
            return copyInitialSignedState(reservedSignedState.get(), platformStateFacade, platformContext);
        }
    }
}
