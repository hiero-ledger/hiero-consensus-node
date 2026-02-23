// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.state.signed;

import static com.swirlds.logging.legacy.LogMarker.EXCEPTION;
import static com.swirlds.logging.legacy.LogMarker.STARTUP;
import static com.swirlds.platform.state.snapshot.SignedStateFileReader.readState;
import static java.util.Objects.requireNonNull;
import static org.hiero.consensus.platformstate.PlatformStateUtils.creationSoftwareVersionOf;
import static org.hiero.consensus.state.signed.ReservedSignedState.createNullReservation;

import com.hedera.hapi.node.base.SemanticVersion;
import com.hedera.hapi.util.HapiUtils;
import com.hedera.pbj.runtime.ParseException;
import com.swirlds.common.config.StateCommonConfig;
import com.swirlds.common.context.PlatformContext;
import com.swirlds.config.api.Configuration;
import com.swirlds.logging.legacy.payload.SavedStateLoadedPayload;
import com.swirlds.platform.internal.SignedStateLoadingException;
import com.swirlds.platform.state.snapshot.DeserializedSignedState;
import com.swirlds.platform.state.snapshot.SavedStateInfo;
import com.swirlds.platform.state.snapshot.SignedStateFilePath;
import com.swirlds.state.StateLifecycleManager;
import com.swirlds.state.merkle.VirtualMapState;
import com.swirlds.virtualmap.VirtualMap;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.function.Supplier;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hiero.base.crypto.Hash;
import org.hiero.consensus.crypto.ConsensusCryptoUtils;
import org.hiero.consensus.io.RecycleBin;
import org.hiero.consensus.model.node.NodeId;
import org.hiero.consensus.state.config.StateConfig;
import org.hiero.consensus.state.signed.ReservedSignedState;
import org.hiero.consensus.state.signed.SignedState;

/**
 * Utilities for loading and manipulating state files at startup time.
 */
public final class StartupStateUtils {

    private static final Logger logger = LogManager.getLogger(StartupStateUtils.class);

    private StartupStateUtils() {}

    /**
     * Looks at the states on disk, chooses one to load, and then loads the chosen state.
     *
     * @param selfId                   the ID of this node
     * @param mainClassName            the name of the main class
     * @param swirldName               the name of the swirld
     * @param currentSoftwareVersion   the current software version
     * @param platformContext          the platform context
     * @param stateLifecycleManager    state lifecycle manager
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
            @NonNull final SemanticVersion currentSoftwareVersion,
            @NonNull final PlatformContext platformContext,
            @NonNull final StateLifecycleManager<VirtualMapState, VirtualMap> stateLifecycleManager) {

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
                recycleBin, currentSoftwareVersion, savedStateFiles, platformContext, stateLifecycleManager);
    }

    /**
     * Create a copy of the initial signed state. There are currently data structures that become immutable after being
     * hashed, and we need to make a copy to force it to become mutable again.
     *
     * @param initialSignedState the initial signed state
     * @return a copy of the initial signed state
     */
    public static @NonNull HashedReservedSignedState copyInitialSignedState(
            @NonNull final SignedState initialSignedState, @NonNull final PlatformContext platformContext) {
        requireNonNull(platformContext);
        requireNonNull(platformContext.getConfiguration());
        requireNonNull(initialSignedState);

        final VirtualMapState stateCopy = initialSignedState.getState().copy();
        final SignedState signedStateCopy = new SignedState(
                platformContext.getConfiguration(),
                ConsensusCryptoUtils::verifySignature,
                stateCopy,
                "StartupStateUtils: copy initial state",
                false,
                false,
                false);
        signedStateCopy.setSigSet(initialSignedState.getSigSet());

        final Hash hash = initialSignedState.getState().getHash();
        return new HashedReservedSignedState(signedStateCopy.reserve("Copied initial state"), hash);
    }

    /**
     * Log the states that were discovered on disk.
     *
     * @param savedStateInfoList the states that were discovered on disk
     */
    private static void logStatesFound(@NonNull final List<SavedStateInfo> savedStateInfoList) {
        if (savedStateInfoList.isEmpty()) {
            logger.info(STARTUP.getMarker(), "No saved states were found on disk.");
            return;
        }
        final StringBuilder sb = new StringBuilder();
        sb.append("The following saved states were found on disk:");
        for (final SavedStateInfo savedStateInfo : savedStateInfoList) {
            sb.append("\n  - ").append(savedStateInfo.stateDirectory());
        }
        logger.info(STARTUP.getMarker(), sb.toString());
    }

