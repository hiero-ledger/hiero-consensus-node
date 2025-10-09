// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform;

import static com.swirlds.logging.legacy.LogMarker.RECONNECT;

import com.hedera.hapi.node.base.SemanticVersion;
import com.hedera.hapi.node.state.roster.Roster;
import com.hedera.hapi.platform.state.ConsensusSnapshot;
import com.swirlds.common.context.PlatformContext;
import com.swirlds.common.stream.RunningEventHashOverride;
import com.swirlds.logging.legacy.LogMarker;
import com.swirlds.platform.components.SavedStateController;
import com.swirlds.platform.consensus.EventWindowUtils;
import com.swirlds.platform.state.ConsensusStateEventHandler;
import com.swirlds.platform.state.SwirldStateManager;
import com.swirlds.platform.state.nexus.SignedStateNexus;
import com.swirlds.platform.state.service.PlatformStateFacade;
import com.swirlds.platform.state.signed.SignedState;
import com.swirlds.platform.system.InitTrigger;
import com.swirlds.platform.system.Platform;
import com.swirlds.platform.system.status.actions.ReconnectCompleteAction;
import com.swirlds.platform.wiring.PlatformCoordinator;
import com.swirlds.state.MerkleNodeState;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Objects;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hiero.base.crypto.Hash;
import org.hiero.consensus.roster.RosterHistory;
import org.hiero.consensus.roster.RosterRetriever;
import org.hiero.consensus.roster.RosterUtils;

// FUTURE WORK: this data should be traveling out over the wiring framework.

/**
 * Encapsulates the logic for loading the reconnect state.
 */
public class ReconnectStateLoader {

    private static final Logger logger = LogManager.getLogger(ReconnectStateLoader.class);
    private final Platform platform;
    private final PlatformContext platformContext;
    private final PlatformCoordinator platformCoordinator;
    private final SwirldStateManager swirldStateManager;
    private final SignedStateNexus latestImmutableStateNexus;
    private final SavedStateController savedStateController;
    private final Roster roster;
    private final ConsensusStateEventHandler consensusStateEventHandler;
    private final PlatformStateFacade platformStateFacade;

    /**
     * Constructor.
     *
     * @param platform                  the platform
     * @param platformContext           the platform context
     * @param platformCoordinator            the platform wiring
     * @param swirldStateManager        manages the mutable state
     * @param latestImmutableStateNexus holds the latest immutable state
     * @param savedStateController      manages how states are saved
     * @param roster                    the current roster
     * @param consensusStateEventHandler           state lifecycle event handler
     */
    public ReconnectStateLoader(
            @NonNull final Platform platform,
            @NonNull final PlatformContext platformContext,
            @NonNull final PlatformCoordinator platformCoordinator,
            @NonNull final SwirldStateManager swirldStateManager,
            @NonNull final SignedStateNexus latestImmutableStateNexus,
            @NonNull final SavedStateController savedStateController,
            @NonNull final Roster roster,
            @NonNull final ConsensusStateEventHandler consensusStateEventHandler,
            @NonNull final PlatformStateFacade platformStateFacade) {
        this.platform = Objects.requireNonNull(platform);
        this.platformContext = Objects.requireNonNull(platformContext);
        this.platformCoordinator = Objects.requireNonNull(platformCoordinator);
        this.swirldStateManager = Objects.requireNonNull(swirldStateManager);
        this.latestImmutableStateNexus = Objects.requireNonNull(latestImmutableStateNexus);
        this.savedStateController = Objects.requireNonNull(savedStateController);
        this.roster = Objects.requireNonNull(roster);
        this.consensusStateEventHandler = consensusStateEventHandler;
        this.platformStateFacade = platformStateFacade;
    }

