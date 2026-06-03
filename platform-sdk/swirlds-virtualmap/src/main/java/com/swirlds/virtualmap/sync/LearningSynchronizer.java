// SPDX-License-Identifier: Apache-2.0
package com.swirlds.virtualmap.sync;

import static com.swirlds.logging.legacy.LogMarker.RECONNECT;

import com.hedera.pbj.runtime.io.buffer.BufferedData;
import com.swirlds.metrics.api.Metrics;
import com.swirlds.virtualmap.VirtualMap;
import com.swirlds.virtualmap.VirtualMapLearner;
import com.swirlds.virtualmap.config.VirtualMapConfig;
import com.swirlds.virtualmap.config.VirtualMapReconnectMode;
import com.swirlds.virtualmap.internal.Path;
import com.swirlds.virtualmap.internal.reconnect.LearnerPullVirtualTreeReceiveTask;
import com.swirlds.virtualmap.internal.reconnect.LearnerPullVirtualTreeSendTask;
import com.swirlds.virtualmap.internal.reconnect.ParallelSyncTraversalOrder;
import com.swirlds.virtualmap.internal.reconnect.PullVirtualTreeRequest;
import com.swirlds.virtualmap.internal.reconnect.PullVirtualTreeResponse;
import com.swirlds.virtualmap.internal.reconnect.TopToBottomTraversalOrder;
import com.swirlds.virtualmap.internal.reconnect.TwoPhasePessimisticTraversalOrder;
import com.swirlds.virtualmap.sync.stats.ReconnectMapMetrics;
import com.swirlds.virtualmap.sync.stats.ReconnectMapStats;
import com.swirlds.virtualmap.sync.streams.AsyncInputStream;
import com.swirlds.virtualmap.sync.streams.AsyncOutputStream;
import com.swirlds.virtualmap.sync.streams.YieldStrategy;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hiero.base.crypto.Hash;
import org.hiero.consensus.concurrent.manager.ThreadManager;
import org.hiero.consensus.concurrent.pool.StandardWorkGroup;
import org.hiero.consensus.reconnect.config.ReconnectConfig;

/**
 * Performs reconnect in the role of the learner.
 */
public class LearningSynchronizer {

    private static final Logger logger = LogManager.getLogger(LearningSynchronizer.class);

    private static final String WORK_GROUP_NAME = "learning-synchronizer";

    private final ThreadManager threadManager;
    private final ReconnectConfig reconnectConfig;
    private final Metrics metrics;

    /**
     * Constructs a new learning synchronizer.
     *
     * @param threadManager responsible for managing thread lifecycles
     * @param reconnectConfig the reconnect configuration
     * @param metrics the metrics system for recording synchronization metrics
     */
    public LearningSynchronizer(
            @NonNull final ThreadManager threadManager,
            @NonNull final ReconnectConfig reconnectConfig,
            @NonNull final Metrics metrics) {

        this.threadManager = Objects.requireNonNull(threadManager, "threadManager cannot be null");
        this.reconnectConfig = Objects.requireNonNull(reconnectConfig, "reconnectConfig cannot be null");
        this.metrics = Objects.requireNonNull(metrics, "metrics cannot be null");
    }

    @NonNull
    private LearnerTreeView buildLearnerView(
            VirtualMap originalVirtualMap, ReconnectConfig reconnectConfig, ReconnectMapStats mapStats) {
        logger.info(
                RECONNECT.getMarker(),
                "Building learner view for map with path range [{}, {}]",
                originalVirtualMap.getMetadata().getFirstLeafPath(),
                originalVirtualMap.getMetadata().getLastLeafPath());

        final VirtualMapConfig virtualMapConfig = originalVirtualMap.getVirtualMapConfig();
        final VirtualMapLearner vmapLearner = new VirtualMapLearner(originalVirtualMap, reconnectConfig, mapStats);

        return switch (virtualMapConfig.reconnectMode()) {
            case VirtualMapReconnectMode.PULL_TOP_TO_BOTTOM ->
                new LearnerTreeView(vmapLearner, new TopToBottomTraversalOrder(), mapStats);
            case VirtualMapReconnectMode.PULL_TWO_PHASE_PESSIMISTIC ->
                new LearnerTreeView(vmapLearner, new TwoPhasePessimisticTraversalOrder(), mapStats);
            case VirtualMapReconnectMode.PULL_PARALLEL_SYNC ->
                new LearnerTreeView(vmapLearner, new ParallelSyncTraversalOrder(), mapStats);
            default ->
                throw new UnsupportedOperationException("Unknown reconnect mode: "
                        + virtualMapConfig.reconnectMode()
                        + ". Supported modes: PULL_TOP_TO_BOTTOM,"
                        + " PULL_TWO_PHASE_PESSIMISTIC, PULL_PARALLEL_SYNC");
        };
    }

