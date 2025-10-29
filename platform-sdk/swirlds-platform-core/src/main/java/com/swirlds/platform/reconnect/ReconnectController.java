// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.reconnect;

import static com.swirlds.logging.legacy.LogMarker.EXCEPTION;
import static com.swirlds.logging.legacy.LogMarker.RECONNECT;
import static com.swirlds.logging.legacy.LogMarker.STATE_HASH;
import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.base.SemanticVersion;
import com.hedera.hapi.node.state.roster.Roster;
import com.swirlds.base.time.Time;
import com.swirlds.common.context.PlatformContext;
import com.swirlds.common.merkle.crypto.MerkleCryptography;
import com.swirlds.common.merkle.synchronization.config.ReconnectConfig;
import com.swirlds.logging.legacy.payload.ReconnectFailurePayload;
import com.swirlds.logging.legacy.payload.ReconnectFailurePayload.CauseOfFailure;
import com.swirlds.platform.components.SavedStateController;
import com.swirlds.platform.network.protocol.ReservedSignedStatePromise;
import com.swirlds.platform.network.protocol.ReservedSignedStatePromise.ReservedStateOrExceptionResource;
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
import com.swirlds.platform.system.status.actions.FallenBehindAction;
import com.swirlds.platform.system.status.actions.ReconnectCompleteAction;
import com.swirlds.platform.wiring.PlatformCoordinator;
import com.swirlds.state.MerkleNodeState;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hiero.base.crypto.Hash;
import org.hiero.consensus.model.node.NodeId;
import org.hiero.consensus.roster.RosterRetriever;

/**
 * Orchestrates the reconnect process when a node falls behind.
 *
 * <p>Once started the controller runs in a continuous loop, waiting for the node to fall behind, then attempting
 * to reconnect until successful or until configured thresholds are exceeded. Each reconnect attempt involves
 * preparing the current state, obtaining a new state from a peer, validating it, and loading it into
 * the platform components.
 *
 * <p>This class blocks the caller until the fall behind condition is detected.
 * Callers are responsible to call this in a separated thread.
 *
 * @see FallenBehindMonitor
 * @see ReservedSignedStatePromise
 * @see PlatformCoordinator
 */
public class ReconnectController implements Runnable {

    private static final Logger logger = LogManager.getLogger(ReconnectController.class);

    private final PlatformStateFacade platformStateFacade;
    private final Roster roster;
    private final SignedStateValidator signedStateValidator;
    private final MerkleCryptography merkleCryptography;
    private final Platform platform;
    private final PlatformContext platformContext;
    private final PlatformCoordinator platformCoordinator;
    private final SwirldStateManager swirldStateManager;
    private final SavedStateController savedStateController;
    private final ConsensusStateEventHandler<MerkleNodeState> consensusStateEventHandler;
    private final ReservedSignedStatePromise peerReservedSignedStatePromise;
    private final NodeId selfId;
    private final ReconnectConfig reconnectConfig;
    private final Time time;
    private final Instant startupTime;
    private final FallenBehindMonitor fallenBehindMonitor;
    private final AtomicBoolean run = new AtomicBoolean(true);

    public ReconnectController(
            @NonNull final PlatformStateFacade platformStateFacade,
            @NonNull final Roster roster,
            @NonNull final MerkleCryptography merkleCryptography,
            @NonNull final Platform platform,
            @NonNull final PlatformContext platformContext,
            @NonNull final PlatformCoordinator platformCoordinator,
            @NonNull final SwirldStateManager swirldStateManager,
            @NonNull final SavedStateController savedStateController,
            @NonNull final ConsensusStateEventHandler<MerkleNodeState> consensusStateEventHandler,
            @NonNull final ReservedSignedStatePromise peerReservedSignedStatePromise,
            @NonNull final NodeId selfId,
            @NonNull final FallenBehindMonitor fallenBehindMonitor,
            @NonNull final SignedStateValidator signedStateValidator) {
        this.platformStateFacade = requireNonNull(platformStateFacade);
        this.roster = requireNonNull(roster);
        this.platformCoordinator = requireNonNull(platformCoordinator);
        this.peerReservedSignedStatePromise = requireNonNull(peerReservedSignedStatePromise);
        this.fallenBehindMonitor = requireNonNull(fallenBehindMonitor);
        this.signedStateValidator = requireNonNull(signedStateValidator);
        this.merkleCryptography = requireNonNull(merkleCryptography);
        this.reconnectConfig = platformContext.getConfiguration().getConfigData(ReconnectConfig.class);
        this.platform = requireNonNull(platform);
        this.platformContext = requireNonNull(platformContext);
        this.swirldStateManager = requireNonNull(swirldStateManager);
        this.savedStateController = requireNonNull(savedStateController);
        this.consensusStateEventHandler = requireNonNull(consensusStateEventHandler);
        this.time = platformContext.getTime();
        this.selfId = selfId;
        this.startupTime = time.now();
    }

