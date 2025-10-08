// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.reconnect;

import static com.swirlds.logging.legacy.LogMarker.EXCEPTION;
import static com.swirlds.logging.legacy.LogMarker.RECONNECT;
import static com.swirlds.logging.legacy.LogMarker.STARTUP;

import com.hedera.hapi.node.base.SemanticVersion;
import com.hedera.hapi.node.state.roster.Roster;
import com.hedera.hapi.platform.state.ConsensusSnapshot;
import com.swirlds.base.state.Startable;
import com.swirlds.base.time.Time;
import com.swirlds.common.context.PlatformContext;
import com.swirlds.common.merkle.crypto.MerkleCryptography;
import com.swirlds.common.merkle.synchronization.config.ReconnectConfig;
import com.swirlds.common.stream.RunningEventHashOverride;
import com.swirlds.common.threading.framework.config.ThreadConfiguration;
import com.swirlds.common.threading.manager.ThreadManager;
import com.swirlds.logging.legacy.LogMarker;
import com.swirlds.logging.legacy.payload.ReconnectFailurePayload;
import com.swirlds.logging.legacy.payload.ReconnectLoadFailurePayload;
import com.swirlds.logging.legacy.payload.UnableToReconnectPayload;
import com.swirlds.platform.components.SavedStateController;
import com.swirlds.platform.consensus.EventWindowUtils;
import com.swirlds.platform.network.protocol.ReservedSignedStatePromise;
import com.swirlds.platform.state.ConsensusStateEventHandler;
import com.swirlds.platform.state.MerkleNodeState;
import com.swirlds.platform.state.SwirldStateManager;
import com.swirlds.platform.state.nexus.SignedStateNexus;
import com.swirlds.platform.state.service.PlatformStateFacade;
import com.swirlds.platform.state.signed.ReservedSignedState;
import com.swirlds.platform.state.signed.SignedState;
import com.swirlds.platform.state.signed.SignedStateValidationData;
import com.swirlds.platform.state.signed.SignedStateValidator;
import com.swirlds.platform.state.snapshot.SignedStateFileReader;
import com.swirlds.platform.system.InitTrigger;
import com.swirlds.platform.system.Platform;
import com.swirlds.platform.system.SystemExitCode;
import com.swirlds.platform.system.SystemExitUtils;
import com.swirlds.platform.system.status.actions.ReconnectCompleteAction;
import com.swirlds.platform.wiring.PlatformCoordinator;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Semaphore;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hiero.base.crypto.Hash;
import org.hiero.consensus.model.node.NodeId;
import org.hiero.consensus.roster.RosterHistory;
import org.hiero.consensus.roster.RosterRetriever;
import org.hiero.consensus.roster.RosterUtils;

/**
 * PlatformReconnecter combined with the former ReconnectStateLoader.
 * This single class handles the reconnect loop and loading the received state.
 */
public class PlatformReconnecter implements Startable {

    private static final Logger logger = LogManager.getLogger(PlatformReconnecter.class);

    // --- Shared/Controller fields ---
    private final PlatformStateFacade platformStateFacade;
    private final ThreadManager threadManager;
    private final Roster roster;

    private final SignedStateValidator validator;
    private final Duration minTimeBetweenReconnects;
    private final MerkleCryptography merkleCryptography;
    private final Semaphore threadRunning = new Semaphore(1);

    // --- State-management / loader dependencies (from ReconnectStateLoader) ---
    private final Platform platform;
    private final PlatformContext platformContext;
    private final PlatformCoordinator platformCoordinator;
    private final SwirldStateManager swirldStateManager;
    private final SignedStateNexus latestImmutableStateNexus;
    private final SavedStateController savedStateController;
    private final ConsensusStateEventHandler<MerkleNodeState> consensusStateEventHandler;

    private final ReservedSignedStatePromise promise;
    // throttle deps
    private final NodeId selfId;
    private final ReconnectConfig reconnectConfig;
    private final Time time;
    private final Instant startupTime;
    /**
     * The number of times reconnect has failed since the last successful reconnect.
     */
    private int failedReconnectsInARow;

