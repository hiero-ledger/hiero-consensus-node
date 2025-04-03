// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.reconnect;

import static com.swirlds.logging.legacy.LogMarker.EXCEPTION;
import static com.swirlds.logging.legacy.LogMarker.RECONNECT;

import com.swirlds.common.merkle.crypto.MerkleCryptography;
import com.swirlds.logging.legacy.payload.ReconnectFinishPayload;
import com.swirlds.logging.legacy.payload.ReconnectLoadFailurePayload;
import com.swirlds.logging.legacy.payload.ReconnectStartPayload;
import com.swirlds.platform.config.StateConfig;
import com.swirlds.platform.network.Connection;
import com.swirlds.platform.state.MerkleNodeState;
import com.swirlds.platform.state.service.PlatformStateFacade;
import com.swirlds.platform.state.signed.ReservedSignedState;
import com.swirlds.platform.state.signed.SignedState;
import com.swirlds.platform.state.signed.SignedStateValidator;
import com.swirlds.platform.state.snapshot.SignedStateFileReader;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hiero.consensus.utility.Clearable;

/**
 * A single point responsible for executing the 3 phases of reconnect:
 * <ol>
 * <li>Preparation - stop gossip and clear all data currently being processed</li>
 * <li>Receive state - execute the reconnect protocol with a neighbor to receive a signed state</li>
 * <li>Load data - load the data from a signed state into various parts of the system</li>
 * </ol>
 */
public class ReconnectHelper {
    private static final Logger logger = LogManager.getLogger(ReconnectHelper.class);

    /** pause gossip for reconnect */
    private final Runnable pauseGossip;
    /** clears all data that is no longer needed since we fell behind */
    private final Clearable clearAll;
    /** supplier of the initial signed state against which to perform a delta based reconnect */
    private final Supplier<MerkleNodeState> workingStateSupplier;
    /** provides the latest signed state round for which we have a supermajority of signatures */
    private final LongSupplier lastCompleteRoundSupplier;
    /** throttles reconnect learner attempts */
    private final ReconnectLearnerThrottle reconnectLearnerThrottle;
    /** performs the third phase, loading signed state data */
    private final Consumer<SignedState> loadSignedState;
    /** Creates instances of {@link ReconnectLearner} to execute the second phase, receiving a signed state */
    private final ReconnectLearnerFactory reconnectLearnerFactory;
    /** configuration for the state from the platform */
    private final StateConfig stateConfig;

    /** provides access to the platform state */
    private final PlatformStateFacade platformStateFacade;

    /** Merkle cryptography */
    private final MerkleCryptography merkleCryptography;

    public ReconnectHelper(
            @NonNull final Runnable pauseGossip,
            @NonNull final Clearable clearAll,
            @NonNull final Supplier<MerkleNodeState> workingStateSupplier,
            @NonNull final LongSupplier lastCompleteRoundSupplier,
            @NonNull final ReconnectLearnerThrottle reconnectLearnerThrottle,
            @NonNull final Consumer<SignedState> loadSignedState,
            @NonNull final ReconnectLearnerFactory reconnectLearnerFactory,
            @NonNull final StateConfig stateConfig,
            @NonNull final PlatformStateFacade platformStateFacade,
            @NonNull final MerkleCryptography merkleCryptography) {
        this.pauseGossip = pauseGossip;
        this.clearAll = clearAll;
        this.workingStateSupplier = workingStateSupplier;
        this.lastCompleteRoundSupplier = lastCompleteRoundSupplier;
        this.reconnectLearnerThrottle = reconnectLearnerThrottle;
        this.loadSignedState = loadSignedState;
        this.reconnectLearnerFactory = reconnectLearnerFactory;
        this.stateConfig = stateConfig;
        this.platformStateFacade = platformStateFacade;
        this.merkleCryptography = merkleCryptography;
    }

    /**
     * Performs necessary operations before a reconnect such as stopping threads, clearing queues, etc.
     */
    public void prepareForReconnect() {
        reconnectLearnerThrottle.exitIfReconnectIsDisabled();
        logger.info(RECONNECT.getMarker(), "Preparing for reconnect, stopping gossip");
        pauseGossip.run();
        logger.info(RECONNECT.getMarker(), "Preparing for reconnect, start clearing queues");
        clearAll.clear();
        logger.info(RECONNECT.getMarker(), "Queues have been cleared");
        // Hash the state if it has not yet been hashed
        ReconnectUtils.hashStateForReconnect(merkleCryptography, workingStateSupplier.get());
    }

    /**
     * Attempts to receive a new signed state by reconnecting with the specified neighbor.
     *
     * @param conn
     * 		the connection to use for the reconnect attempt
     * @return the signed state received from the neighbor
     * @throws ReconnectException
     * 		if any error occurs during the reconnect attempt
     */
    public ReservedSignedState receiveSignedState(final Connection conn, final SignedStateValidator validator)
            throws ReconnectException {
        try {
            final ReservedSignedState reservedState = reconnectLearner(conn, validator);
            reconnectLearnerThrottle.successfulReconnect();
            return reservedState;
        } catch (final RuntimeException e) {
            reconnectLearnerThrottle.handleFailedReconnect(conn, e);
            throw e;
        }
    }

    private ReservedSignedState reconnectLearner(final Connection conn, final SignedStateValidator validator)
            throws ReconnectException {

        logger.info(RECONNECT.getMarker(), () -> new ReconnectStartPayload(
                        "Starting reconnect in role of the receiver.",
                        true,
                        conn.getSelfId().id(),
                        conn.getOtherId().id(),
                        lastCompleteRoundSupplier.getAsLong())
                .toString());

        final ReconnectLearner reconnect = reconnectLearnerFactory.create(conn, workingStateSupplier.get());

        final ReservedSignedState reservedState = reconnect.execute(validator);

        final long lastRoundReceived = reservedState.get().getRound();
        SignedStateFileReader.registerServiceStates(reservedState.get());

        logger.info(RECONNECT.getMarker(), () -> new ReconnectFinishPayload(
                        "Finished reconnect in the role of the receiver.",
                        true,
                        conn.getSelfId().id(),
                        conn.getOtherId().id(),
                        lastRoundReceived)
                .toString());

        logger.info(
                RECONNECT.getMarker(),
                """
                Information for state received during reconnect:
                {}""",
                () -> platformStateFacade.getInfoString(reservedState.get().getState(), stateConfig.debugHashDepth()));

        return reservedState;
    }

    /**
     * Used to load the state received from the sender.
     *
     * @param signedState
     * 		the signed state that was received from the sender
     * @return true if the state was successfully loaded; otherwise false
     */
    public boolean loadSignedState(final SignedState signedState) {
        try {
            loadSignedState.accept(signedState);
        } catch (final RuntimeException e) {
            logger.error(
                    EXCEPTION.getMarker(),
                    () -> new ReconnectLoadFailurePayload("Error while loading a received SignedState!").toString(),
                    e);
            // this means we need to start the reconnect process from the beginning
            logger.debug(
                    RECONNECT.getMarker(),
                    "`reloadState` : reloading state, finished, failed, returning `false`: Restart the "
                            + "reconnection process");
            reconnectLearnerThrottle.handleFailedReconnect(null, e);
            return false;
        }
        return true;
    }
}
