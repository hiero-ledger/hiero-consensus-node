// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.reconnect.impl;

import static com.swirlds.base.formatting.StringFormattingUtils.formattedList;
import static com.swirlds.base.units.UnitConstants.MILLISECONDS_TO_SECONDS;
import static com.swirlds.logging.legacy.LogMarker.RECONNECT;
import static java.util.Objects.requireNonNull;

import com.hedera.pbj.runtime.ParseException;
import com.hedera.pbj.runtime.io.stream.ReadableStreamingData;
import com.swirlds.config.api.Configuration;
import com.swirlds.logging.legacy.payload.ReconnectDataUsagePayload;
import com.swirlds.logging.legacy.payload.SynchronizationCompletePayload;
import com.swirlds.metrics.api.Metrics;
import com.swirlds.state.StateLifecycleManager;
import com.swirlds.state.merkle.VirtualMapState;
import com.swirlds.virtualmap.VirtualMap;
import com.swirlds.virtualmap.VirtualMapLearner;
import com.swirlds.virtualmap.sync.LearningSynchronizer;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.SocketException;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hiero.base.concurrent.framework.config.CompositeThreadNameProvider;
import org.hiero.base.concurrent.framework.config.ThreadConfiguration;
import org.hiero.base.concurrent.manager.ThreadManager;
import org.hiero.base.crypto.CryptoUtils;
import org.hiero.consensus.gossip.impl.network.Connection;
import org.hiero.consensus.state.SignedStateFileReader;
import org.hiero.consensus.state.signed.ReservedSignedState;
import org.hiero.consensus.state.signed.SigSet;
import org.hiero.consensus.state.signed.SignedState;
import org.hiero.consensus.state.signed.SignedStateInvalidException;

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
    private final VirtualMapState currentState;
    private final Duration reconnectSocketTimeout;
    private final ReconnectMetrics statistics;
    private final StateLifecycleManager<VirtualMapState, VirtualMap> stateLifecycleManager;
    private final Configuration configuration;
    private final ThreadManager threadManager;
    private final LearningSynchronizer synchronizer;

    private SigSet sigSet;

    /**
     * After reconnect is finished, restore the socket timeout to the original value.
     */
    private int originalSocketTimeout;

    /**
     *
     * @param configuration the platform configuration
     * @param metrics the metrics system
     * @param threadManager responsible for managing thread lifecycles
     * @param connection the connection to use for the reconnect
     * @param currentState the most recent state from the learner; must be a VirtualMapStateImpl
     * @param reconnectSocketTimeout the amount of time that should be used for the socket timeout
     * @param statistics reconnect metrics
     * @param stateLifecycleManager the state lifecycle manager
     */
    public ReconnectStateLearner(
            @NonNull final Configuration configuration,
            @NonNull final Metrics metrics,
            @NonNull final ThreadManager threadManager,
            @NonNull final Connection connection,
            @NonNull final VirtualMapState currentState,
            @NonNull final Duration reconnectSocketTimeout,
            @NonNull final ReconnectMetrics statistics,
            @NonNull final StateLifecycleManager<VirtualMapState, VirtualMap> stateLifecycleManager) {
        this(
                configuration,
                threadManager,
                connection,
                currentState,
                reconnectSocketTimeout,
                statistics,
                stateLifecycleManager,
                new LearningSynchronizer(threadManager, configuration, requireNonNull(metrics)));
    }

    /**
     * Same as the public constructor, with the given synchronizer. Exposed for testing.
     */
    ReconnectStateLearner(
            @NonNull final Configuration configuration,
            @NonNull final ThreadManager threadManager,
            @NonNull final Connection connection,
            @NonNull final VirtualMapState currentState,
            @NonNull final Duration reconnectSocketTimeout,
            @NonNull final ReconnectMetrics statistics,
            @NonNull final StateLifecycleManager<VirtualMapState, VirtualMap> stateLifecycleManager,
            @NonNull final LearningSynchronizer synchronizer) {
        this.stateLifecycleManager = requireNonNull(stateLifecycleManager);

        this.threadManager = requireNonNull(threadManager);
        currentState.throwIfImmutable("Can not perform reconnect with immutable state");
        currentState.throwIfDestroyed("Can not perform reconnect with destroyed state");

        this.configuration = requireNonNull(configuration);
        this.connection = requireNonNull(connection);
        this.currentState = requireNonNull(currentState);
        this.reconnectSocketTimeout = requireNonNull(reconnectSocketTimeout);
        this.statistics = requireNonNull(statistics);
        this.synchronizer = requireNonNull(synchronizer);
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
        CompletableFuture<VirtualMapLearner> learnerFuture = null;
        boolean learnerHandedOver = false;
        ReservedSignedState reservedSignedState = null;
        try {
            // Copying the learner's data source takes long for large states, and it depends only on the learner's
            // own state, which doesn't change during the reconnect. Start it now, so that it runs while the teacher
            // prepares its state and sends the signatures
            learnerFuture = createLearnerAsync();
            receiveSignatures();
            statistics.incrementReceiverStartTimes();
            connection.getDis().byteCounter().getAndReset();
            final VirtualMapLearner learner = awaitLearner(learnerFuture);
            // From here, the synchronizer owns the learner and aborts it if the synchronization fails. Nothing
            // that can fail runs between this line and the synchronizer taking the learner over
            learnerHandedOver = true;
            reservedSignedState = reconnect(learner);
            endReconnectHandshake(connection);
            return reservedSignedState;
        } catch (final IOException | SignedStateInvalidException | ParseException e) {
            if (reservedSignedState != null) {
                // if the state was received, we need to release it or it will be leaked
                reservedSignedState.close();
            }
            throw new ReconnectStateException(e);
        } catch (final InterruptedException e) {
            // an interrupt can only occur before the state is received, so we don't need to close the
            // reservedSignedState
            Thread.currentThread().interrupt();
            throw new ReconnectStateException("interrupted while attempting to reconnect", e);
        } finally {
            if ((learnerFuture != null) && !learnerHandedOver) {
                // The reconnect failed before the synchronization started. Disconnect first: the teacher may be
                // waiting for requests, holding its state and its own data source copy, while this method waits
                // for the learner's copy to finish
                connection.disconnect();
                discardLearner(learnerFuture);
            }
            resetSocketTimeout();
        }
    }

    /**
     * Starts creating the learner for the current state on a new thread.
     *
     * @return the future learner
     */
    @NonNull
    private CompletableFuture<VirtualMapLearner> createLearnerAsync() {
        final VirtualMap originalMap = currentState.getRoot();
        final Executor newThread = runnable -> new ThreadConfiguration(threadManager)
                .setSingleThreadName(CompositeThreadNameProvider.create("platform-core", "reconnectLearnerCopy"))
                .setRunnable(runnable)
                .build(true);
        return CompletableFuture.supplyAsync(
                () -> {
                    final long start = System.currentTimeMillis();
                    final VirtualMapLearner learner = synchronizer.createLearner(originalMap);
                    logger.info(
                            RECONNECT.getMarker(),
                            "Learner took {} seconds to prepare it's state for synchronization",
                            (System.currentTimeMillis() - start) * MILLISECONDS_TO_SECONDS);
                    return learner;
                },
                newThread);
    }

    /**
     * Waits for the learner created by {@link #createLearnerAsync()}.
     *
     * @param learnerFuture the future learner
     * @return the learner
     * @throws InterruptedException if the current thread is interrupted while waiting
     * @throws ReconnectStateException if the learner couldn't be created
     */
    @NonNull
    private static VirtualMapLearner awaitLearner(@NonNull final CompletableFuture<VirtualMapLearner> learnerFuture)
            throws InterruptedException {
        try {
            return learnerFuture.get();
        } catch (final ExecutionException e) {
            throw new ReconnectStateException("Failed to create the learner", e.getCause());
        }
    }

    /**
     * Closes the learner created by {@link #createLearnerAsync()}, if the reconnect fails before the learner is
     * handed over to the synchronizer. The learner isn't interrupted while it's being created: its data source is
     * copied while the virtual pipeline is paused, and an interrupt during MerkleDB file I/O can close file
     * channels the copy needs. Instead, this method waits for the learner, so that a retry doesn't start another
     * copy while this one still exists. If the current thread is interrupted while waiting, the learner is closed
     * when it's ready.
     *
     * @param learnerFuture the future learner
     */
    private static void discardLearner(@NonNull final CompletableFuture<VirtualMapLearner> learnerFuture) {
        try {
            learnerFuture.thenAccept(VirtualMapLearner::abortOnException).get();
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (final ExecutionException e) {
            // There's nothing to close
            logger.warn(RECONNECT.getMarker(), "Failed to create the learner", e.getCause());
        }
    }

    /**
     * Receive and reconstruct the state from the teacher.
     *
     * @param learner the learner for the current state, created by {@link #createLearnerAsync()}. This method
     *     passes it to the synchronizer before anything else can fail, so the synchronizer owns it
     * @return the signed state received from the teacher
     * @throws InterruptedException if the current thread is interrupted
     */
    @NonNull
    private ReservedSignedState reconnect(@NonNull final VirtualMapLearner learner) throws InterruptedException {
        final DataInputStream in = new DataInputStream(connection.getDis());
        final DataOutputStream out = new DataOutputStream(connection.getDos());
        VirtualMap syncedVirtualMap;

        final long syncStartTime = System.currentTimeMillis();
        try {
            syncedVirtualMap = synchronizer.synchronize(learner, in, out, connection::disconnect);
        } catch (final InterruptedException e) {
            logger.warn(RECONNECT.getMarker(), "Synchronization interrupted");
            Thread.currentThread().interrupt();
            throw e;
        } catch (final Exception e) {
            throw new ReconnectStateException(e);
        }

        final long synchronizationTimeMilliseconds = System.currentTimeMillis() - syncStartTime;
        logger.info(RECONNECT.getMarker(), () -> new SynchronizationCompletePayload("Finished synchronization")
                .setTimeInSeconds(synchronizationTimeMilliseconds * MILLISECONDS_TO_SECONDS)
                .toString());

        final VirtualMapState receivedState = stateLifecycleManager.createStateFrom(syncedVirtualMap);
        final SignedState newSignedState = new SignedState(
                configuration,
                CryptoUtils::verifySignature,
                receivedState,
                "ReconnectLearner.reconnect()",
                false,
                false,
                false);
        SignedStateFileReader.registerServiceStates(newSignedState);
        newSignedState.setSigSet(sigSet);

        final double mbReceived = connection.getDis().byteCounter().getMebiBytes();
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
    private void receiveSignatures() throws IOException, ParseException {
        logger.info(RECONNECT.getMarker(), "Receiving signed state signatures");

        sigSet = new SigSet();
        final ReadableStreamingData streamingData = new ReadableStreamingData(connection.getDis());
        sigSet.deserialize(streamingData);

        final StringBuilder sb = new StringBuilder();
        sb.append("Received signatures from nodes ");
        formattedList(sb, sigSet.iterator());
        logger.info(RECONNECT.getMarker(), sb);
    }
}
