// SPDX-License-Identifier: Apache-2.0
package com.swirlds.virtualmap.internal.reconnect;

import static com.swirlds.logging.legacy.LogMarker.RECONNECT;
import static com.swirlds.virtualmap.internal.Path.ROOT_PATH;

import com.swirlds.virtualmap.internal.Path;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicLong;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Virtual node traversal policy, which sends requests to the teacher rank by rank,
 * internal nodes first, then leaves. After a request for an internal node is sent,
 * no requests for the node's children (leaves or internals) are sent till the
 * response is received from the teacher. If the response is clean, the node is
 * added to the list of clean nodes, and no more requests in the node's sub-tree
 * are sent. If the response is dirty, node's children are sent to the teacher.
 *
 * <p>There are two edge strategies while sending internal nodes. If the policy is
 * to send child nodes without waiting for responses for parent nodes, the changes
 * are redundant clean nodes will be sent. On the other hand, if a node is only
 * sent after it's parent is confirmed to be dirty by the teacher, it would result
 * in too much waiting, and overall throughput would be low. That's why the current
 * implementation is somewhat in between. For every internal node, this class waits
 * for the teacher to confirm it's clean or dirty. If a node is dirty, its child
 * nodes are sent to the teacher, but not immediate children, but the grand children
 * several ranks lower in the tree.
 *
 * <p>When leaves are processed, the policy is to wait till they have a clean parent
 * anywhere up in the tree (in this case, the leaves will be skipped, no requests to
 * the teacher), or there is a dirty parent node a few ranks above the leaves.
 *
 * <p>Tree processing is done in chunks. First, the chunk root rank is fixed, this is
 * where every chunk's root paths are. Then a chunk containing the first leaf path is
 * processed, both internals and leaves. Then the next chunk, and the next one, till
 * the chunk, which contains the last leaf. After each chunk is processed, the list
 * of accumulated clean nodes is reset, since chunk paths are disjoint. This makes
 * sure the list of clean nodes doesn't grow beyond certain point.
 *
 * <p>One more optimization is effective processing of the beginning or the end of
 * the leaf path range. If the range is [N, 2N] on the learner, and [N+X, 2N+2X] on
 * the teacher (which means, the teacher has X more leaves than the learner), then
 * all leaves in range [2N+1, 2N+2X] are known to be dirty, no need to send any
 * internals in the corresponding sub-tree.
 */
public class TopToBottomTraversalOrder implements NodeTraversalOrder {

    /**
     * The number of ranks to skip, when a response for a dirty node is received from the
     * teacher. This means, all grand children at node's rank + RANK_STEP are sent.
     */
    private static final int RANK_STEP = 3;

    /**
     * Default chunk height. If the tree is large enough, the chunk root rank is the last
     * leaf rank minus DEFAULT_CHUNK_HEIGHT. Chunks that cover leaves at the first leaf
     * rank are one rank smaller. If the tree is less than DEFAULT_CHUNK_HEIGHT ranks,
     * the chunk root rank is 1, and chunk heights are adjusted accordingly.
     */
    private static final int DEFAULT_CHUNK_HEIGHT = 23;

    private static final Logger logger = LogManager.getLogger(TopToBottomTraversalOrder.class);

    /**
     * When the number of nodes is low, it doesn't make sense to use chunks, just send all
     * leaves without any internal nodes.
     */
    private volatile boolean simpleMode = false;

    private volatile long oldFirstLeafPath;
    private volatile long oldLastLeafPath;
    private volatile long firstLeafPath;
    private volatile long lastLeafPath;

    /** The rank of the first leaf path (teacher's leaf path range) */
    private volatile int firstLeafRank;
    /**
     * The rank of the last leaf path (teacher's leaf path range). It may be equal to
     * firstLeafRank or firstLeafRank + 1.
     */
    private volatile int lastLeafRank;

    /** The rank of chunk root nodes */
    private volatile int chunkRootRank;
    /** Node path of the currently processed chunk */
    private volatile long chunkRootPath;
    /** The last rank of the current chunk. It may be either firstLeafRank or lastLeafRank */
    private volatile int chunkLastRank;
    /** The last leaf path in the currently processed chunk */
    private volatile long chunkLastLeafPath;

