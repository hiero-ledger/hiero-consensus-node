// SPDX-License-Identifier: Apache-2.0
package com.swirlds.virtualmap.sync;

import com.swirlds.virtualmap.VirtualMap;
import com.swirlds.virtualmap.VirtualMapLearner;
import com.swirlds.virtualmap.datasource.VirtualLeafBytes;
import com.swirlds.virtualmap.internal.Path;
import com.swirlds.virtualmap.internal.merkle.VirtualMapMetadata;
import com.swirlds.virtualmap.internal.reconnect.NodeTraversalOrder;
import com.swirlds.virtualmap.internal.reconnect.PullVirtualTreeResponse;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Map;
import java.util.Objects;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicBoolean;
import org.hiero.base.crypto.Cryptography;
import org.hiero.base.crypto.Hash;

/**
 * Class for learner handle merkle tree node exchanges.
 * <p>
 * It uses {@link NodeTraversalOrder} to provide next path to request from teacher via {@link #getNextPathToSend()}.
 * Responses from teacher should be handled via {@link #responseReceived(PullVirtualTreeResponse)}.
 */
public final class LearnerTreeExchanger {

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
     * The reconnect helper that manages hashing and lifecycle for this learner reconnect operation.
     */
    private final VirtualMapLearner vmapLearner;

    /**
     * Node traversal order. Defines the order in which node requests will be sent to the teacher.
     */
    private final NodeTraversalOrder traversalOrder;

    private final LearnerSyncMetrics stats;

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
     * Create a new {@link LearnerTreeExchanger}.
     *
     * @param vmapLearner
     * 		The reconnect helper managing this learner reconnect operation. Cannot be null.
     * @param traversalOrder
     *      the traversal order defining which paths to request
     * @param stats
     *      a ReconnectMapStats object to collect reconnect metrics
     */
    public LearnerTreeExchanger(
            @NonNull final VirtualMapLearner vmapLearner,
            @NonNull final NodeTraversalOrder traversalOrder,
            @NonNull final LearnerSyncMetrics stats) {
        this.vmapLearner = Objects.requireNonNull(vmapLearner, "vmapLearner is null");
        this.originalState = vmapLearner.getOriginalState();
        this.reconnectState = vmapLearner.getReconnectState();
        this.traversalOrder = Objects.requireNonNull(traversalOrder, "traversalOrder is null");
        this.stats = Objects.requireNonNull(stats, "mapStats is null");
    }

    /**
     * Initialize the exchanger with the root response from the teacher.
     * This will initialize the traversal order and the learner with the teacher's leaf key range, and handle the root response.
     *
     * @param rootResponse root information from teacher
     */
    public void init(PullVirtualTreeResponse rootResponse) {
        // init with teacher key range
        final long firstLeafPath = rootResponse.firstLeafPath();
        final long lastLeafPath = rootResponse.lastLeafPath();
        traversalOrder.start(
                originalState.getFirstLeafPath(), originalState.getLastLeafPath(), firstLeafPath, lastLeafPath);
        vmapLearner.init(firstLeafPath, lastLeafPath);
        handleResponse(rootResponse);
    }

    VirtualMap onSuccessfulComplete() {
        return vmapLearner.finish();
    }

    void abortOnException() {
        vmapLearner.abortOnException();
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
    public long getNextPathToSend() {
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

    public void onRequestSend() {
        stats.incrementTransfersFromLearner();
    }

    // This method is called concurrently from multiple threads and called for non-root nodes (internal and leaves)
    public void responseReceived(final PullVirtualTreeResponse response) {
        final long responsePath = response.path();
        if (!isLeaf(responsePath)) {
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
        stats.incrementTransfersFromTeacher();

        if (isLeaf) {
            if (!isClean) {
                final VirtualLeafBytes<?> leaf = response.leafData();
                assert leaf != null;
                assert path == leaf.path();
                vmapLearner.onDirtyLeaf(leaf); // may block if hashing is slower than ingest
            }
            stats.incrementLeafData(isClean);
        } else {
            stats.incrementInternalHashes(isClean);
        }
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