    /**
     * Used to load the state received from the sender.
     *
     * @param signedState the signed state that was received from the sender
     */
    public void loadReconnectState(@NonNull final SignedState signedState) {
        // the state was received, so now we load its data into different objects
        logger.info(LogMarker.STATE_HASH.getMarker(), "RECONNECT: loadReconnectState: reloading state");
        logger.debug(RECONNECT.getMarker(), "`loadReconnectState` : reloading state");
        try {
            platformCoordinator.overrideIssDetectorState(signedState.reserve("reconnect state to issDetector"));

            // It's important to call init() before loading the signed state. The loading process makes copies
            // of the state, and we want to be sure that the first state in the chain of copies has been initialized.
            final Hash reconnectHash = signedState.getState().getHash();
            final MerkleNodeState state = signedState.getState();
            final SemanticVersion creationSoftwareVersion = platformStateFacade.creationSoftwareVersionOf(state);
            signedState.init(platformContext);
            consensusStateEventHandler.onStateInitialized(
                    state, platform, InitTrigger.RECONNECT, creationSoftwareVersion);

            if (!Objects.equals(signedState.getState().getHash(), reconnectHash)) {
                throw new IllegalStateException(
                        "State hash is not permitted to change during a reconnect init() call. Previous hash was "
                                + reconnectHash + ", new hash is "
                                + signedState.getState().getHash());
            }

            // Before attempting to load the state, verify that the platform roster matches the state roster.
            final long round = platformStateFacade.roundOf(state);
            final Roster stateRoster = RosterRetriever.retrieveActive(state, round);
            if (!roster.equals(stateRoster)) {
                throw new IllegalStateException("Current roster and state-based roster do not contain the same nodes "
                        + " (currentRoster=" + Roster.JSON.toJSON(roster) + ") (stateRoster="
                        + Roster.JSON.toJSON(stateRoster) + ")");
            }

            swirldStateManager.loadFromSignedState(signedState);
            // kick off transition to RECONNECT_COMPLETE before beginning to save the reconnect state to disk
            // this guarantees that the platform status will be RECONNECT_COMPLETE before the state is saved
            platformCoordinator
                    .getStatusActionSubmitter()
                    .submitStatusAction(new ReconnectCompleteAction(signedState.getRound()));
            latestImmutableStateNexus.setState(signedState.reserve("set latest immutable to reconnect state"));
            savedStateController.reconnectStateReceived(
                    signedState.reserve("savedStateController.reconnectStateReceived"));

            platformCoordinator.sendStateToHashLogger(signedState);
            // this will send the state to the signature collector which will send it to be written to disk.
            // in the future, we might not send it to the collector because it already has all the signatures
            // if this is the case, we must make sure to send it to the writer directly
            platformCoordinator.putSignatureCollectorState(
                    signedState.reserve("loading reconnect state into sig collector"));
            final ConsensusSnapshot consensusSnapshot =
                    Objects.requireNonNull(platformStateFacade.consensusSnapshotOf(state));
            platformCoordinator.consensusSnapshotOverride(consensusSnapshot);

            final RosterHistory rosterHistory = RosterUtils.createRosterHistory(state);
            platformCoordinator.injectRosterHistory(rosterHistory);

            platformCoordinator.updateEventWindow(
                    EventWindowUtils.createEventWindow(consensusSnapshot, platformContext.getConfiguration()));

            final RunningEventHashOverride runningEventHashOverride =
                    new RunningEventHashOverride(platformStateFacade.legacyRunningEventHashOf(state), true);
            platformCoordinator.updateRunningHash(runningEventHashOverride);
            platformCoordinator.registerPcesDiscontinuity(signedState.getRound());

            // Notify any listeners that the reconnect has been completed
            platformCoordinator.sendReconnectCompleteNotification(signedState);

        } catch (final RuntimeException e) {
            logger.debug(RECONNECT.getMarker(), "`loadReconnectState` : FAILED, reason: {}", e.getMessage());
            // if the loading fails for whatever reason, we clear all data again in case some of it has been loaded
            platformCoordinator.clear();
            throw e;
        }
    }
}
