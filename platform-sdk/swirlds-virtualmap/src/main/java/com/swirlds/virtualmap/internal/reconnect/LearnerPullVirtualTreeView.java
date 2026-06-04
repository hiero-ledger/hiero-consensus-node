// SPDX-License-Identifier: Apache-2.0
package com.swirlds.virtualmap.internal.reconnect;

import static com.swirlds.logging.legacy.LogMarker.RECONNECT;

import com.hedera.pbj.runtime.io.buffer.BufferedData;
import com.swirlds.virtualmap.VirtualMapLearner;
import com.swirlds.virtualmap.datasource.VirtualLeafBytes;
import com.swirlds.virtualmap.internal.Path;
import com.swirlds.virtualmap.internal.merkle.VirtualMapMetadata;
import com.swirlds.virtualmap.sync.LearnerTreeView;
import com.swirlds.virtualmap.sync.MerkleSynchronizationException;
import com.swirlds.virtualmap.sync.stats.ReconnectMapStats;
import com.swirlds.virtualmap.sync.streams.AsyncInputStream;
import com.swirlds.virtualmap.sync.streams.AsyncOutputStream;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Map;
import java.util.Objects;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hiero.base.crypto.Cryptography;
import org.hiero.base.crypto.Hash;
import org.hiero.consensus.concurrent.pool.StandardWorkGroup;
import org.hiero.consensus.reconnect.config.ReconnectConfig;

/**
 * An implementation of {@link LearnerTreeView} for the virtual merkle. The learner during reconnect
 * needs access both to the original state and records, and the current reconnect state and records.
 * This implementation uses {@link Long} as the representation of a node and corresponds directly
 * to the path of the node.
 *
 * <p>This implementation is supposed to work with {@link TeacherPullVirtualTreeView} on the
 * teacher side.
 */
public final class LearnerPullVirtualTreeView implements LearnerTreeView {

    // Diagnostic: number of handleResponse() calls performed by each receiver thread.
    // Because responseReceived drains the in-order backlog, the thread that fills a queue
    // head performs handleResponse for all contiguous already-arrived responses — so this
    // count is UNEQUAL across threads even when received-response counts are equal.
    private final ConcurrentHashMap<String, LongAdder> handleResponseCounts = new ConcurrentHashMap<>();

    private static final Logger logger = LogManager.getLogger(LearnerPullVirtualTreeView.class);

    /**
     * The state representing the original, unmodified tree on the learner. For simplicity, on the teacher,
     * this is the same as {@link #reconnectState}. For the learner, it is the state of the detached, unmodified
     * tree.
     */
    private final VirtualMapMetadata originalState;

    /**
     * The state representing the tree being reconnected. For the teacher, this corresponds to the saved state.
     * For the learner, this is the state of the tree being serialized into.
     */
    private final VirtualMapMetadata reconnectState;

    /**
     * Reconnect configuration.
     */
    private final ReconnectConfig reconnectConfig;

    /**
     * The reconnect helper that manages hashing and lifecycle for this learner reconnect operation.
     */
    private final VirtualMapLearner vmapLearner;

    /**
     * Node traversal order. Defines the order in which node requests will be sent to the teacher.
     */
    private final NodeTraversalOrder traversalOrder;

    private final ReconnectMapStats mapStats;

    /**
     * Responses from teacher may come in a different order than they are sent by learner. The order
     * is important for hashing, so it's restored using this queue. Once hashing is improved to work
     * with unsorted dirty leaves stream, this code may be cleaned up.
     */
    private final Queue<Long> anticipatedLeafPaths = new ConcurrentLinkedDeque<>();

    /**
     * Related to the queue above. If a response is received out of order, it's temporarily stored
     * in this map.
     */
    private final Map<Long, PullVirtualTreeResponse> responses = new ConcurrentHashMap<>();

    private final AtomicBoolean lastLeafSent = new AtomicBoolean(false);