    /**
     * Clean internal nodes. This set is cleared, when the current chunk is completely
     * processed, and a new chunk is started.
     */
    private final Set<Long> cleanPaths = ConcurrentHashMap.newKeySet();
    /**
     * If a node is dirty, and it's less than RANK_STEP ranks higher than the leaf
     * rank, it's tracked here. This set, together with cleanPaths, is used to
     * evaluate if a leaf node can be processed, or it's pending more responses
     * from the teacher.
     */
    private final Set<Long> someDirtyPaths = ConcurrentHashMap.newKeySet();

    /**
     * A queue of internal nodes to send to the teacher. It's originally populated with
     * some nodes in the first chunk. Afterwards, nodes are added to the queue, when
     * dirty responses from the teacher are processed. The queue is drained by learner's
     * sending thread using {@link #getNextInternalPathToSend()} method. If the queue is
     * empty, no internal nodes are sent, and an attempt to send a leaf is performed.
     */
    private final Queue<Long> internals = new ConcurrentLinkedQueue<>();

    /**
     * Leaf path tracker. Leaf requests are sent to the teacher in ascending path order.
     * When {@link #getNextLeafPathToSend()} is called, it checks a leaf at this current
     * path. If the leaf is clean (has a clean parent), the current path is increased to
     * skip all clean leaves. If the leaf has a dirty parent in someDirtyPaths, it's sent
     * to the teacher. Otherwise, no leaf requests are sent, and this traversal order is
     * queried again later. When this current leaf path reaches lastLeafPath + 1, no more
     * requests are sent to the teacher.
     */
    private final AtomicLong currentLeafPath = new AtomicLong();

    @Override
    public void start(
            final long oldFirstLeafPath,
            final long oldLastLeafPath,
            final long firstLeafPath,
            final long lastLeafPath) {
        this.oldFirstLeafPath = oldFirstLeafPath;
        this.oldLastLeafPath = oldLastLeafPath;
        this.firstLeafPath = firstLeafPath;
        this.lastLeafPath = lastLeafPath;

        currentLeafPath.set(firstLeafPath);

        firstLeafRank = Path.getRank(firstLeafPath);
        lastLeafRank = Path.getRank(lastLeafPath);

        if (firstLeafRank < 10) {
            simpleMode = true;
        } else {
            chunkRootRank = Math.max(1, lastLeafRank - DEFAULT_CHUNK_HEIGHT);
            final long startingLeaf = Math.max(firstLeafPath, oldFirstLeafPath);
            chunkLastRank = Path.getRank(startingLeaf);
            chunkRootPath = Path.getGrandParentPath(startingLeaf, chunkLastRank - chunkRootRank);
            chunkLastLeafPath = Path.getRightGrandChildPath(chunkRootPath, chunkLastRank - chunkRootRank);
            addInitialChunkInternals();

            logger.debug(RECONNECT.getMarker(), "Pull start: chunk root rank = {}", chunkRootRank);
        }
    }

    // chunkRootPath, chunkRootRank, and chunkLastRank must be set before this method is called
    private void addInitialChunkInternals() {
        final int chunkHeight = chunkLastRank - chunkRootRank;
        final int skipRanks = chunkHeight / 2;
        final long firstPath = Path.getLeftGrandChildPath(chunkRootPath, skipRanks);
        final long lastPath = Path.getRightGrandChildPath(chunkRootPath, skipRanks);
        for (long path = firstPath; path <= lastPath; path++) {
            internals.add(path);
        }
    }

    @Override
    public void nodeReceived(final long path, final boolean isClean) {
        final boolean isLeaf = path >= firstLeafPath;
        if ((path == 0) || isLeaf) {
            return;
        }
        final int rank = Path.getRank(path);
        if (isClean) {
            cleanPaths.add(path);
        } else if (rank >= chunkLastRank - RANK_STEP) {
            someDirtyPaths.add(path);
        } else {
            final long left = Path.getLeftGrandChildPath(path, RANK_STEP);
            final long right = Path.getRightGrandChildPath(path, RANK_STEP);
            final long lastChunkInternal = Math.min(firstLeafPath - 1, Path.getParentPath(chunkLastLeafPath));
            for (long p = left; p <= right; p++) {
                if (p <= lastChunkInternal) {
                    internals.add(p);
                }
            }
        }
    }

    @Override
    public long getNextInternalPathToSend() {
        if (simpleMode) {
            return Path.INVALID_PATH;
        }
        long leafPath = currentLeafPath.get();
        if (leafPath < oldFirstLeafPath) {
            // Proceed to leaves
            return Path.INVALID_PATH;
        }
        if (leafPath > oldLastLeafPath) {
            // Proceed to leaves
            return Path.INVALID_PATH;
        }
        final Long internal = internals.poll();
        if (internal != null) {
            return internal;
        }
        // Proceed to leaves
        return Path.INVALID_PATH;
    }

