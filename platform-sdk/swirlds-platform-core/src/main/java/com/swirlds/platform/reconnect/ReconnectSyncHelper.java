// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.reconnect;

import static com.swirlds.logging.legacy.LogMarker.EXCEPTION;
import static com.swirlds.logging.legacy.LogMarker.RECONNECT;

import com.swirlds.common.threading.BlockingResourceProvider;
import com.swirlds.common.threading.locks.locked.LockedResource;
import com.swirlds.logging.legacy.payload.ReconnectFailurePayload;
import com.swirlds.logging.legacy.payload.ReconnectFinishPayload;
import com.swirlds.logging.legacy.payload.ReconnectStartPayload;
import com.swirlds.platform.Utilities;
import com.swirlds.platform.config.StateConfig;
import com.swirlds.platform.network.Connection;
import com.swirlds.platform.network.NetworkUtils;
import com.swirlds.platform.state.MerkleNodeState;
import com.swirlds.platform.state.service.PlatformStateFacade;
import com.swirlds.platform.state.signed.ReservedSignedState;
import com.swirlds.platform.state.signed.SignedStateValidator;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Objects;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public class ReconnectSyncHelper implements ReconnectNetworkHelper {

    private static final Logger logger = LogManager.getLogger(ReconnectSyncHelper.class);

    /** supplier of the initial signed state against which to perform a delta based reconnect */
    private final Supplier<MerkleNodeState> workingStateSupplier;
    /** provides the latest signed state round for which we have a supermajority of signatures */
    private final LongSupplier lastCompleteRoundSupplier;
    /** Creates instances of {@link ReconnectLearner} to execute the second phase, receiving a signed state */
    private final ReconnectLearnerFactory reconnectLearnerFactory;
    /** configuration for the state from the platform */
    private final StateConfig stateConfig;
    /** provides access to the platform state */
    private final PlatformStateFacade platformStateFacade;

    private final BlockingResourceProvider<Connection> connectionProvider;

    /**
     * @param workingStateSupplier      supplier of the initial signed state against which to perform a delta based
     *                                  reconnect
     * @param lastCompleteRoundSupplier provides the latest signed state round for which we have a supermajority of
     *                                  signatures
     * @param reconnectLearnerFactory   Creates instances of {@link ReconnectLearner} to execute the second phase,
     *                                  receiving a signed state
     * @param stateConfig               configuration for the state from the platform
     * @param platformStateFacade       provides access to the platform state
     */
    public ReconnectSyncHelper(
            @NonNull final Supplier<MerkleNodeState> workingStateSupplier,
            @NonNull final LongSupplier lastCompleteRoundSupplier,
            @NonNull final ReconnectLearnerFactory reconnectLearnerFactory,
            @NonNull final StateConfig stateConfig,
            @NonNull final PlatformStateFacade platformStateFacade) {

        this.connectionProvider = new BlockingResourceProvider<>();
        this.workingStateSupplier = Objects.requireNonNull(workingStateSupplier);
        this.lastCompleteRoundSupplier = Objects.requireNonNull(lastCompleteRoundSupplier);
        this.reconnectLearnerFactory = Objects.requireNonNull(reconnectLearnerFactory);
        this.stateConfig = Objects.requireNonNull(stateConfig);
        this.platformStateFacade = Objects.requireNonNull(platformStateFacade);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public @NonNull ReservedSignedState receiveSignedState(@NonNull final SignedStateValidator validator)
            throws ReconnectException, InterruptedException {
        Connection connection = null;
        try (final LockedResource<Connection> conn = connectionProvider.waitForResource()) {
            connection = conn.getResource();
            final ReservedSignedState reservedState = reconnectLearner(connection, validator);
            return reservedState;
        } catch (final RuntimeException e) {
            if (Utilities.isOrCausedBySocketException(e)) {
                logger.error(EXCEPTION.getMarker(), () -> new ReconnectFailurePayload(
                                "Got socket exception while receiving a signed state! "
                                        + NetworkUtils.formatException(e),
                                ReconnectFailurePayload.CauseOfFailure.SOCKET)
                        .toString());
            } else {
                if (connection != null) {
                    connection.disconnect();
                }
            }
            throw e;
        }
    }

    private @NonNull ReservedSignedState reconnectLearner(
            @NonNull final Connection conn, @NonNull final SignedStateValidator validator) throws ReconnectException {

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
     * Provides a connection over which a reconnect learner has been already negotiated. This method should only be
     * called if {@link #acquireLearnerPermit()} has returned true previously. This method blocks until the reconnect is
     * done. It also starts reconnect learner handling thread, if it wasn't started already.
     *
     * @param connection the connection to use to execute the reconnect learner protocol
     * @throws InterruptedException if the calling thread is interrupted while the connection is being used
     */
    public void provideLearnerConnection(@NonNull final Connection connection) throws InterruptedException {
        connectionProvider.provide(connection);
    }

    /**
     * Try to acquire a permit for negotiate a reconnect in the role of the learner
     *
     * @return true if the permit has been acquired
     */
    public boolean acquireLearnerPermit() {
        return connectionProvider.acquireProvidePermit();
    }

    /**
     * Try to block the learner permit for reconnect. The method {@link #cancelLearnerPermit()} should be called to
     * unblock the permit.
     *
     * @return true if the permit has been blocked
     */
    public boolean blockLearnerPermit() {
        return connectionProvider.tryBlockProvidePermit();
    }

    /**
     * Releases a previously acquired permit for reconnect
     */
    public void cancelLearnerPermit() {
        connectionProvider.releaseProvidePermit();
    }
}
