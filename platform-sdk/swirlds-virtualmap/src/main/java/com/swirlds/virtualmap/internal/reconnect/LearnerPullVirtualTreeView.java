// SPDX-License-Identifier: Apache-2.0
package com.swirlds.virtualmap.internal.reconnect;

import static com.swirlds.logging.legacy.LogMarker.RECONNECT;

import com.swirlds.common.merkle.synchronization.stats.ReconnectMapStats;
import com.swirlds.common.merkle.synchronization.streams.AsyncInputStream;
import com.swirlds.common.merkle.synchronization.streams.AsyncOutputStream;
import com.swirlds.common.merkle.synchronization.utility.MerkleSynchronizationException;
import com.swirlds.common.merkle.synchronization.views.LearnerTreeView;
import com.swirlds.virtualmap.VirtualMap;
import com.swirlds.virtualmap.datasource.VirtualLeafBytes;
import com.swirlds.virtualmap.internal.Path;
import com.swirlds.virtualmap.internal.RecordAccessor;
import com.swirlds.virtualmap.internal.merkle.VirtualMapMetadata;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Map;
import java.util.Objects;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
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

    private static final Logger logger = LogManager.getLogger(LearnerPullVirtualTreeView.class);

    /**
     * The root node that is involved in reconnect. This would be the saved state for the teacher, and
     * the new root node into which things are being serialized for the learner.
     */
    private final VirtualMap map;

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
     * Handles removal of old nodes.
     */
    private final ReconnectNodeRemover nodeRemover;

    /**
     * A {@link RecordAccessor} for getting access to the original records.
     */
    private final RecordAccessor originalRecords;

    /**
     * Node traversal order. Defines the order in which node requests will be sent to the teacher.
     */
    private final NodeTraversalOrder traversalOrder;

    private final ReconnectMapStats mapStats;

    // Indicates if a response for path 0 (virtual root) has been received
    private final CountDownLatch rootResponseReceived = new CountDownLatch(1);

    /**
     * Indicates if no responses from the teacher have been received yet. The very first response
     * must be for path 0 (root virtual node). Used in assertions only.
     */
    private final AtomicBoolean firstNodeResponse = new AtomicBoolean(true);

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
     * @param map
     * 		The map node of the <strong>reconnect</strong> tree. Cannot be null.
     * @param originalRecords
     * 		A {@link RecordAccessor} for accessing records from the unmodified <strong>original</strong> tree.
     * 		Cannot be null.
     * @param originalState
     * 		A {@link VirtualMapMetadata} for accessing state (first and last paths) from the
     * 		unmodified <strong>original</strong> tree. Cannot be null.
     * @param reconnectState
     * 		A {@link VirtualMapMetadata} for accessing state (first and last paths) from the
     * 		modified <strong>reconnect</strong> tree. We only use first and last leaf path from this state.
     * 		Cannot be null.
     * @param nodeRemover
     *      handles removal of old nodes
     * @param traversalOrder
     *      the traversal order defining which paths to request
     * @param mapStats
     *      a ReconnectMapStats object to collect reconnect metrics
     */
    public LearnerPullVirtualTreeView(
            @NonNull final ReconnectConfig reconnectConfig,
            @NonNull final VirtualMap map,
            @NonNull final RecordAccessor originalRecords,
            @NonNull final VirtualMapMetadata originalState,
            @NonNull final VirtualMapMetadata reconnectState,
            @NonNull final ReconnectNodeRemover nodeRemover,
            @NonNull final NodeTraversalOrder traversalOrder,
            @NonNull final ReconnectMapStats mapStats) {
        this.map = Objects.requireNonNull(map);
        this.originalState = Objects.requireNonNull(originalState);
        this.reconnectState = Objects.requireNonNull(reconnectState);
        this.reconnectConfig = reconnectConfig;
        this.originalRecords = Objects.requireNonNull(originalRecords);
        this.nodeRemover = nodeRemover;
        this.traversalOrder = traversalOrder;
        this.mapStats = mapStats;
    }

    /** {@inheritDoc} */
    @Override
    public void startLearnerTasks(
            final StandardWorkGroup workGroup,
            final AsyncInputStream in,
            final AsyncOutputStream out,
            final Runnable completeListener) {
        final AtomicLong expectedResponses = new AtomicLong(0);
        // FUTURE WORK: configurable number of tasks
        for (int i = 0; i < 16; i++) {
            final LearnerPullVirtualTreeReceiveTask learnerReceiveTask = new LearnerPullVirtualTreeReceiveTask(
                    reconnectConfig, workGroup, in, this, expectedResponses, completeListener);
            learnerReceiveTask.exec();
        }

        final AtomicBoolean rootRequestSent = new AtomicBoolean(false);
        // FUTURE WORK: configurable number of tasks
        final int learnerSendTasks = 16;
        final AtomicInteger tasksDone = new AtomicInteger(learnerSendTasks);
        for (int i = 0; i < learnerSendTasks; i++) {
            final LearnerPullVirtualTreeSendTask learnerSendTask = new LearnerPullVirtualTreeSendTask(
                    reconnectConfig,
                    workGroup,
                    out,
                    this,
                    rootResponseReceived,
                    expectedResponses,
                    rootRequestSent,
                    tasksDone);
            learnerSendTask.exec();
        }
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

    // This method is called concurrently from multiple threads
    void responseReceived(final PullVirtualTreeResponse response) {
        final long responsePath = response.path();
        if (responsePath == 0) {
            logger.info(RECONNECT.getMarker(), "Root response received from the teacher");
            final long firstLeafPath = response.firstLeafPath();
            final long lastLeafPath = response.lastLeafPath();
            assert firstNodeResponse.compareAndSet(true, false)
                    : "Root node must be the first node received from the teacher";
            reconnectState.setPaths(firstLeafPath, lastLeafPath);
            traversalOrder.start(
                    originalState.getFirstLeafPath(), originalState.getLastLeafPath(), firstLeafPath, lastLeafPath);
            map.prepareReconnectHashing(firstLeafPath, lastLeafPath);
            rootResponseReceived.countDown();
            // setPathInformation() below may take a while
            nodeRemover.setPathInformation(firstLeafPath, lastLeafPath);
        }
        if ((responsePath == 0) || !isLeaf(responsePath)) {
            handleResponse(response);
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
                anticipatedLeafPaths.remove();
            }
        }

        if (responsePath != Path.ROOT_PATH) {
            final boolean isLeaf = isLeaf(responsePath);
            if (isLeaf) {
                mapStats.incrementLeafHashes(1, response.isClean() ? 1 : 0);
            } else {
                mapStats.incrementInternalHashes(1, response.isClean() ? 1 : 0);
            }
        }
    }

    private void handleResponse(final PullVirtualTreeResponse response) {
        assert !firstNodeResponse.get() : "Root node must be the first node received from the teacher";
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
                nodeRemover.newLeafNode(path, leaf.keyBytes());
                map.handleReconnectLeaf(leaf); // may block if hashing is slower than ingest
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
     * {@inheritDoc}
     */
    @Override
    public Hash getNodeHash(final Long originalChild) {
        // The path given is the _ORIGINAL_ child. Each call to this
        // method will be made only for the original state from the original tree.

        // Make sure the path is valid for the original state
        if (originalChild > originalState.getLastLeafPath()) {
            return Cryptography.NULL_HASH;
        }

        final Hash hash = originalRecords.findHash(originalChild);
        // The hash must have been specified by this point. The original tree was hashed before
        // we started running on the learner, so either the hash is in cache or on disk, but it
        // definitely exists at this point. If it is null, something bad happened elsewhere.
        if (hash == null) {
            throw new MerkleSynchronizationException("Node found, but hash was null. path=" + originalChild);
        }
        return hash;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void close() {
        nodeRemover.allNodesReceived();
        map.endLearnerReconnect();
    }
}
