// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.reconnect;

import static com.swirlds.base.formatting.StringFormattingUtils.formattedList;
import static com.swirlds.logging.legacy.LogMarker.RECONNECT;

import com.swirlds.common.context.PlatformContext;
import com.swirlds.common.merkle.synchronization.LearningSynchronizer;
import com.swirlds.common.merkle.synchronization.config.ReconnectConfig;
import com.swirlds.common.merkle.synchronization.stats.ReconnectMapMetrics;
import com.swirlds.common.merkle.synchronization.stats.ReconnectMapStats;
import com.swirlds.common.merkle.synchronization.utility.MerkleSynchronizationException;
import com.swirlds.common.merkle.synchronization.views.LearnerTreeView;
import com.swirlds.logging.legacy.payload.ReconnectDataUsagePayload;
import com.swirlds.platform.metrics.ReconnectMetrics;
import com.swirlds.platform.network.Connection;
import com.swirlds.platform.state.signed.ReservedSignedState;
import com.swirlds.platform.state.signed.SigSet;
import com.swirlds.platform.state.signed.SignedState;
import com.swirlds.platform.state.signed.SignedStateInvalidException;
import com.swirlds.platform.state.snapshot.SignedStateFileReader;
import com.swirlds.state.MerkleNodeState;
import com.swirlds.state.StateLifecycleManager;
import com.swirlds.state.merkle.VirtualMapState;
import com.swirlds.virtualmap.VirtualMap;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.IOException;
import java.net.SocketException;
import java.time.Duration;
import java.util.Objects;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hiero.base.io.streams.SerializableDataInputStream;
import org.hiero.base.io.streams.SerializableDataOutputStream;
import org.hiero.consensus.concurrent.manager.ThreadManager;
import org.hiero.consensus.crypto.ConsensusCryptoUtils;

/**
 * This class encapsulates logic for receiving the up-to-date state from a peer when the local node's state is out-of-date.
 */
public class ReconnectStateLearner {

    /** use this for all logging, as controlled by the optional data/log4j2.xml file */
    private static final Logger logger = LogManager.getLogger(ReconnectStateLearner.class);
    /**
     * A value to send to signify the end of a reconnect. A random long value is chosen to minimize the possibility that
     * the stream is misaligned
     */
    private static final long END_RECONNECT_MSG = 0x7747b5bd49693b61L;

    private final Connection connection;
    private final MerkleNodeState currentState;
    private final Duration reconnectSocketTimeout;
    private final ReconnectMetrics statistics;
    private final StateLifecycleManager stateLifecycleManager;

    private SigSet sigSet;
    private final PlatformContext platformContext;
    /**
     * After reconnect is finished, restore the socket timeout to the original value.
     */
    private int originalSocketTimeout;

    private final ThreadManager threadManager;

    /**
     * @param threadManager
     * 		responsible for managing thread lifecycles
     * @param connection
     * 		the connection to use for the reconnect
     * @param currentState
     * 		the most recent state from the learner; must be a VirtualMapState
     * @param reconnectSocketTimeout
     * 		the amount of time that should be used for the socket timeout
     * @param statistics
     * 		reconnect metrics
     * @param stateLifecycleManager the state lifecycle manager
     */
    public ReconnectStateLearner(
            @NonNull final PlatformContext platformContext,
            @NonNull final ThreadManager threadManager,
            @NonNull final Connection connection,
            @NonNull final MerkleNodeState currentState,
            @NonNull final Duration reconnectSocketTimeout,
            @NonNull final ReconnectMetrics statistics,
            @NonNull final StateLifecycleManager stateLifecycleManager) {
        this.stateLifecycleManager = Objects.requireNonNull(stateLifecycleManager);

        currentState.throwIfImmutable("Can not perform reconnect with immutable state");
        currentState.throwIfDestroyed("Can not perform reconnect with destroyed state");

        this.platformContext = Objects.requireNonNull(platformContext);
        this.threadManager = Objects.requireNonNull(threadManager);
        this.connection = Objects.requireNonNull(connection);
        this.currentState = Objects.requireNonNull(currentState);
        this.reconnectSocketTimeout = Objects.requireNonNull(reconnectSocketTimeout);
        this.statistics = Objects.requireNonNull(statistics);

        // Save some of the current state data for validation
    }

    /**
     * Send and receive the end reconnect message
     *
     * @param connection the connection to send/receive on
     * @throws IOException if the connection breaks, times out, or the wrong message is received
     */
    static void endReconnectHandshake(@NonNull final Connection connection) throws IOException {
        connection.getDos().writeLong(END_RECONNECT_MSG);
        connection.getDos().flush();
        final long endReconnectMsg = connection.getDis().readLong();
        if (endReconnectMsg != END_RECONNECT_MSG) {
            throw new IOException("Did not receive expected end reconnect message. Expecting %x, Received %x"
                    .formatted(END_RECONNECT_MSG, endReconnectMsg));
        }
    }