    /**
     * Create a new {@link LearnerPullVirtualTreeView}.
     *
     * @param reconnectConfig
     *      the reconnect configuration
     * @param vmapLearner
     * 		The reconnect helper managing this learner reconnect operation. Cannot be null.
     * @param traversalOrder
     *      the traversal order defining which paths to request
     * @param mapStats
     *      a ReconnectMapStats object to collect reconnect metrics
     */
    public LearnerPullVirtualTreeView(
            @NonNull final ReconnectConfig reconnectConfig,
            @NonNull final VirtualMapLearner vmapLearner,
            @NonNull final NodeTraversalOrder traversalOrder,
            @NonNull final ReconnectMapStats mapStats) {
        this.vmapLearner = Objects.requireNonNull(vmapLearner, "vmapLearner is null");
        this.originalState = vmapLearner.getOriginalState();
        this.reconnectState = vmapLearner.getReconnectState();
        this.reconnectConfig = Objects.requireNonNull(reconnectConfig, "reconnectConfig is null");
        this.traversalOrder = Objects.requireNonNull(traversalOrder, "traversalOrder is null");
        this.mapStats = Objects.requireNonNull(mapStats, "mapStats is null");
    }

    /** {@inheritDoc} */
    @Override
    public void startLearnerTasks(
            final StandardWorkGroup workGroup, final AsyncInputStream in, final AsyncOutputStream out) {
        // Perform the root-node (path 0) request/response handshake synchronously before forking
        // any parallel tasks. The root response carries the teacher's first/last leaf path range,
        // which must be known before the traversal order can be started and before any parallel
        // send tasks can generate meaningful non-root requests.
        exchangeRootNode(in, out);

        final AtomicLong expectedResponses = new AtomicLong(0);
        // FUTURE WORK: configurable number of tasks
        for (int i = 0; i < 16; i++) {
            final LearnerPullVirtualTreeReceiveTask learnerReceiveTask =
                    new LearnerPullVirtualTreeReceiveTask(reconnectConfig, workGroup, in, this, expectedResponses);
            learnerReceiveTask.exec();
        }

        // FUTURE WORK: configurable number of tasks
        final int learnerSendTasks = 16;
        final AtomicInteger tasksDone = new AtomicInteger(learnerSendTasks);
        for (int i = 0; i < learnerSendTasks; i++) {
            final LearnerPullVirtualTreeSendTask learnerSendTask =
                    new LearnerPullVirtualTreeSendTask(workGroup, out, this, expectedResponses, tasksDone);
            learnerSendTask.exec();
        }
    }

    /**
     * Synchronously sends the root node request to the teacher, waits for the root response, and
     * initializes the traversal order and learner state from the response. This must complete
     * before any parallel tasks are forked, because all subsequent requests depend on the leaf
     * path range carried in the root response.
     *
     * @param in  the async input stream to read the root response from
     * @param out the async output stream to send the root request to
     * @throws MerkleSynchronizationException if the exchange fails, times out, or is interrupted
     */
    private void exchangeRootNode(final AsyncInputStream in, final AsyncOutputStream out) {
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
        mapStats.incrementTransfersFromLearner();

        // wait for response
        final byte[] rootResponseBytes =
                in.readAnticipatedMessageSync(reconnectConfig.pullLearnerRootResponseTimeout());
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

        // init with teacher key range
        final long firstLeafPath = rootResponse.firstLeafPath();
        final long lastLeafPath = rootResponse.lastLeafPath();
        traversalOrder.start(
                originalState.getFirstLeafPath(), originalState.getLastLeafPath(), firstLeafPath, lastLeafPath);
        vmapLearner.init(firstLeafPath, lastLeafPath);
        handleResponse(rootResponse);
    }

    @Override
    public void onSuccessfulComplete() {
        handleResponseCounts.forEach((thread, count) ->
                logger.info(RECONNECT.getMarker(), "handleResponse count: thread={} count={}", thread, count.sum()));
        vmapLearner.finish();
    }

    /**
     * Determines if a given path refers to a leaf of the tree.
     *
     * @param path a path
     * @return true if leaf, false if internal
     */
    public boolean isLeaf(long path) {
        assert path <= reconnectState.getLastLeafPath();
        return path >= reconnectState.getFirstLeafPath();
    }

