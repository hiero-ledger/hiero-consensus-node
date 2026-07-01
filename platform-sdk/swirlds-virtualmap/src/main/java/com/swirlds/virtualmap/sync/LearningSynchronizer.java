// SPDX-License-Identifier: Apache-2.0
package com.swirlds.virtualmap.sync;

import static com.swirlds.logging.legacy.LogMarker.RECONNECT;

import com.hedera.pbj.runtime.io.buffer.BufferedData;
import com.swirlds.config.api.Configuration;
import com.swirlds.metrics.api.Metrics;
import com.swirlds.virtualmap.VirtualMap;
import com.swirlds.virtualmap.VirtualMapLearner;
import com.swirlds.virtualmap.config.VirtualMapConfig;
import com.swirlds.virtualmap.config.VirtualMapReconnectMode;
import com.swirlds.virtualmap.config.VirtualMapSyncConfig;
import com.swirlds.virtualmap.internal.Path;
import com.swirlds.virtualmap.internal.reconnect.LearnerPullVirtualTreeReceiveTask;
import com.swirlds.virtualmap.internal.reconnect.LearnerPullVirtualTreeSendTask;
import com.swirlds.virtualmap.internal.reconnect.ParallelSyncTraversalOrder;
import com.swirlds.virtualmap.internal.reconnect.PullVirtualTreeRequest;
import com.swirlds.virtualmap.internal.reconnect.PullVirtualTreeResponse;
import com.swirlds.virtualmap.internal.reconnect.TopToBottomTraversalOrder;
import com.swirlds.virtualmap.internal.reconnect.TwoPhasePessimisticTraversalOrder;
import com.swirlds.virtualmap.sync.streams.AsyncInputStream;
import com.swirlds.virtualmap.sync.streams.AsyncOutputStream;
import com.swirlds.virtualmap.sync.streams.YieldStrategy;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hiero.base.crypto.Hash;
import org.hiero.consensus.concurrent.manager.ThreadManager;
import org.hiero.consensus.concurrent.pool.StandardWorkGroup;

/**
 * Performs reconnect in the role of the learner.
 */
public class LearningSynchronizer {

    private static final Logger logger = LogManager.getLogger(LearningSynchronizer.class);

    private static final String WORK_GROUP_NAME = "learning-synchronizer";

    private final ThreadManager threadManager;
    private final VirtualMapSyncConfig syncConfig;
    private final Metrics metrics;

    /**
     * Constructs a new learning synchronizer.
     *
     * @param threadManager responsible for managing thread lifecycles
     * @param config the configuration
     * @param metrics the metrics system for recording synchronization metrics
     */
    public LearningSynchronizer(
            @NonNull final ThreadManager threadManager,
            @NonNull final Configuration config,
            @NonNull final Metrics metrics) {

        this.threadManager = Objects.requireNonNull(threadManager, "threadManager cannot be null");
        this.syncConfig =
                Objects.requireNonNull(config, "config cannot be null").getConfigData(VirtualMapSyncConfig.class);
        this.metrics = Objects.requireNonNull(metrics, "metrics cannot be null");
    }