    /**
     * Hash the working state to prepare for reconnect
     */
    private static void hashStateForReconnect(
            final @NonNull MerkleCryptography merkleCryptography, final @NonNull MerkleNodeState workingState)
            throws InterruptedException {
        try {
            merkleCryptography.digestTreeAsync(workingState.getRoot()).get();
        } catch (final ExecutionException e) {
            throw new IllegalStateException("Error encountered while hashing state for reconnect", e);
        }
    }

    /**
     * Initiates graceful shutdown of the controller.
     * Does not guarantee stopping if the thread where the controller is running is blocked
     */
    public void stopReconnectLoop() {
        run.set(false);
    }

    /**
     * Once started, it manages the lifecycle of reconnection:
     * <ul>
     *   <li>Waits a notification of a fallen-behind condition via {@link FallenBehindMonitor}</li>
     *   <li>Coordinating platform state transitions during reconnect (BEHIND -> RECONNECT COMPLETE -> CHECKING)</li>
     *   <li>Operates the platform to prepare for reconnect ( pausing gossip, flush and clearing queues)</li>
     *   <li>Acquiring and validating signed states from peer nodes</li>
     *   <li>Loading the validated state into the platform</li>
     *   <li>Managing retry logic with configurable limits</li>
     * </ul>
     */
    @Override
    public void run() {
        logger.info(RECONNECT.getMarker(), "Starting the ReconnectController");
        try {
            while (run.get()) {
                fallenBehindMonitor.awaitFallenBehind(); // Block until the monitor notifies the node is behind
                exitIf();
                platformCoordinator.submitStatusAction(new FallenBehindAction());
                logger.info(RECONNECT.getMarker(), "Preparing for reconnect, stopping gossip");
                platformCoordinator.pauseGossip();
                logger.info(RECONNECT.getMarker(), "Preparing for reconnect, start clearing queues");
                platformCoordinator.clear();
                logger.info(RECONNECT.getMarker(), "Queues have been cleared");

                final MerkleNodeState currentState = swirldStateManager.getConsensusState();
                hashStateForReconnect(merkleCryptography, currentState);
                int failedReconnectsInARow = 0;
                do {
                    final AttemptReconnectResult result = attemptReconnect(currentState);
                    if (result.success()) {
                        // reset the monitor to the initial state
                        fallenBehindMonitor.clear();
                        logger.info(RECONNECT.getMarker(), "Reconnect almost done resuming gossip");
                        platformCoordinator.resumeGossip();
                        break;
                    }
                    platformCoordinator.clear();
                    exitIfMaxRetriesOrWait(++failedReconnectsInARow, result.throwable());
                } while (run.get());
            }
        } catch (final RuntimeException e) {
            logger.error(
                    RECONNECT.getMarker(),
                    () -> new ReconnectFailurePayload(
                            "Unexpected error occurred while reconnecting", CauseOfFailure.ERROR),
                    e);
            // in the future we might want to consider a graceful shutdown hook instead of exitSystem
            SystemExitUtils.exitSystem(SystemExitCode.RECONNECT_FAILURE);
        } catch (final InterruptedException e) {
            if (run.get()) {
                logger.error(
                        RECONNECT.getMarker(),
                        () -> new ReconnectFailurePayload("Thread was interrupted unexpectedly", CauseOfFailure.ERROR),
                        e);
                Thread.currentThread().interrupt();
                // in the future we might want to consider a graceful shutdown hook instead of exitSystem
                SystemExitUtils.exitSystem(SystemExitCode.RECONNECT_FAILURE);
            }
        }
    }

    /** One reconnect attempt; returns true on success. */
    private AttemptReconnectResult attemptReconnect(final MerkleNodeState currentState) throws InterruptedException {
        // This is a direct connection with the protocols at Gossip component.
        // reservedStateResource is a blocking data structure that will provide a signed state from one of the peers
        // At the same time this code is evaluated, the ReconnectStateProtocol is being executed
        // which will select a peer to receive a state (only one from all the ones that reported we are behind)
        // Once the transferred is complete this peerReservedSignedStatePromise will be notified and this code will be
        // unblocked
        logger.info(RECONNECT.getMarker(), "Waiting for a state to be obtained from a peer");
        try (final ReservedStateOrExceptionResource reservedStateResource =
                        requireNonNull(peerReservedSignedStatePromise.awaitResolution());
                final ReservedSignedState reservedState = reservedStateResource.getResource()) {
            if (reservedState == null && reservedStateResource.getException() != null) {
                return AttemptReconnectResult.error(reservedStateResource.getException());
            }

            logger.info(RECONNECT.getMarker(), "A state was obtained from a peer");
            signedStateValidator.validate(
                    reservedState.get(),
                    roster,
                    new SignedStateValidationData(currentState, roster, platformStateFacade));
            logger.info(RECONNECT.getMarker(), "The state obtained from a peer was validated");

            SignedStateFileReader.registerServiceStates(reservedState.get());
            loadState(reservedState.get());
            // Notify any listeners that the reconnect has been completed
            platformCoordinator.sendReconnectCompleteNotification(reservedState.get());
            return AttemptReconnectResult.ok();
        } catch (final RuntimeException e) {
            return AttemptReconnectResult.error(e);
        }
    }