    public PlatformReconnecter(
            @NonNull final PlatformStateFacade platformStateFacade,
            @NonNull final ThreadManager threadManager,
            @NonNull final Roster roster,
            @NonNull final MerkleCryptography merkleCryptography,
            // loader deps
            @NonNull final Platform platform,
            @NonNull final PlatformContext platformContext,
            final PlatformCoordinator platformCoordinator,
            @NonNull final SwirldStateManager swirldStateManager,
            @NonNull final SignedStateNexus latestImmutableStateNexus,
            @NonNull final SavedStateController savedStateController,
            @NonNull final ConsensusStateEventHandler<MerkleNodeState> consensusStateEventHandler,
            final ReservedSignedStatePromise promise,
            final NodeId selfId) {
        this.platformStateFacade = Objects.requireNonNull(platformStateFacade);
        this.threadManager = Objects.requireNonNull(threadManager);
        this.roster = Objects.requireNonNull(roster);
        this.platformCoordinator = platformCoordinator;
        this.promise = promise;
        this.validator = new DefaultSignedStateValidator(platformContext, platformStateFacade);
        this.merkleCryptography = Objects.requireNonNull(merkleCryptography);
        this.reconnectConfig = platformContext.getConfiguration().getConfigData(ReconnectConfig.class);
        this.minTimeBetweenReconnects = Objects.requireNonNull(reconnectConfig).minimumTimeBetweenReconnects();
        // loader deps
        this.platform = Objects.requireNonNull(platform);
        this.platformContext = Objects.requireNonNull(platformContext);
        this.swirldStateManager = Objects.requireNonNull(swirldStateManager);
        this.latestImmutableStateNexus = Objects.requireNonNull(latestImmutableStateNexus);
        this.savedStateController = Objects.requireNonNull(savedStateController);
        this.consensusStateEventHandler = Objects.requireNonNull(consensusStateEventHandler);
        this.time = platformContext.getTime();
        this.selfId = selfId;
        this.startupTime = time.now();
    }

    /**
     * Hash the working state to prepare for reconnect
     */
    static void hashStateForReconnect(final MerkleCryptography merkleCryptography, final MerkleNodeState workingState) {
        try {
            merkleCryptography.digestTreeAsync(workingState.getRoot()).get();
        } catch (final ExecutionException e) {
            logger.error(
                    EXCEPTION.getMarker(),
                    () -> new ReconnectFailurePayload(
                                    "Error encountered while hashing state for reconnect",
                                    ReconnectFailurePayload.CauseOfFailure.ERROR)
                            .toString(),
                    e);
            throw new StateSyncException(e);
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            logger.error(
                    EXCEPTION.getMarker(),
                    () -> new ReconnectFailurePayload(
                                    "Interrupted while attempting to hash state",
                                    ReconnectFailurePayload.CauseOfFailure.ERROR)
                            .toString(),
                    e);
        }
    }

    /** Start the controller thread (idempotent). */
    public void start() {
        if (!threadRunning.tryAcquire()) {
            logger.error(EXCEPTION.getMarker(), "Attempting to start reconnect controller while it's already running");
            return;
        }
        logger.info(LogMarker.RECONNECT.getMarker(), "Starting ReconnectController");
        new ThreadConfiguration(threadManager)
                .setComponent("reconnect")
                .setThreadName("reconnect-controller")
                .setRunnable(() -> {
                    try {
                        while (!executeReconnect()) {
                            logger.error(EXCEPTION.getMarker(), "Reconnect failed, retrying");
                            Thread.sleep(minTimeBetweenReconnects.toMillis());
                        }
                    } catch (final RuntimeException | InterruptedException e) {
                        logger.error(EXCEPTION.getMarker(), "Unexpected error occurred while reconnecting", e);
                        SystemExitUtils.exitSystem(SystemExitCode.RECONNECT_FAILURE);
                    } finally {
                        threadRunning.release();
                    }
                })
                .build(true);
    }

    /** One reconnect attempt; returns true on success. */
    public boolean executeReconnect() throws InterruptedException {
        exitIfReconnectIsDisabled();
        final MerkleNodeState currentState = swirldStateManager.getConsensusState();
        prepareForReconnect(currentState);

        logger.info(RECONNECT.getMarker(), "waiting for reconnect connection");
        try {
            logger.info(RECONNECT.getMarker(), "acquired reconnect connection");
            try (final ReservedSignedState reservedState = promise.get()) {
                validator.validate(
                        reservedState.get(),
                        roster,
                        new SignedStateValidationData(currentState, roster, platformStateFacade));

                SignedStateFileReader.registerServiceStates(reservedState.get());
                successfulReconnect();

                if (!loadSignedState(reservedState.get())) {
                    handleFailedReconnect();
                    return false;
                }
            }
        } catch (final RuntimeException e) {
            handleFailedReconnect();
            logger.info(RECONNECT.getMarker(), "receiving signed state failed", e);
            return false;
        }
        platformCoordinator.resumeGossip();
        return true;
    }

    /** Pre-reconnect housekeeping. */
    public void prepareForReconnect(final MerkleNodeState currentState) {
        logger.info(RECONNECT.getMarker(), "Preparing for reconnect, stopping gossip");
        platformCoordinator.pauseGossip();
        logger.info(RECONNECT.getMarker(), "Preparing for reconnect, start clearing queues");
        platformCoordinator.clear();
        logger.info(RECONNECT.getMarker(), "Queues have been cleared");
        hashStateForReconnect(merkleCryptography, currentState);
    }

