// SPDX-License-Identifier: Apache-2.0
package com.swirlds.virtualmap.sync;

import com.swirlds.virtualmap.MerklePathUtils;
import com.swirlds.virtualmap.VirtualMap;
import com.swirlds.virtualmap.VirtualMapLearner;
import com.swirlds.virtualmap.datasource.VirtualLeafBytes;
import com.swirlds.virtualmap.internal.reconnect.NodeTraversalOrder;
import com.swirlds.virtualmap.internal.reconnect.PullVirtualTreeResponse;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Map;
import java.util.Objects;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.LockSupport;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hiero.base.crypto.Cryptography;
import org.hiero.base.crypto.Hash;

/**
 * Class for learner handle merkle tree node exchanges.
 * <p>
 * It uses {@link NodeTraversalOrder} to provide next path to request from teacher via {@link #getNextPathToSend()}.
 * Responses from teacher should be handled via {@link #responseReceived(PullVirtualTreeResponse)}.
 */
public final class LearnerTreeExchanger {

    private static final Logger logger = LogManager.getLogger(LearnerTreeExchanger.class);

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

    private VirtualMap.Metadata teacherMetadata = new VirtualMap.Metadata();

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
        teacherMetadata = new VirtualMap.Metadata(firstLeafPath, lastLeafPath);

        traversalOrder.start(
                vmapLearner.getOriginalMetadata().getFirstLeafPath(),
                vmapLearner.getOriginalMetadata().getLastLeafPath(),
                firstLeafPath,
                lastLeafPath);
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
     * Determines if a given path refers to a leaf of the teacher tree.
     *
     * @param path a path
     * @return true if a leaf path, false if internal node
     */
    public boolean isLeafOnTeacher(long path) {
        return teacherMetadata.isLeaf(path);
    }

    // This method is called concurrently from multiple threads
    public long getNextPathToSend() {
        // If the last leaf path request has been sent, don't send anything else
        if (lastLeafSent.get()) {
            return MerklePathUtils.INVALID_PATH;
        }
        final long intPath = traversalOrder.getNextInternalPathToSend();
        if (intPath != MerklePathUtils.INVALID_PATH) {
            assert (intPath < 0) || !isLeafOnTeacher(intPath);
            return intPath;
        }
        synchronized (this) {
            // If the last leaf path is sent, all subsequent calls to getNextPathToSend()
            // are expected to return INVALID_PATH, so there is no need to check
            // lastLeafPath.get() here again
            final long leafPath = traversalOrder.getNextLeafPathToSend();
            if (leafPath == MerklePathUtils.INVALID_PATH) {
                lastLeafSent.set(true);
            } else {
                assert (leafPath < 0) || isLeafOnTeacher(leafPath);
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
        if (!isLeafOnTeacher(responsePath)) {
            handleResponse(response);
        } else {
            // Eager, order-independent store: stale-key tracking + leaf store, done in parallel on this
            // receiver the moment the leaf arrives. Only the ordered hash-feed (dirtyLeafReceived, on the
            // applier) is deferred. nodeReceived is a no-op for leaf paths, so it is not needed here.
            if (!response.isClean() && teacherMetadata.getLastLeafPath() > 0) {
                final VirtualLeafBytes<?> leaf = response.leafData();
                assert leaf != null && leaf.path() == responsePath;
                vmapLearner.storeDirtyLeaf(leaf);
            }
            // The ordered hash-feed is performed by the dedicated applier thread (see applierLoop),
            // which drains this map in anticipatedLeafPaths FIFO order. Receiver threads stay free to
            // keep draining the socket instead of blocking on a long contiguous-backlog drain.
            responses.put(responsePath, response);
        }
    }

    /**
     * Dedicated, single-threaded loop that feeds leaf responses to the hasher in
     * {@link #anticipatedLeafPaths} FIFO order. Exactly one thread (this one) ever drains the FIFO,
     * so leaf apply remains single-threaded — identical to the previous inline behaviour, just
     * relocated off the receiver threads so a long contiguous-backlog drain can no longer block a
     * receiver from reading the socket (which would stall the TCP window and throttle the teacher).
     *
     * @param receiveTasksDone latch counted down as each receiver task finishes; when it reaches zero
     *                         no further responses will arrive, so once the FIFO is drained the loop exits
     */
    void applierLoop(final CountDownLatch receiveTasksDone) {
        // Phase 1 — steady state. Drain the FIFO head whenever its response has been published,
        // otherwise spin. Runs until either the work group interrupts us (a sibling task failed) or
        // every receiver has finished (latch zero) after the last leaf request was sent. The latter is
        // the terminal condition: each receiver publishes into `responses` before counting the latch
        // down, so once the count is zero every response that will ever arrive is already in the map.
        while (!Thread.currentThread().isInterrupted() && (!lastLeafSent.get() || receiveTasksDone.getCount() != 0)) {
            final Long head = anticipatedLeafPaths.peek();
            final PullVirtualTreeResponse r = (head == null) ? null : responses.remove(head);
            if (r == null) {
                LockSupport.parkNanos(50_000L); // head not yet received; nothing to drain this pass
                continue;
            }
            handleResponse(r);
            anticipatedLeafPaths.remove();
        }

        // If we exited because of an interrupt (sibling failure), the work group is tearing down —
        // do not drain; just return and let it complete.
        if (Thread.currentThread().isInterrupted()) {
            return;
        }

        // Phase 2 — drain the tail. The terminal condition held on exit, so both maps are frozen: no
        // receiver can publish again. Every remaining anticipated path must therefore already have its
        // response in the map. A missing one is a lost response / protocol violation — fail the
        // reconnect rather than leave a leaf unsupplied (a silently corrupt tree).
        for (Long path = anticipatedLeafPaths.poll(); path != null; path = anticipatedLeafPaths.poll()) {
            final PullVirtualTreeResponse r = responses.remove(path);
            if (r == null) {
                throw new MerkleSynchronizationException(
                        "Reconnect ended with an undrained leaf path " + path + " whose response was never received");
            }
            handleResponse(r);
        }
    }

    private void handleResponse(final PullVirtualTreeResponse response) {
        // Root node was exchanged synchronously in exchangeRootNode() before any tasks started,
        // so by the time this is called from parallel tasks the root has already been processed.
        final long path = response.path();
        if (teacherMetadata.getLastLeafPath() <= 0) {
            return;
        }
        final boolean isClean = response.isClean();
        final boolean isLeaf = isLeafOnTeacher(path);
        traversalOrder.nodeReceived(path, isClean);
        stats.incrementTransfersFromTeacher();

        if (isLeaf) {
            if (!isClean) {
                final VirtualLeafBytes<?> leaf = response.leafData();
                assert leaf != null;
                assert path == leaf.path();
                // The store (stale-key tracking + updateLeaf) already ran eagerly in responseReceived.
                // Here, on the applier in FIFO order, do only the ordered hash-feed. May block if
                // hashing is slower than ingest.
                vmapLearner.dirtyLeafReceived(leaf);
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
        if (originalNodePath > vmapLearner.getOriginalMetadata().getLastLeafPath()) {
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
