// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.reconnect;

import static com.swirlds.common.formatting.StringFormattingUtils.formattedList;
import static com.swirlds.logging.legacy.LogMarker.RECONNECT;

import com.swirlds.base.time.Time;
import com.swirlds.common.context.PlatformContext;
import com.swirlds.common.io.streams.MerkleDataInputStream;
import com.swirlds.common.io.streams.MerkleDataOutputStream;
import com.swirlds.common.merkle.synchronization.TeachingSynchronizer;
import com.swirlds.common.merkle.synchronization.config.ReconnectConfig;
import com.swirlds.common.threading.manager.ThreadManager;
import com.swirlds.config.api.Configuration;
import com.swirlds.logging.legacy.payload.ReconnectFinishPayload;
import com.swirlds.logging.legacy.payload.ReconnectStartPayload;
import com.swirlds.platform.config.StateConfig;
import com.swirlds.platform.metrics.ReconnectMetrics;
import com.swirlds.platform.network.Connection;
import com.swirlds.platform.roster.RosterUtils;
import com.swirlds.platform.state.service.PlatformStateFacade;
import com.swirlds.platform.state.signed.SignedState;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.IOException;
import java.net.SocketException;
import java.time.Duration;
import java.util.Objects;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hiero.consensus.model.node.NodeId;

/**
 * This class encapsulates reconnect logic for the up to date node which is helping an out of date node obtain a recent
 * state.
 */
public class ReconnectTeacher {

    /** use this for all logging, as controlled by the optional data/log4j2.xml file */
    private static final Logger logger = LogManager.getLogger(ReconnectTeacher.class);

    private final Connection connection;
    private final Duration reconnectSocketTimeout;

    private final NodeId selfId;
    private final NodeId otherId;
    private final long lastRoundReceived;
    private final Configuration configuration;

    private final ReconnectMetrics statistics;

    /**
     * After reconnect is finished, restore the socket timeout to the original value.
     */
    private int originalSocketTimeout;

    private final ThreadManager threadManager;
    private final Time time;
    private final PlatformContext platformContext;

    private final PlatformStateFacade platformStateFacade;

    /**
     * @param platformContext        the platform context
     * @param threadManager          responsible for managing thread lifecycles
     * @param connection             the connection to be used for the reconnect
     * @param reconnectSocketTimeout the socket timeout to use during the reconnect
     * @param selfId                 this node's ID
     * @param otherId                the learner's ID
     * @param lastRoundReceived      the round of the state
     * @param statistics             reconnect metrics
     * @param platformStateFacade    the facade to access the platform state
     */
    public ReconnectTeacher(
            @NonNull final PlatformContext platformContext,
            @NonNull final Time time,
            @NonNull final ThreadManager threadManager,
            @NonNull final Connection connection,
            @NonNull final Duration reconnectSocketTimeout,
            @NonNull final NodeId selfId,
            @NonNull final NodeId otherId,
            final long lastRoundReceived,
            @NonNull final ReconnectMetrics statistics,
            @NonNull final PlatformStateFacade platformStateFacade) {

        this.platformContext = Objects.requireNonNull(platformContext);
        this.time = Objects.requireNonNull(time);
        this.threadManager = Objects.requireNonNull(threadManager);
        this.connection = Objects.requireNonNull(connection);
        this.reconnectSocketTimeout = reconnectSocketTimeout;

        this.selfId = Objects.requireNonNull(selfId);
        this.otherId = Objects.requireNonNull(otherId);
        this.lastRoundReceived = lastRoundReceived;
        this.statistics = Objects.requireNonNull(statistics);
        this.configuration = Objects.requireNonNull(platformContext.getConfiguration());
        this.platformStateFacade = platformStateFacade;
    }

    /**
     * increase socketTimout before performing reconnect
     *
     * @throws ReconnectException thrown when there is an error in the underlying protocol
     */
    private void increaseSocketTimeout() throws ReconnectException {
        try {
            originalSocketTimeout = connection.getTimeout();
            connection.setTimeout(reconnectSocketTimeout.toMillis());
        } catch (final SocketException e) {
            throw new ReconnectException(e);
        }
    }

