// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.builder;

import static com.swirlds.logging.legacy.LogMarker.SIGNED_STATE;
import static com.swirlds.logging.legacy.LogMarker.STARTUP;
import static com.swirlds.platform.system.InitTrigger.GENESIS;
import static com.swirlds.platform.system.InitTrigger.RESTART;
import static java.util.Objects.requireNonNull;
import static java.util.Objects.requireNonNullElse;
import static org.hiero.base.concurrent.interrupt.Uninterruptable.abortAndThrowIfInterrupted;
import static org.hiero.consensus.platformstate.PlatformStateUtils.consensusSnapshotOf;
import static org.hiero.consensus.platformstate.PlatformStateUtils.creationSoftwareVersionOf;
import static org.hiero.consensus.platformstate.PlatformStateUtils.getInfoString;
import static org.hiero.consensus.platformstate.PlatformStateUtils.legacyRunningEventHashOf;
import static org.hiero.consensus.platformstate.PlatformStateUtils.setCreationSoftwareVersionTo;
import static org.hiero.consensus.state.persistence.SignedStateFileUtils.CONSENSUS_SNAPSHOT_FILE_NAME;

import com.hedera.hapi.node.base.SemanticVersion;
import com.hedera.hapi.platform.state.ConsensusSnapshot;
import com.hedera.pbj.runtime.ParseException;
import com.swirlds.platform.state.ConsensusStateEventHandler;
import com.swirlds.platform.system.InitTrigger;
import com.swirlds.platform.system.Platform;
import com.swirlds.state.State;
import com.swirlds.state.StateLifecycleManager;
import com.swirlds.state.merkle.VirtualMapState;
import com.swirlds.virtualmap.VirtualMap;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.IOException;
import java.util.List;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hiero.base.crypto.Cryptography;
import org.hiero.base.crypto.Hash;
import org.hiero.consensus.ConsensusLayerAdapterBuildingBlocks;
import org.hiero.consensus.ConsensusLayerAdapterInputs;
import org.hiero.consensus.hashgraph.config.ConsensusConfig;
import org.hiero.consensus.model.hashgraph.EventWindow;
import org.hiero.consensus.model.stream.RunningEventHashOverride;
import org.hiero.consensus.platformstate.PlatformStateUtils;
import org.hiero.consensus.round.EventWindowUtils;
import org.hiero.consensus.state.config.StateConfig;
import org.hiero.consensus.state.persistence.SignedStateFilePath;
import org.hiero.consensus.state.persistence.SignedStateFileUtils;
import org.hiero.consensus.state.saved.SavedStateInfo;
import org.hiero.consensus.state.signed.SignedState;

/**
 * A static utility class for loading the initial state into the consensus layer.
 */
public class InitialStateLoader {

    private static final Logger logger = LogManager.getLogger();

