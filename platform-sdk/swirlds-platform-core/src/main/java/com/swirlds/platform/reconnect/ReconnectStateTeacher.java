// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.reconnect;

import static com.swirlds.base.formatting.StringFormattingUtils.formattedList;
import static com.swirlds.logging.legacy.LogMarker.RECONNECT;
import static com.swirlds.platform.reconnect.ReconnectStateLearner.endReconnectHandshake;
import static com.swirlds.platform.state.service.PlatformStateUtils.getInfoString;

import com.hedera.hapi.node.state.roster.Roster;
import com.hedera.pbj.runtime.io.stream.WritableStreamingData;
import com.swirlds.base.time.Time;
import com.swirlds.common.merkle.synchronization.TeachingSynchronizer;
import com.swirlds.common.merkle.synchronization.views.TeacherTreeView;
import com.swirlds.config.api.Configuration;
import com.swirlds.logging.legacy.payload.ReconnectFinishPayload;
import com.swirlds.logging.legacy.payload.ReconnectStartPayload;
import com.swirlds.platform.config.StateConfig;
import com.swirlds.platform.metrics.ReconnectMetrics;
import com.swirlds.platform.state.signed.SigSet;
import com.swirlds.platform.state.signed.SignedState;
import com.swirlds.state.merkle.VirtualMapState;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.IOException;
import java.net.SocketException;
import java.time.Duration;
import java.util.Objects;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hiero.base.crypto.Hash;
import org.hiero.base.io.streams.SerializableDataInputStream;
import org.hiero.base.io.streams.SerializableDataOutputStream;
import org.hiero.consensus.concurrent.manager.ThreadManager;
import org.hiero.consensus.gossip.impl.network.Connection;
import org.hiero.consensus.model.node.NodeId;
import org.hiero.consensus.reconnect.config.ReconnectConfig;
import org.hiero.consensus.roster.RosterUtils;

/**
 * This class encapsulates logic for transmitting the up-to-date state to a peer that has an out-of-date state.
 */
public class ReconnectStateTeacher {

    /** use this for all logging, as controlled by the optional data/log4j2.xml file */
    private static final Logger logger = LogManager.getLogger(ReconnectStateTeacher.class);

    private final Connection connection;
    private final Duration reconnectSocketTimeout;

    private final TeacherTreeView<Long> teacherView;
    private final SigSet signatures;
    private final long signingWeight;
    private final Roster roster;
    private final Hash hash;

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

    /**
     * @param configuration the platform context
     * @param time the source of time
     * @param threadManager responsible for managing thread lifecycles
     * @param connection the connection to be used for the reconnect
     * @param reconnectSocketTimeout the socket timeout to use during the reconnect
     * @param selfId this node's ID
     * @param otherId the learner's ID
     * @param lastRoundReceived the round of the state
     * @param signedState the state used for teaching; must be a signed VirtualMapState
     * @param statistics reconnect metrics
     */
    public ReconnectStateTeacher(
            @NonNull final Configuration configuration,
            @NonNull final Time time,
            @NonNull final ThreadManager threadManager,
            @NonNull final Connection connection,
            @NonNull final Duration reconnectSocketTimeout,
            @NonNull final NodeId selfId,
            @NonNull final NodeId otherId,
            final long lastRoundReceived,
            @NonNull final SignedState signedState,
            @NonNull final ReconnectMetrics statistics) {

        this.time = Objects.requireNonNull(time);
        this.threadManager = Objects.requireNonNull(threadManager);
        this.connection = Objects.requireNonNull(connection);
        this.reconnectSocketTimeout = reconnectSocketTimeout;

        this.selfId = Objects.requireNonNull(selfId);
        this.otherId = Objects.requireNonNull(otherId);
        this.lastRoundReceived = lastRoundReceived;
        this.statistics = Objects.requireNonNull(statistics);
        this.configuration = Objects.requireNonNull(configuration);

        signatures = signedState.getSigSet();
        signingWeight = signedState.getSigningWeight();
        roster = signedState.getRoster();
        hash = signedState.getState().getHash();
        if (!(signedState.getState() instanceof VirtualMapState virtualMapState)) {
            throw new UnsupportedOperationException("Reconnects are only supported for VirtualMap states");
        }
        final ReconnectConfig reconnectConfig = configuration.getConfigData(ReconnectConfig.class);
        // The teacher view will be closed by TeacherSynchronizer in reconnect() below
        teacherView = virtualMapState.getRoot().buildTeacherView(reconnectConfig);

        logReconnectStart(signedState);
    }

    /**
     * increase socketTimout before performing reconnect
     *
     * @throws ReconnectStateException thrown when there is an error in the underlying protocol
     */
    private void increaseSocketTimeout() throws ReconnectStateException {
        try {
            originalSocketTimeout = connection.getTimeout();
            connection.setTimeout(reconnectSocketTimeout.toMillis());
        } catch (final SocketException e) {
            throw new ReconnectStateException(e);
        }
    }

    /**
     * Reset socketTimeout to original value
     *
     * @throws ReconnectStateException thrown when there is an error in the underlying protocol
     */
    private void resetSocketTimeout() throws ReconnectStateException {
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
            throw new ReconnectStateException(e);
        }
    }

    /**
     * Perform the reconnect operation.
     *
     * @throws ReconnectStateException thrown when current thread is interrupted, or when any I/O related errors occur, or
     *                            when there is an error in the underlying protocol
     */
    public void execute() throws ReconnectStateException {
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
        increaseSocketTimeout();

        try {
            sendSignatures();
            reconnect();
            endReconnectHandshake(connection);
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ReconnectStateException(e);
        } catch (final IOException e) {
            throw new ReconnectStateException(e);
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
        logger.info(RECONNECT.getMarker(), """
                        The following state will be sent to the learner:
                        {}""", () -> getInfoString(signedState.getState()));
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
    private void reconnect() throws InterruptedException, IOException {
        logger.info(RECONNECT.getMarker(), "Starting synchronization in the role of the sender.");
        statistics.incrementSenderStartTimes();

        connection.getDis().getSyncByteCounter().resetCount();
        connection.getDos().getSyncByteCounter().resetCount();

        final ReconnectConfig reconnectConfig = configuration.getConfigData(ReconnectConfig.class);
        final TeachingSynchronizer synchronizer = new TeachingSynchronizer(
                time,
                threadManager,
                new SerializableDataInputStream(connection.getDis()),
                new SerializableDataOutputStream(connection.getDos()),
                teacherView,
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
    private void sendSignatures() throws IOException {
        final StringBuilder sb = new StringBuilder();
        sb.append("Sending signatures from nodes ");
        formattedList(sb, signatures.iterator());
        sb.append(" (signing weight = ")
                .append(signingWeight)
                .append("/")
                .append(RosterUtils.computeTotalWeight(roster))
                .append(") for state hash ")
                .append(hash);

        logger.info(RECONNECT.getMarker(), sb);
        final WritableStreamingData wsd = new WritableStreamingData(connection.getDos());
        signatures.serialize(wsd);
        connection.getDos().flush();
    }
}
