// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.builder;

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

import com.hedera.hapi.node.base.SemanticVersion;
import com.hedera.hapi.platform.state.ConsensusSnapshot;
import com.swirlds.platform.state.ConsensusStateEventHandler;
import com.swirlds.platform.system.InitTrigger;
import com.swirlds.platform.system.Platform;
import com.swirlds.state.State;
import com.swirlds.state.StateLifecycleManager;
import com.swirlds.state.merkle.VirtualMapState;
import com.swirlds.virtualmap.VirtualMap;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.List;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hiero.base.crypto.Cryptography;
import org.hiero.base.crypto.Hash;
import org.hiero.consensus.ConsensusLayerBuildingBlocks;
import org.hiero.consensus.ConsensusLayerInputs;
import org.hiero.consensus.hashgraph.config.ConsensusConfig;
import org.hiero.consensus.model.stream.RunningEventHashOverride;
import org.hiero.consensus.round.EventWindowUtils;
import org.hiero.consensus.state.config.StateConfig;
import org.hiero.consensus.state.persistence.SignedStateFilePath;
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
            @NonNull final ConsensusLayerInputs inputs,
            @NonNull final ConsensusLayerBuildingBlocks buildingBlocks) {
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
            // The minimum birth round of non-ancient events for the oldest state snapshot on disk.
            final long minimumBirthRoundNonAncientForOldestState =
                    savedStates.getLast().metadata().minimumBirthRoundNonAncient();
            buildingBlocks.pcesModule().minimumBirthRoundInputWire().inject(minimumBirthRoundNonAncientForOldestState);
        }

        final boolean startedFromGenesis = signedState.isGenesisState();

        if (!startedFromGenesis) {
            buildingBlocks.stateModule().sendState(signedState);

            buildingBlocks.savedStateController().registerSignedStateFromDisk(signedState);

            final ConsensusSnapshot consensusSnapshot = requireNonNull(consensusSnapshotOf(signedState.getState()));
            buildingBlocks
                    .hashgraphModule()
                    .consensusSnapshotOverrideInputWire()
                    .inject(consensusSnapshot);

            // We only load non-ancient events during start up, so the initial expired threshold will be
            // equal to the ancient threshold when the system first starts. Over time as we get more events,
            // the expired threshold will continue to expand until it reaches its full size.
            final int roundsNonAncient =
                    inputs.configuration().getConfigData(ConsensusConfig.class).roundsNonAncient();
            buildingBlocks
                    .initialEventWindowDispatcher()
                    .getInputWire()
                    .inject(EventWindowUtils.createEventWindow(consensusSnapshot, roundsNonAncient));
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