    @NonNull
    private LearnerTreeExchanger buildLearnerExchanger(VirtualMap originalVirtualMap, LearnerSyncMetrics syncMetrics) {
        logger.info(
                RECONNECT.getMarker(),
                "Building learner exchanger for map with path range [{}, {}]",
                originalVirtualMap.getMetadata().getFirstLeafPath(),
                originalVirtualMap.getMetadata().getLastLeafPath());

        final VirtualMapConfig virtualMapConfig = originalVirtualMap.getVirtualMapConfig();
        final VirtualMapLearner vmapLearner = new VirtualMapLearner(originalVirtualMap, syncConfig);

        return switch (virtualMapConfig.reconnectMode()) {
            case VirtualMapReconnectMode.PULL_TOP_TO_BOTTOM ->
                new LearnerTreeExchanger(vmapLearner, new TopToBottomTraversalOrder(), syncMetrics);
            case VirtualMapReconnectMode.PULL_TWO_PHASE_PESSIMISTIC ->
                new LearnerTreeExchanger(vmapLearner, new TwoPhasePessimisticTraversalOrder(), syncMetrics);
            case VirtualMapReconnectMode.PULL_PARALLEL_SYNC ->
                new LearnerTreeExchanger(vmapLearner, new ParallelSyncTraversalOrder(), syncMetrics);
            default ->
                throw new UnsupportedOperationException("Unknown reconnect mode: "
                        + virtualMapConfig.reconnectMode()
                        + ". Supported modes: PULL_TOP_TO_BOTTOM,"
                        + " PULL_TWO_PHASE_PESSIMISTIC, PULL_PARALLEL_SYNC");
        };
    }

    /**
     * Perform reconnect in the role of the learner blocking until it's finished.
     *
     * @param originalMap original learner virtual map
     * @param in data input stream for reading requests from the teacher
     * @param out data output stream for sending responses to the teacher
     * @param breakConnection action to break the connection, which should be called if a reconnect-related exception is encountered and the connection should be closed.
     *
     * @return the synchronized virtual map
     * @throws InterruptedException if the synchronization is interrupted
     * @throws MerkleSynchronizationException if the synchronization fails due to an exception
     */
    public VirtualMap synchronize(
            @NonNull final VirtualMap originalMap,
            @NonNull final DataInputStream in,
            @NonNull final DataOutputStream out,
            @NonNull final Runnable breakConnection)
            throws InterruptedException {

        Objects.requireNonNull(originalMap, "originalMap cannot be null");
        Objects.requireNonNull(in, "input stream cannot be null");
        Objects.requireNonNull(out, "output stream cannot be null");
        Objects.requireNonNull(breakConnection, "break connection action cannot be null");

        final LearnerSyncMetrics syncMetrics = new LearnerSyncMetrics(metrics);
        final LearnerTreeExchanger exchanger = buildLearnerExchanger(originalMap, syncMetrics);

        try (final StandardWorkGroup workGroup = createStandardWorkGroup(threadManager, breakConnection)) {
            logger.info(RECONNECT.getMarker(), "learner start synchronizing");

            final AsyncInputStream input =
                    new AsyncInputStream(in, syncConfig.asyncStreamBufferSize(), syncConfig.asyncStreamTimeout());
            input.start(workGroup);
            final AsyncOutputStream output = buildOutputStream(out, syncConfig);
            output.start(workGroup);

            // Perform the root-node (path 0) request/response handshake synchronously before forking
            // any parallel tasks. The root response carries the teacher's first/last leaf path range,
            // which must be known before the traversal order can be started and before any parallel
            // send tasks can generate meaningful non-root requests.
            exchangeRootNode(exchanger, input, output);

            // FUTURE WORK: configurable number of tasks
            for (int i = 0; i < 16; i++) {
                workGroup.fork("reconnect-learner-receiver", new LearnerPullVirtualTreeReceiveTask(input, exchanger));
            }

            // FUTURE WORK: configurable number of tasks
            final int learnerSendTasks = 16;
            final CountDownLatch sendTasksDone = new CountDownLatch(learnerSendTasks);
            for (int i = 0; i < learnerSendTasks; i++) {
                workGroup.fork(
                        "reconnect-learner-sender",
                        new LearnerPullVirtualTreeSendTask(output, exchanger, sendTasksDone));
            }

            // when all send tasks done, output can be closed, which signals the teacher that no more requests will be
            // sent.
            // This allows the teacher to complete and close its input stream, which allows the receive tasks to finish.
            try {
                sendTasksDone.await();
            } catch (final InterruptedException ie) {
                Thread.currentThread().interrupt();
            } finally {
                output.done(); // always signal the peer, even on interrupt
            }

            workGroup.join();
        } catch (final InterruptedException ie) {
            Thread.currentThread().interrupt();
            exchanger.abortOnException();
            throw ie;
        } catch (final Throwable t) {
            logger.info(RECONNECT.getMarker(), "Caught exception while receiving tree", t);
            exchanger.abortOnException();
            throwCause(t);
        }

        try {
            VirtualMap syncedVirtualMap = exchanger.onSuccessfulComplete();
            logger.info(RECONNECT.getMarker(), "learner is done synchronizing");
            logger.info(RECONNECT.getMarker(), syncMetrics::toString);
            return syncedVirtualMap;
        } catch (final Throwable t) {
            logger.info(RECONNECT.getMarker(), "Caught exception while completing synchronization", t);
            exchanger.abortOnException();
            throw new MerkleSynchronizationException("Failed to finish synchronization", t);
        }
    }