    @Override
    public long getNextLeafPathToSend() {
        long leafPath = currentLeafPath.get();
        if (leafPath > lastLeafPath) {
            // Processing is over, this method must return INVALID_PATH
            return Path.INVALID_PATH;
        }
        if (simpleMode) {
            // Just iterate over all leaves
            currentLeafPath.set(leafPath + 1);
            return leafPath;
        }
        if ((leafPath < oldFirstLeafPath) || (leafPath > oldLastLeafPath)) {
            // Processing leaves before or after the old path range, all known to be dirty
            currentLeafPath.set(leafPath + 1);
            return leafPath;
        }
        // Skip all clean leaf paths starting from the current path
        leafPath = skipCleanPaths(leafPath, chunkLastLeafPath);
        if (leafPath == Path.INVALID_PATH) {
            // All remaining leaves in the current chunk are clean, time to proceed to the
            // next chunk, if any
            leafPath = chunkLastLeafPath + 1;
            if (leafPath > lastLeafPath) {
                // This was the last chunk. Done
                return Path.INVALID_PATH;
            } else {
                logger.debug(
                        RECONNECT.getMarker(),
                        "Chunk end: clean paths: {} some dirty paths: {} last chunk path: {}",
                        cleanPaths.size(),
                        someDirtyPaths.size(),
                        chunkLastLeafPath);
                cleanPaths.clear();
                someDirtyPaths.clear();
                long nextChunkRootPath = chunkRootPath + 1;
                if (Path.getRank(nextChunkRootPath) != chunkRootRank) {
                    assert Path.getRank(nextChunkRootPath) == chunkRootRank + 1;
                    nextChunkRootPath = Path.getParentPath(nextChunkRootPath);
                    assert chunkLastRank == firstLeafRank;
                    chunkLastRank = lastLeafRank;
                }
                chunkRootPath = nextChunkRootPath;
                chunkLastLeafPath = Path.getRightGrandChildPath(chunkRootPath, chunkLastRank - chunkRootRank);
                addInitialChunkInternals();
                currentLeafPath.set(leafPath);
                // Proceed to internal nodes
                return PATH_NOT_AVAILABLE_YET;
            }
        }
        // OK, leafPath is not clean. It can be either dirty (if one of its parents is
        // in someDirtyPaths) or not known
        boolean hasDirtyParent = false;
        long parentPath = Path.getParentPath(leafPath);
        for (int i = 0; i < RANK_STEP; i++) {
            if (someDirtyPaths.contains(parentPath)) {
                hasDirtyParent = true;
                break;
            }
            parentPath = Path.getParentPath(parentPath);
        }
        if (!hasDirtyParent) {
            currentLeafPath.set(leafPath);
            // Neither clean, nor dirty. Wait
            return PATH_NOT_AVAILABLE_YET;
        }
        currentLeafPath.set(leafPath + 1);
        return leafPath;
    }

    /**
     * Skip all clean paths starting from the given path at the same rank, un until the limit. If
     * all paths are clean up to and including the limit, Path.INVALID_PATH is returned
     */
    private long skipCleanPaths(long path, final long limit) {
        long result = skipCleanPaths(path);
        while ((result < limit) && (result != path)) {
            path = result;
            result = skipCleanPaths(path);
        }
        return (result <= limit) ? result : Path.INVALID_PATH;
    }

    /**
     * For a given path, find its highest parent path in cleanNodes. If such a parent exists,
     * skip all paths at the original paths's rank in the parent sub-tree and return the first
     * path after that. If no clean parent is found, the original path is returned
     */
    private long skipCleanPaths(final long path) {
        assert path > 0;
        long parent = Path.getParentPath(path);
        long cleanParent = Path.INVALID_PATH;
        int parentRanksAbove = 1;
        int cleanParentRanksAbove = 1;
        while (parent != ROOT_PATH) {
            if (cleanPaths.contains(parent)) {
                cleanParent = parent;
                cleanParentRanksAbove = parentRanksAbove;
            }
            parentRanksAbove++;
            parent = Path.getParentPath(parent);
        }
        final long result;
        if (cleanParent == Path.INVALID_PATH) {
            // no clean parent found
            result = path;
        } else {
            result = Path.getRightGrandChildPath(cleanParent, cleanParentRanksAbove) + 1;
        }
        assert result >= path;
        return result;
    }
}