    /**
     * Load the latest state. If the latest state is invalid, try to load the next latest state. Repeat until a valid
     * state is found or there are no more states to try.
     *
     * @param currentSoftwareVersion the current software version
     * @param savedStateList        the saved states to try
     * @param platformContext       the platform context
     * @param stateLifecycleManager state lifecycle manager
     * @return the loaded state
     */
    public static ReservedSignedState loadLatestState(
            @NonNull final RecycleBin recycleBin,
            @NonNull final SemanticVersion currentSoftwareVersion,
            @NonNull final List<SavedStateInfo> savedStateList,
            @NonNull final PlatformContext platformContext,
            @NonNull final StateLifecycleManager<VirtualMapState, VirtualMap> stateLifecycleManager)
            throws SignedStateLoadingException {

        logger.info(STARTUP.getMarker(), "Loading latest state from disk.");

        for (final SavedStateInfo savedStateInfo : savedStateList) {
            final ReservedSignedState state = loadStateFile(
                    recycleBin, currentSoftwareVersion, savedStateInfo, platformContext, stateLifecycleManager);
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
     * @param savedStateInfo         the state to load
     * @param platformContext        the platform context
     * @param stateLifecycleManager  state lifecycle manager
     * @return the loaded state, or null if the state could not be loaded. Will be fully hashed if non-null.
     */
    @Nullable
    private static ReservedSignedState loadStateFile(
            @NonNull final RecycleBin recycleBin,
            @NonNull final SemanticVersion currentSoftwareVersion,
            @NonNull final SavedStateInfo savedStateInfo,
            @NonNull final PlatformContext platformContext,
            @NonNull final StateLifecycleManager<VirtualMapState, VirtualMap> stateLifecycleManager)
            throws SignedStateLoadingException {

        logger.info(STARTUP.getMarker(), "Loading signed state from disk: {}", savedStateInfo.stateDirectory());

        final DeserializedSignedState deserializedSignedState;
        final Configuration configuration = platformContext.getConfiguration();
        try {
            deserializedSignedState =
                    readState(savedStateInfo.stateDirectory(), platformContext, stateLifecycleManager);
        } catch (final IOException | UncheckedIOException | ParseException e) {
            logger.error(EXCEPTION.getMarker(), "unable to load state file {}", savedStateInfo.stateDirectory(), e);

            final StateConfig stateConfig = configuration.getConfigData(StateConfig.class);
            if (stateConfig.deleteInvalidStateFiles()) {
                recycleState(recycleBin, savedStateInfo);
                return null;
            } else {
                throw new SignedStateLoadingException("unable to load state, this is unrecoverable", e);
            }
        }

        final VirtualMapState state =
                deserializedSignedState.reservedSignedState().get().getState();

        final Hash oldHash = deserializedSignedState.originalHash();
        final Hash newHash = state.getHash();

        final SemanticVersion loadedVersion = creationSoftwareVersionOf(state);

        if (oldHash.equals(newHash)) {
            logger.info(STARTUP.getMarker(), "Loaded state's hash is the same as when it was saved.");
        } else if (HapiUtils.SEMANTIC_VERSION_COMPARATOR.compare(loadedVersion, currentSoftwareVersion) == 0) {
            logger.error(
                    EXCEPTION.getMarker(),
                    "The saved state {} was created with the current version of the software, "
                            + "but the state hash has changed. Unless the state was intentionally modified, "
                            + "this is a good indicator that there is probably a bug.",
                    savedStateInfo.stateDirectory());
        } else {
            logger.warn(
                    STARTUP.getMarker(),
                    "The saved state {} was created with version {}, which is different than the "
                            + "current version {}. The hash of the loaded state is different than the hash of the "
                            + "state when it was first created, which is not abnormal if there have been data "
                            + "migrations.",
                    savedStateInfo.stateDirectory(),
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
        logger.warn(STARTUP.getMarker(), "Moving state {} to the recycle bin.", stateInfo.stateDirectory());
        try {
            recycleBin.recycle(stateInfo.stateDirectory());
        } catch (final IOException e) {
            throw new UncheckedIOException("unable to recycle state", e);
        }
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
     * @param platformContext     the platform context
     * @return the initial state to be used by this node
     */
    @NonNull
    public static HashedReservedSignedState loadInitialState(
            @NonNull final RecycleBin recycleBin,
            @NonNull final SemanticVersion softwareVersion,
            @NonNull final Supplier<VirtualMapState> stateRootSupplier,
            @NonNull final String mainClassName,
            @NonNull final String swirldName,
            @NonNull final NodeId selfId,
            @NonNull final PlatformContext platformContext,
            @NonNull final StateLifecycleManager<VirtualMapState, VirtualMap> stateLifecycleManager) {
        final var loadedState = loadStateFile(
                recycleBin, selfId, mainClassName, swirldName, softwareVersion, platformContext, stateLifecycleManager);
        try (loadedState) {
            if (loadedState.isNotNull()) {
                logger.info(
                        STARTUP.getMarker(),
                        new SavedStateLoadedPayload(
                                loadedState.get().getRound(), loadedState.get().getConsensusTimestamp()));
                return copyInitialSignedState(loadedState.get(), platformContext);
            }
        }
        final var stateRoot = stateRootSupplier.get();
        final var signedState = new SignedState(
                platformContext.getConfiguration(),
                ConsensusCryptoUtils::verifySignature,
                stateRoot,
                "genesis state",
                false,
                false,
                false);
        final var reservedSignedState = signedState.reserve("initial reservation on genesis state");
        try (reservedSignedState) {
            return copyInitialSignedState(reservedSignedState.get(), platformContext);
        }
    }
}