    /**
     * Loads the received signed state into the platform
     */
    private void loadState(@NonNull final SignedState signedState) {
        // the state was received, so now we load its data into different objects
        logger.info(STATE_HASH.getMarker(), "RECONNECT: loadState: reloading state");
        logger.debug(RECONNECT.getMarker(), "`loadState` : reloading state");
        final Hash reconnectHash = signedState.getState().getHash();
        final MerkleNodeState state = signedState.getState();
        final SemanticVersion creationSoftwareVersion = platformStateFacade.creationSoftwareVersionOf(state);
        // It's important to call init() before loading the signed state. The loading process makes copies
        // of the state, and we want to be sure that the first state in the chain of copies has been initialized.
        signedState.init(platformContext);
        consensusStateEventHandler.onStateInitialized(state, platform, InitTrigger.RECONNECT, creationSoftwareVersion);

        if (!Objects.equals(signedState.getState().getHash(), reconnectHash)) {
            throw new IllegalStateException(
                    "State hash is not permitted to change during a reconnect init() call. Previous hash was "
                            + reconnectHash + ", new hash is "
                            + signedState.getState().getHash());
        }

        // Before attempting to load the state, verify that the platform roster matches the state roster.
        final long round = signedState.getRound();
        final Roster stateRoster = RosterRetriever.retrieveActive(state, round);
        if (!roster.equals(stateRoster)) {
            throw new IllegalStateException("Current roster and state-based roster do not contain the same nodes "
                    + " (currentRoster=" + Roster.JSON.toJSON(roster) + ") (stateRoster="
                    + Roster.JSON.toJSON(stateRoster) + ")");
        }

        swirldStateManager.loadFromSignedState(signedState);
        // kick off transition to RECONNECT_COMPLETE before beginning to save the reconnect state to disk
        // this guarantees that the platform status will be RECONNECT_COMPLETE before the state is saved
        platformCoordinator.submitStatusAction(new ReconnectCompleteAction(signedState.getRound()));
        savedStateController.reconnectStateReceived(signedState.reserve("savedStateController.reconnectStateReceived"));
        platformCoordinator.loadReconnectState(platformContext.getConfiguration(), signedState);
    }

    /**
     * Kills the node if we reached the max reconnect retries, otherwise wait until minimumTimeBetweenReconnects
     */
    private void exitIfMaxRetriesOrWait(final int failedReconnectsInARow, final @Nullable Throwable throwable)
            throws InterruptedException {
        if (failedReconnectsInARow >= reconnectConfig.maximumReconnectFailuresBeforeShutdown()) {
            logger.error(
                    EXCEPTION.getMarker(),
                    () -> new ReconnectFailurePayload(
                            "Too many reconnect failures in a row (%s), killing node"
                                    .formatted(reconnectConfig.maximumReconnectFailuresBeforeShutdown()),
                            CauseOfFailure.ERROR),
                    throwable);
            SystemExitUtils.exitSystem(SystemExitCode.RECONNECT_FAILURE);
        } else {
            logger.warn(
                    RECONNECT.getMarker(),
                    "Reconnect retry ({}/{}) failed with error",
                    failedReconnectsInARow,
                    reconnectConfig.maximumReconnectFailuresBeforeShutdown());
            if (throwable != null) {
                logger.warn(
                        EXCEPTION.getMarker(),
                        () -> new ReconnectFailurePayload(
                                "Unexpected exception during reconnect", CauseOfFailure.ERROR),
                        throwable);
            }
            logger.info(
                    RECONNECT.getMarker(),
                    "Reconnect will wait for {} until next retry",
                    reconnectConfig.minimumTimeBetweenReconnects());
            Thread.sleep(reconnectConfig.minimumTimeBetweenReconnects().toMillis());
        }
    }

    /**
     * Kills the node if the reconnect time window has elapsed.
     */
    private void exitIf() {
        if (!reconnectConfig.active()) {
            logger.error(
                    RECONNECT.getMarker(), "Node {} has fallen behind, reconnect is disabled, will die", selfId.id());
            SystemExitUtils.exitSystem(SystemExitCode.BEHIND_RECONNECT_DISABLED);
        } else if (reconnectConfig.reconnectWindowSeconds() >= 0
                && reconnectConfig.reconnectWindowSeconds()
                        < Duration.between(startupTime, time.now()).toSeconds()) {
            logger.error(
                    RECONNECT.getMarker(),
                    "Node {} has fallen behind, reconnect is disabled outside of time window, will die",
                    selfId.id());
            SystemExitUtils.exitSystem(SystemExitCode.RECONNECT_FAILURE);
        }
    }

    private record AttemptReconnectResult(boolean success, @Nullable Throwable throwable) {
        static AttemptReconnectResult ok() {
            return new AttemptReconnectResult(true, null);
        }

        static AttemptReconnectResult error(@NonNull final Throwable error) {
            return new AttemptReconnectResult(false, error);
        }
    }
}