    /**
     * Perform synchronization in the role of the learner.
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

        final AtomicReference<Throwable> firstReconnectException = new AtomicReference<>();
        final ReconnectMapMetrics reconnectStats = new ReconnectMapMetrics(metrics, null, null);
        final LearnerTreeView view = buildLearnerView(originalMap, reconnectConfig, reconnectStats);

        final Function<Throwable, Boolean> reconnectExceptionListener = ex -> {
            firstReconnectException.compareAndSet(null, ex);
            return false;
        };
        final StandardWorkGroup workGroup =
                createStandardWorkGroup(threadManager, breakConnection, reconnectExceptionListener);

        logger.info(RECONNECT.getMarker(), "learner start synchronizing");

        final AsyncInputStream input = new AsyncInputStream(
                in, workGroup, reconnectConfig.asyncStreamBufferSize(), reconnectConfig.asyncStreamTimeout());
        input.start();
        final AsyncOutputStream output = buildOutputStream(workGroup, out, reconnectConfig);
        output.start();

        try {
            // Perform the root-node (path 0) request/response handshake synchronously before forking
            // any parallel tasks. The root response carries the teacher's first/last leaf path range,
            // which must be known before the traversal order can be started and before any parallel
            // send tasks can generate meaningful non-root requests.
            exchangeRootNode(view, input, output);

            final AtomicLong expectedResponses = new AtomicLong(0);
            // FUTURE WORK: configurable number of tasks
            for (int i = 0; i < 16; i++) {
                final LearnerPullVirtualTreeReceiveTask learnerReceiveTask = new LearnerPullVirtualTreeReceiveTask(
                        reconnectConfig, workGroup, input, view, expectedResponses);
                learnerReceiveTask.exec();
            }

            // FUTURE WORK: configurable number of tasks
            final int learnerSendTasks = 16;
            final AtomicInteger tasksDone = new AtomicInteger(learnerSendTasks);
            for (int i = 0; i < learnerSendTasks; i++) {
                final LearnerPullVirtualTreeSendTask learnerSendTask =
                        new LearnerPullVirtualTreeSendTask(workGroup, output, view, expectedResponses, tasksDone);
                learnerSendTask.exec();
            }

            workGroup.waitForTermination();
        } catch (final InterruptedException ie) {
            Thread.currentThread().interrupt();
            view.abortOnException();
            throw ie;
        } catch (final Throwable t) {
            logger.info(RECONNECT.getMarker(), "Caught exception while receiving tree", t);
            view.abortOnException();
            throw new RuntimeException(t);
        }

        if (workGroup.hasExceptions()) {
            view.abortOnException();
            throw new MerkleSynchronizationException(
                    "Synchronization failed with exceptions", firstReconnectException.get());
        } else {
            logger.info(RECONNECT.getMarker(), "learner is done synchronizing");
            logger.info(RECONNECT.getMarker(), reconnectStats::format);
            return view.onSuccessfulComplete();
        }
    }

    protected StandardWorkGroup createStandardWorkGroup(
            ThreadManager threadManager,
            Runnable breakConnection,
            Function<Throwable, Boolean> reconnectExceptionListener) {
        return new StandardWorkGroup(threadManager, WORK_GROUP_NAME, breakConnection, reconnectExceptionListener);
    }

    /**
     * Build the output stream. Exposed to allow unit tests to override implementation to simulate latency.
     */
    protected AsyncOutputStream buildOutputStream(
            @NonNull final StandardWorkGroup workGroup,
            @NonNull final DataOutputStream out,
            @NonNull final ReconnectConfig reconnectConfig) {
        return new AsyncOutputStream(
                out,
                workGroup,
                reconnectConfig.asyncStreamBufferSize(),
                reconnectConfig.asyncOutputStreamFlush(),
                reconnectConfig.asyncStreamTimeout());
    }

    /**
     * Synchronously sends the root node request to the teacher, waits for the root response, and
     * initializes the traversal order and learner state from the response. This must complete
     * before any parallel tasks are forked, because all subsequent requests depend on the leaf
     * path range carried in the root response.
     *
     * @param view learner view
     * @param in  the async input stream to read the root response from
     * @param out the async output stream to send the root request to
     * @throws MerkleSynchronizationException if the exchange fails, times out, or is interrupted
     */
    private void exchangeRootNode(LearnerTreeView view, final AsyncInputStream in, final AsyncOutputStream out) {
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
        view.getMapStats().incrementTransfersFromLearner();

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

        view.init(rootResponse);
    }
}
