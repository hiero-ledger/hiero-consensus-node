// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.reconnect;

import static com.swirlds.logging.legacy.LogMarker.EXCEPTION;
import static com.swirlds.logging.legacy.LogMarker.RECONNECT;
import static com.swirlds.logging.legacy.LogMarker.STARTUP;

import com.hedera.hapi.node.base.SemanticVersion;
import com.hedera.hapi.node.state.roster.Roster;
import com.swirlds.base.time.Time;
import com.swirlds.common.context.PlatformContext;
import com.swirlds.common.merkle.crypto.MerkleCryptography;
import com.swirlds.common.merkle.synchronization.config.ReconnectConfig;
import com.swirlds.common.threading.framework.config.ThreadConfiguration;
import com.swirlds.common.threading.manager.ThreadManager;
import com.swirlds.logging.legacy.LogMarker;
import com.swirlds.logging.legacy.payload.ReconnectFailurePayload;
import com.swirlds.logging.legacy.payload.UnableToReconnectPayload;
import com.swirlds.platform.components.SavedStateController;
import com.swirlds.platform.network.protocol.ReservedSignedStatePromise;
import com.swirlds.platform.state.ConsensusStateEventHandler;
import com.swirlds.platform.state.SwirldStateManager;
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
import com.swirlds.platform.wiring.PlatformCoordinator;
import com.swirlds.state.MerkleNodeState;
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
import org.hiero.consensus.roster.RosterRetriever;

/**
 * PlatformReconnecter combined with the former ReconnectStateLoader.
 * This single class handles the reconnect loop and loading the received state.
 */
public class PlatformReconnecter {

    private static final Logger logger = LogManager.getLogger(PlatformReconnecter.class);

    // --- Shared/Controller fields ---
    private final PlatformStateFacade platformStateFacade;
    private final ThreadManager threadManager;
    private final Roster roster;

    private final SignedStateValidator validator;
    private final MerkleCryptography merkleCryptography;
    private final Semaphore threadRunning = new Semaphore(1);

    // --- State-management / loader dependencies (from ReconnectStateLoader) ---
    private final Platform platform;
    private final PlatformContext platformContext;
    private final PlatformCoordinator platformCoordinator;
    private final SwirldStateManager swirldStateManager;
    private final SavedStateController savedStateController;
    private final ConsensusStateEventHandler<MerkleNodeState> consensusStateEventHandler;

    private final ReservedSignedStatePromise peerReservedSignedStatePromise;
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
            @NonNull final Platform platform,
            @NonNull final PlatformContext platformContext,
            @NonNull final PlatformCoordinator platformCoordinator,
            @NonNull final SwirldStateManager swirldStateManager,
            @NonNull final SavedStateController savedStateController,
            @NonNull final ConsensusStateEventHandler<MerkleNodeState> consensusStateEventHandler,
            @NonNull final ReservedSignedStatePromise peerReservedSignedStatePromise,
            @NonNull final NodeId selfId) {
        this.platformStateFacade = Objects.requireNonNull(platformStateFacade);
        this.threadManager = Objects.requireNonNull(threadManager);
        this.roster = Objects.requireNonNull(roster);
        this.platformCoordinator = Objects.requireNonNull(platformCoordinator);
        this.peerReservedSignedStatePromise = Objects.requireNonNull(peerReservedSignedStatePromise);
        this.validator = new DefaultSignedStateValidator(platformContext, platformStateFacade);
        this.merkleCryptography = Objects.requireNonNull(merkleCryptography);
        this.reconnectConfig = platformContext.getConfiguration().getConfigData(ReconnectConfig.class);
        // loader deps
        this.platform = Objects.requireNonNull(platform);
        this.platformContext = Objects.requireNonNull(platformContext);
        this.swirldStateManager = Objects.requireNonNull(swirldStateManager);
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
    public void start(@NonNull final Runnable onSuccess, @NonNull final Runnable onEachFailure) {
        if (!threadRunning.tryAcquire()) {
            // logger.error(EXCEPTION.getMarker(), "Attempting to start reconnect controller while it's already
            // running");
            logger.info(RECONNECT.getMarker(), "Attempting to start reconnect controller while it's already running");
            return;
        }
        logger.info(LogMarker.RECONNECT.getMarker(), "Starting ReconnectController");
        new ThreadConfiguration(threadManager)
                .setComponent("reconnect")
                .setThreadName("reconnect-controller")
                .setRunnable(() -> {
                    try {
                        while (!doReconnect()) {
                            failedReconnectsInARow++;
                            killNodeIfThresholdMet();
                            logger.error(EXCEPTION.getMarker(), "Reconnect failed, retrying");
                            Thread.sleep(reconnectConfig
                                    .minimumTimeBetweenReconnects()
                                    .toMillis());
                        }
                        failedReconnectsInARow = 0;
                        onSuccess.run();
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
    public boolean doReconnect() throws InterruptedException {
        exitIfReconnectIsDisabled();
        final MerkleNodeState currentState = swirldStateManager.getConsensusState();
        logger.info(RECONNECT.getMarker(), "Preparing for reconnect, stopping gossip");
        platformCoordinator.pauseGossip();
        logger.info(RECONNECT.getMarker(), "Preparing for reconnect, start clearing queues");
        platformCoordinator.clear();
        logger.info(RECONNECT.getMarker(), "Queues have been cleared");
        hashStateForReconnect(merkleCryptography, currentState);

        logger.info(RECONNECT.getMarker(), "Waiting for a state to be obtained from a peer");
        try {
            logger.info(RECONNECT.getMarker(), "A state was obtained from a peer");
            try (final ReservedSignedState reservedState = peerReservedSignedStatePromise.await()) {
                validator.validate(
                        reservedState.get(),
                        roster,
                        new SignedStateValidationData(currentState, roster, platformStateFacade));

                SignedStateFileReader.registerServiceStates(reservedState.get());

                if (!loadReconnectState(reservedState.get())) {
                    return false;
                }
            }
        } catch (final RuntimeException e) {
            logger.info(RECONNECT.getMarker(), "Reconnect failed with the following exception", e);
            return false;
        }
        platformCoordinator.resumeGossip();
        return true;
    }

    /**
     * Load the received signed state into the platform (inline former ReconnectStateLoader#loadReconnectState).
     */
    public boolean loadReconnectState(@NonNull final SignedState signedState) {
        // the state was received, so now we load its data into different objects
        // logger.info(LogMarker.STATE_HASH.getMarker(), "RECONNECT: loadReconnectState: reloading state");
        // logger.debug(RECONNECT.getMarker(), "`loadReconnectState` : reloading state");
        try {
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
            platformCoordinator.loadReconnectState(platformContext.getConfiguration(), signedState);
            savedStateController.reconnectStateReceived(
                    signedState.reserve("savedStateController.reconnectStateReceived"));
            return true;
        } catch (final RuntimeException e) {
            logger.debug(RECONNECT.getMarker(), "`loadReconnectState` : FAILED, reason: {}", e.getMessage());
            // if the loading fails for whatever reason, we clear all data again in case some of it has been loaded
            platformCoordinator.clear();
            return false;
        }
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
                        < Duration.between(startupTime, time.now()).toSeconds()) {
            logger.warn(STARTUP.getMarker(), () -> new UnableToReconnectPayload(
                            "Node has fallen behind, reconnect is disabled outside of time window, will die",
                            selfId.id())
                    .toString());
            SystemExitUtils.exitSystem(SystemExitCode.BEHIND_RECONNECT_DISABLED);
        }
    }
}