    /**
     * Initializes all consensus layer modules with the initial state. This method is only for loading the initial state
     * immediately after constructing the consensus layer, not for use after a reconnect.
     *
     * @param platform            the newly constructed platform
     * @param inputs              consensus layer inputs from the execution layer
     * @param buildingBlocks      the consensus layer building blocks
     */
    public static void initializeModulesWithInitialState(
            @NonNull final Platform platform,
            @NonNull final ConsensusLayerAdapterInputs inputs,
            @NonNull final ConsensusLayerAdapterBuildingBlocks buildingBlocks) {
        final SignedState signedState = inputs.initialState().get();

        initializeState(platform, signedState, inputs.consensusStateEventHandler());

        // The StateLifecycleManager is already initialized before PlatformBuilder.build() is called:
        // - For genesis: the manager creates a genesis state eagerly in its constructor.
        // - For restart: loadSnapshot() initializes the manager when loading from disk.
        // - For reconnect: initWithState() re-initializes the manager at runtime.
        final StateLifecycleManager<VirtualMapState, VirtualMap> stateLifecycleManager = inputs.stateLifecycleManager();
        // Startup initialization may hash/freeze the state referenced by the initial SignedState.
        // Move the lifecycle manager to a fresh mutable copy before transaction handling begins.
        stateLifecycleManager.copyMutableState();
        // Genesis state must stay empty until changes can be externalized in the block stream
        if (!signedState.isGenesisState()) {
            setCreationSoftwareVersionTo(stateLifecycleManager.getMutableState(), inputs.version());
        }

        final Hash legacyRunningEventHash =
                requireNonNullElse(legacyRunningEventHashOf(signedState.getState()), Cryptography.NULL_HASH);
        final RunningEventHashOverride runningEventHashOverride =
                new RunningEventHashOverride(legacyRunningEventHash, false);
        buildingBlocks.runningEventHashOverrideWiring().runningHashUpdateInput().inject(runningEventHashOverride);

        // Load the minimum birth round into the pre-consensus event writer
        final String actualMainClassName =
                inputs.configuration().getConfigData(StateConfig.class).getMainClassName(inputs.appName());

        final SignedStateFilePath statePath = new SignedStateFilePath(
                inputs.fileSystemManager(), actualMainClassName, inputs.selfId(), inputs.swirldName());
        final List<SavedStateInfo> savedStates = statePath.getSavedStateFiles();
        if (!savedStates.isEmpty()) {
            try {
                final ConsensusSnapshot consensusSnapshot = SignedStateFileUtils.getConsensusSnapshot(savedStates.getLast().stateDirectory());
                buildingBlocks.consensusLayer().oldestRestartableSnapshot(consensusSnapshot);
            } catch (final IOException | ParseException e) {
                logger.warn(STARTUP.getMarker(),
                        "Unable to read {} file from round {} on disk - PCES for this round will be maintained "
                                + "until the next oldest state on disk has a readable consensus snapshot file.",
                        CONSENSUS_SNAPSHOT_FILE_NAME, savedStates.getLast().metadata().round());
            }
        }

        final boolean startedFromGenesis = signedState.isGenesisState();

        if (!startedFromGenesis) {
            buildingBlocks.stateModule().sendState(signedState);
            final ConsensusSnapshot consensusSnapshot = PlatformStateUtils.consensusSnapshotOf(signedState.getState());
            if (consensusSnapshot == null) {
                logger.error(STARTUP.getMarker(), "Initial state does not have a consensus snapshot. Unable to initialize the consensus node.");
                throw new IllegalStateException("Initial state does not have a consensus snapshot");
            }
            final int roundsNonAncient =
                    inputs.configuration().getConfigData(ConsensusConfig.class).roundsNonAncient();
            final EventWindow eventWindow = EventWindowUtils.createEventWindow(consensusSnapshot, roundsNonAncient);
            buildingBlocks.stateModule().initialEventWindowInputWire().inject(eventWindow);

            buildingBlocks.savedStateController().registerSignedStateFromDisk(signedState);

            buildingBlocks
                    .issDetectionModule()
                    .overridingStateInputWire()
                    .put(signedState.reserve("initialize issDetector"));
        }
    }

    /**
     * Initialize the state with the execution layer.
     *
     * @param platform the platform
     * @param signedState the state to initialize
     * @param consensusStateEventHandler the consensus state event handler
     */
    private static void initializeState(
            @NonNull final Platform platform,
            @NonNull final SignedState signedState,
            @NonNull final ConsensusStateEventHandler consensusStateEventHandler) {

        final SemanticVersion previousSoftwareVersion;
        final InitTrigger trigger;

        if (signedState.isGenesisState()) {
            previousSoftwareVersion = null;
            trigger = GENESIS;
        } else {
            previousSoftwareVersion = creationSoftwareVersionOf(signedState.getState());
            trigger = RESTART;
        }

        final State initialState = signedState.getState();

        // Although the state from disk / genesis state is initially hashed, we are actually dealing with a copy
        // of that state here. That copy should have caused the hash to be cleared. The hash must be calculated
        // after onStateInitialized(), so that it includes any changes to the state made in onStateInitialized().
        if (initialState.isHashed()) {
            throw new IllegalStateException("Expected initial state to be unhashed");
        }

        consensusStateEventHandler.onStateInitialized(
                signedState.getState(), platform, trigger, previousSoftwareVersion);

        // calculate hash
        abortAndThrowIfInterrupted(
                initialState::getHash, // calculate hash
                "interrupted while attempting to hash the state");

        // If our hash changes as a result of the new address book then our old signatures may become invalid.
        if (trigger != GENESIS) {
            signedState.pruneInvalidSignatures();
        }

        logger.info(STARTUP.getMarker(), """
                The platform is using the following initial state:
                {}""", getInfoString(signedState.getState()));
    }
}