    private void throwCause(Throwable ex) throws MerkleSynchronizationException {
        if (ex instanceof MerkleSynchronizationException) {
            throw (MerkleSynchronizationException) ex;
        } else if (ex.getCause() instanceof MerkleSynchronizationException) {
            throw (MerkleSynchronizationException) ex.getCause();
        } else {
            throw new MerkleSynchronizationException("Synchronization failed with exceptions", ex);
        }
    }

    protected StandardWorkGroup createStandardWorkGroup(ThreadManager threadManager, Runnable breakConnection) {
        return new StandardWorkGroup(threadManager, WORK_GROUP_NAME, breakConnection);
    }

    /**
     * Build the output stream. Exposed to allow unit tests to override implementation to simulate latency.
     */
    protected AsyncOutputStream buildOutputStream(
            @NonNull final DataOutputStream out, @NonNull final VirtualMapSyncConfig syncConfig) {
        return new AsyncOutputStream(
                out,
                syncConfig.asyncStreamBufferSize(),
                syncConfig.asyncOutputStreamFlush(),
                syncConfig.asyncStreamTimeout());
    }

    /**
     * Synchronously sends the root node request to the teacher, waits for the root response, and
     * initializes the traversal order and learner state from the response. This must complete
     * before any parallel tasks are forked, because all subsequent requests depend on the leaf
     * path range carried in the root response.
     *
     * @param exchanger learner view
     * @param in  the async input stream to read the root response from
     * @param out the async output stream to send the root request to
     * @throws MerkleSynchronizationException if the exchange fails, times out, or is interrupted
     */
    private void exchangeRootNode(
            LearnerTreeExchanger exchanger, final AsyncInputStream in, final AsyncOutputStream out) {
        logger.info(RECONNECT.getMarker(), "Learner sending root node request to teacher");
        final PullVirtualTreeRequest rootRequest = new PullVirtualTreeRequest(Path.ROOT_PATH, new Hash());
        final byte[] rootRequestBytes = new byte[rootRequest.getSizeInBytes()];
        rootRequest.writeTo(BufferedData.wrap(rootRequestBytes));
        try {
            out.sendAsync(rootRequestBytes);
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new MerkleSynchronizationException("Interrupted while sending root node request", e);
        }
        exchanger.onRequestSend();

        // wait for response
        final byte[] rootResponseBytes = in.readOrWait(YieldStrategy.PARK);
        if (rootResponseBytes == null) {
            throw new MerkleSynchronizationException("Stream closed before root node response was received");
        }
        final PullVirtualTreeResponse rootResponse =
                PullVirtualTreeResponse.parseFrom(BufferedData.wrap(rootResponseBytes));
        if (rootResponse.path() != Path.ROOT_PATH) {
            throw new MerkleSynchronizationException(
                    "Expected root node response, but received response for path " + rootResponse.path());
        }
        logger.info(RECONNECT.getMarker(), "Root node response received from teacher");

        exchanger.init(rootResponse);
    }
}