    /**
     * @throws ReconnectStateException
     * 		thrown when there is an error in the underlying protocol
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
     * @throws ReconnectStateException
     * 		thrown when there is an error in the underlying protocol
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
     * @throws ReconnectStateException
     * 		thrown if I/O related errors occur, when there is an error in the underlying protocol, or the received
     * 		state is invalid
     * @return the state received from the other node
     */
    @NonNull
    public ReservedSignedState execute() throws ReconnectStateException {
        increaseSocketTimeout();
        ReservedSignedState reservedSignedState = null;
        try {
            receiveSignatures();
            reservedSignedState = reconnect();
            endReconnectHandshake(connection);
            return reservedSignedState;
        } catch (final IOException | SignedStateInvalidException e) {
            if (reservedSignedState != null) {
                // if the state was received, we need to release it or it will be leaked
                reservedSignedState.close();
            }
            throw new ReconnectStateException(e);
        } catch (final InterruptedException e) {
            // an interrupt can only occur in the reconnect() method, so we don't need to close the reservedSignedState
            Thread.currentThread().interrupt();
            throw new ReconnectStateException("interrupted while attempting to reconnect", e);
        } finally {
            resetSocketTimeout();
        }
    }

    /**
     * Get a copy of the state from the other node.
     *
     * @throws InterruptedException
     * 		if the current thread is interrupted
     */
    @NonNull
    private ReservedSignedState reconnect() throws InterruptedException {
        if (!(currentState instanceof VirtualMapState virtualMapState)) {
            throw new UnsupportedOperationException("Reconnects are only supported for VirtualMap states");
        }

        statistics.incrementReceiverStartTimes();

        final SerializableDataInputStream in = new SerializableDataInputStream(connection.getDis());
        final SerializableDataOutputStream out = new SerializableDataOutputStream(connection.getDos());

        connection.getDis().getSyncByteCounter().resetCount();
        connection.getDos().getSyncByteCounter().resetCount();

        final ReconnectConfig reconnectConfig =
                platformContext.getConfiguration().getConfigData(ReconnectConfig.class);

        final VirtualMap reconnectRoot = virtualMapState.getRoot().newReconnectRoot();
        final ReconnectMapStats mapStats = new ReconnectMapMetrics(platformContext.getMetrics(), null, null);
        // The learner view will be closed by LearningSynchronizer
        final LearnerTreeView<?> learnerView = reconnectRoot.buildLearnerView(reconnectConfig, mapStats);
        final LearningSynchronizer synchronizer = new LearningSynchronizer(
                threadManager, in, out, reconnectRoot, learnerView, connection::disconnect, reconnectConfig);
        try {
            synchronizer.synchronize();
            logger.info(RECONNECT.getMarker(), () -> mapStats.format());
        } catch (final InterruptedException e) {
            logger.warn(RECONNECT.getMarker(), "Synchronization interrupted");
            Thread.currentThread().interrupt();
            reconnectRoot.release();
            throw e;
        } catch (final Exception e) {
            reconnectRoot.release();
            throw new MerkleSynchronizationException(e);
        }

        final MerkleNodeState receivedState = stateLifecycleManager.createStateFrom(reconnectRoot);
        final SignedState newSignedState = new SignedState(
                platformContext.getConfiguration(),
                ConsensusCryptoUtils::verifySignature,
                receivedState,
                "ReconnectLearner.reconnect()",
                false,
                false,
                false);
        SignedStateFileReader.registerServiceStates(newSignedState);
        newSignedState.setSigSet(sigSet);

        final double mbReceived = connection.getDis().getSyncByteCounter().getMebiBytes();
        logger.info(
                RECONNECT.getMarker(),
                () -> new ReconnectDataUsagePayload("Reconnect data usage report", mbReceived).toString());

        statistics.incrementReceiverEndTimes();

        return newSignedState.reserve("ReconnectLearner.reconnect()");
    }

    /**
     * Copy the signatures for the state from the other node.
     *
     * @throws IOException
     * 		if any I/O related errors occur
     */
    private void receiveSignatures() throws IOException {
        logger.info(RECONNECT.getMarker(), "Receiving signed state signatures");
        sigSet = connection.getDis().readSerializable();

        final StringBuilder sb = new StringBuilder();
        sb.append("Received signatures from nodes ");
        formattedList(sb, sigSet.iterator());
        logger.info(RECONNECT.getMarker(), sb);
    }
}