    /**
     * Reset socketTimeout to original value
     *
     * @throws ReconnectException thrown when there is an error in the underlying protocol
     */
    private void resetSocketTimeout() throws ReconnectException {
        if (!connection.connected()) {
            logger.debug(
                    RECONNECT.getMarker(),
                    "{} connection to {} is no longer connected. Returning.",
                    connection.getSelfId(),
                    connection.getOtherId());
            return;
        }

        try {
            connection.setTimeout(originalSocketTimeout);
        } catch (final SocketException e) {
            throw new ReconnectException(e);
        }
    }

    /**
     * Perform the reconnect operation.
     *
     * @throws ReconnectException thrown when current thread is interrupted, or when any I/O related errors occur, or
     *                            when there is an error in the underlying protocol
     */
    public void execute(final SignedState signedState) throws ReconnectException {

        // If the connection object to be used here has been disconnected on another thread, we can
        // not reconnect with this connection.
        if (!connection.connected()) {
            logger.debug(
                    RECONNECT.getMarker(),
                    "{} connection to {} is no longer connected. Returning.",
                    connection.getSelfId(),
                    connection.getOtherId());
            return;
        }
        logReconnectStart(signedState);
        increaseSocketTimeout();

        try {
            sendSignatures(signedState);
            reconnect(signedState);
            ReconnectUtils.endReconnectHandshake(connection);
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ReconnectException(e);
        } catch (final IOException e) {
            throw new ReconnectException(e);
        } finally {
            resetSocketTimeout();
        }
        logReconnectFinish();
    }

    private void logReconnectStart(final SignedState signedState) {
        logger.info(
                RECONNECT.getMarker(),
                () -> new ReconnectStartPayload(
                        "Starting reconnect in the role of the sender",
                        false,
                        selfId.id(),
                        otherId.id(),
                        lastRoundReceived));
        final StateConfig stateConfig = configuration.getConfigData(StateConfig.class);
        logger.info(
                RECONNECT.getMarker(),
                """
                        The following state will be sent to the learner:
                        {}""",
                () -> platformStateFacade.getInfoString(signedState.getState(), stateConfig.debugHashDepth()));
    }

    private void logReconnectFinish() {
        logger.info(
                RECONNECT.getMarker(),
                () -> new ReconnectFinishPayload(
                        "Finished reconnect in the role of the sender.",
                        false,
                        selfId.id(),
                        otherId.id(),
                        lastRoundReceived));
    }

    /**
     * Copy the signed state from this node to the other node.
     *
     * @throws InterruptedException thrown if the current thread is interrupted
     */
    private void reconnect(final SignedState signedState) throws InterruptedException, IOException {
        logger.info(RECONNECT.getMarker(), "Starting synchronization in the role of the sender.");
        statistics.incrementSenderStartTimes();

        connection.getDis().getSyncByteCounter().resetCount();
        connection.getDos().getSyncByteCounter().resetCount();

        final ReconnectConfig reconnectConfig = configuration.getConfigData(ReconnectConfig.class);
        final TeachingSynchronizer synchronizer = new TeachingSynchronizer(
                platformContext.getConfiguration(),
                time,
                threadManager,
                new MerkleDataInputStream(connection.getDis()),
                new MerkleDataOutputStream(connection.getDos()),
                signedState.getState().getRoot(),
                connection::disconnect,
                reconnectConfig);

        synchronizer.synchronize();
        connection.getDos().flush();

        statistics.incrementSenderEndTimes();
        logger.info(RECONNECT.getMarker(), "Finished synchronization in the role of the sender.");
    }

    /**
     * Copy the signatures on the signed state from this node to the other node.
     *
     * @throws IOException thrown when any I/O related errors occur
     */
    private void sendSignatures(final SignedState signedState) throws IOException {
        final StringBuilder sb = new StringBuilder();
        sb.append("Sending signatures from nodes ");
        formattedList(sb, signedState.getSigSet().iterator());
        sb.append(" (signing weight = ")
                .append(signedState.getSigningWeight())
                .append("/")
                .append(RosterUtils.computeTotalWeight(signedState.getRoster()))
                .append(") for state hash ")
                .append(signedState.getState().getHash());

        logger.info(RECONNECT.getMarker(), sb);
        connection.getDos().writeSerializable(signedState.getSigSet(), true);
        connection.getDos().flush();
    }
}