    /**
     * Load the received signed state into the platform (inline former ReconnectStateLoader#loadReconnectState).
     */
    public boolean loadSignedState(@NonNull final SignedState signedState) {
        try {
            logger.info(LogMarker.STATE_HASH.getMarker(), "RECONNECT: loadReconnectState: reloading state");
            logger.debug(RECONNECT.getMarker(), "`loadReconnectState` : reloading state");

            // keep an ISS-detector view of the state
            platformCoordinator.overrideIssDetectorState(signedState.reserve("reconnect state to issDetector"));

            // Ensure initial state in the copy chain is initialized *before* further processing
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

            // Verify platform roster matches state roster for the specific round
            final long round = platformStateFacade.roundOf(state);
            final Roster stateRoster = RosterRetriever.retrieveActive(state, round);
            if (!roster.equals(stateRoster)) {
                throw new IllegalStateException("Current roster and state-based roster do not contain the same nodes "
                        + " (currentRoster=" + Roster.JSON.toJSON(roster) + ") (stateRoster="
                        + Roster.JSON.toJSON(stateRoster) + ")");
            }

            // Load state into runtime holders
            swirldStateManager.loadFromSignedState(signedState);

            // Transition to RECONNECT_COMPLETE before persistence kicks in
            platformCoordinator.submitStatusAction(new ReconnectCompleteAction(signedState.getRound()));

            latestImmutableStateNexus.setState(signedState.reserve("set latest immutable to reconnect state"));
            savedStateController.reconnectStateReceived(
                    signedState.reserve("savedStateController.reconnectStateReceived"));

            // Hash logging & signature collection / persistence pipeline
            platformCoordinator.sendStateToHashLogger(signedState);
            platformCoordinator.putSignatureCollectorState(
                    signedState.reserve("loading reconnect state into sig collector"));

            // Consensus snapshot + roster history + event window updates
            final ConsensusSnapshot consensusSnapshot =
                    Objects.requireNonNull(platformStateFacade.consensusSnapshotOf(state));
            platformCoordinator.consensusSnapshotOverride(consensusSnapshot);

            final RosterHistory rosterHistory = RosterUtils.createRosterHistory(state);

            platformCoordinator.injectRosterHistory(rosterHistory);

            platformCoordinator.updateEventWindow(
                    EventWindowUtils.createEventWindow(consensusSnapshot, platformContext.getConfiguration()));

            // Running hash / PCES writer updates
            final RunningEventHashOverride runningEventHashOverride =
                    new RunningEventHashOverride(platformStateFacade.legacyRunningEventHashOf(state), true);
            platformCoordinator.updateRunningHash(runningEventHashOverride);
            platformCoordinator.registerPcesDiscontinuity(signedState.getRound());

            // Notify app-level listeners
            platformCoordinator.sendReconnectCompleteNotification(signedState);
            return true;
        } catch (final RuntimeException e) {
            logger.debug(RECONNECT.getMarker(), "`loadReconnectState` : FAILED, reason: {}", e.getMessage());
            platformCoordinator.clear();
            logger.error(
                    EXCEPTION.getMarker(),
                    () -> new ReconnectLoadFailurePayload("Error while loading a received SignedState!").toString(),
                    e);
            logger.debug(
                    RECONNECT.getMarker(),
                    "`reloadState` : reloading state, finished, failed, returning `false`: Restart the reconnection process");
            return false;
        }
    }

    @NonNull
    private Duration getTimeSinceStartup() {
        return Duration.between(startupTime, time.now());
    }

    /**
     * Notifies the throttle that a successful reconnect occurred
     */
    public void successfulReconnect() {
        failedReconnectsInARow = 0;
    }

    /**
     * Notifies the throttle that a reconnect failed
     */
    public void handleFailedReconnect() {
        failedReconnectsInARow++;
        killNodeIfThresholdMet();
    }

    private void killNodeIfThresholdMet() {
        if (failedReconnectsInARow >= reconnectConfig.maximumReconnectFailuresBeforeShutdown()) {
            logger.error(EXCEPTION.getMarker(), "Too many reconnect failures in a row, killing node");
            SystemExitUtils.exitSystem(SystemExitCode.RECONNECT_FAILURE);
        }
    }

    /**
     * Check if a reconnect is currently allowed. If not then kill the node.
     */
    public void exitIfReconnectIsDisabled() {
        if (!reconnectConfig.active()) {
            logger.warn(STARTUP.getMarker(), () -> new UnableToReconnectPayload(
                            "Node has fallen behind, reconnect is disabled, will die", selfId.id())
                    .toString());
            SystemExitUtils.exitSystem(SystemExitCode.BEHIND_RECONNECT_DISABLED);
        }

        if (reconnectConfig.reconnectWindowSeconds() >= 0
                && reconnectConfig.reconnectWindowSeconds()
                        < getTimeSinceStartup().toSeconds()) {
            logger.warn(STARTUP.getMarker(), () -> new UnableToReconnectPayload(
                            "Node has fallen behind, reconnect is disabled outside of time window, will die",
                            selfId.id())
                    .toString());
            SystemExitUtils.exitSystem(SystemExitCode.BEHIND_RECONNECT_DISABLED);
        }
    }
}