    // This method is called concurrently from multiple threads
    long getNextPathToSend() {
        // If the last leaf path request has been sent, don't send anything else
        if (lastLeafSent.get()) {
            return Path.INVALID_PATH;
        }
        final long intPath = traversalOrder.getNextInternalPathToSend();
        if (intPath != Path.INVALID_PATH) {
            assert (intPath < 0) || !isLeaf(intPath);
            return intPath;
        }
        synchronized (this) {
            // If the last leaf path is sent, all subsequent calls to getNextPathToSend()
            // are expected to return INVALID_PATH, so there is no need to check
            // lastLeafPath.get() here again
            final long leafPath = traversalOrder.getNextLeafPathToSend();
            if (leafPath == Path.INVALID_PATH) {
                lastLeafSent.set(true);
            } else {
                assert (leafPath < 0) || isLeaf(leafPath);
                if (leafPath > 0) {
                    anticipatedLeafPaths.add(leafPath);
                }
            }
            return leafPath;
        }
    }

    // This method is called concurrently from multiple threads and called for non-root nodes (internal and leaves)
    void responseReceived(final PullVirtualTreeResponse response) {
        final long responsePath = response.path();
        if (!isLeaf(responsePath)) {
            handleResponse(response);
            handleResponseCounts
                    .computeIfAbsent(Thread.currentThread().getName(), k -> new LongAdder())
                    .increment();
            mapStats.incrementInternalHashes(1, response.isClean() ? 1 : 0);
        } else {
            responses.put(responsePath, response);
            // Handle responses in the same order as the corresponding requests were sent to the teacher
            while (true) {
                final Long nextExpectedPath = anticipatedLeafPaths.peek();
                if (nextExpectedPath == null) {
                    break;
                }
                final PullVirtualTreeResponse r = responses.remove(nextExpectedPath);
                if (r == null) {
                    break;
                }
                handleResponse(r);
                handleResponseCounts
                        .computeIfAbsent(
                                Thread.currentThread().getName(), k -> new java.util.concurrent.atomic.LongAdder())
                        .increment();
                anticipatedLeafPaths.remove();
            }
            mapStats.incrementLeafHashes(1, response.isClean() ? 1 : 0);
        }
    }

    private void handleResponse(final PullVirtualTreeResponse response) {
        // Root node was exchanged synchronously in exchangeRootNode() before any tasks started,
        // so by the time this is called from parallel tasks the root has already been processed.
        final long path = response.path();
        if (reconnectState.getLastLeafPath() <= 0) {
            return;
        }
        final boolean isClean = response.isClean();
        final boolean isLeaf = isLeaf(path);
        traversalOrder.nodeReceived(path, isClean);
        mapStats.incrementTransfersFromTeacher();

        if (isLeaf) {
            if (!isClean) {
                final VirtualLeafBytes<?> leaf = response.leafData();
                assert leaf != null;
                assert path == leaf.path();
                vmapLearner.onDirtyLeaf(leaf); // may block if hashing is slower than ingest
            }
            mapStats.incrementLeafData(1, isClean ? 1 : 0);
        } else {
            mapStats.incrementInternalData(1, isClean ? 1 : 0);
        }
    }

    /**
     * Returns the ReconnectMapStats object.
     *
     * @return the ReconnectMapStats object
     */
    @NonNull
    public ReconnectMapStats getMapStats() {
        return mapStats;
    }

    /**
     * Get the hash of a node. If this view represents a tree that has null nodes within it, those nodes should cause
     * this method to return a {@link Cryptography#NULL_HASH null hash}.
     *
     * @param originalNodePath the original node path
     * @return the hash of the node
     */
    public Hash getNodeHash(final Long originalNodePath) {
        // The path given is the _ORIGINAL_ node. Each call to this
        // method will be made only for the original state from the original tree.

        // Make sure the path is valid for the original state
        if (originalNodePath > originalState.getLastLeafPath()) {
            return Cryptography.NULL_HASH;
        }

        final Hash hash = vmapLearner.findHash(originalNodePath);
        // The hash must have been specified by this point. The original tree was hashed before
        // we started running on the learner, so either the hash is in cache or on disk, but it
        // definitely exists at this point. If it is null, something bad happened elsewhere.
        if (hash == null) {
            throw new MerkleSynchronizationException("Node found, but hash was null. path=" + originalNodePath);
        }
        return hash;
    }
}
