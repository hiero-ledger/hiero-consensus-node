// SPDX-License-Identifier: Apache-2.0
package com.swirlds.virtualmap.internal.reconnect;

import static com.swirlds.logging.legacy.LogMarker.RECONNECT;

import com.swirlds.virtualmap.MerklePathUtils;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.Deque;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
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
 * the chunk, which contains the last leaf. Each chunk maintains its own set of
 * accumulated clean and dirty nodes (see {@link ChunkState}). Since chunk subtrees
 * are disjoint, these sets are scoped to a single chunk and discarded once the chunk's
 * leaves are fully processed, ensuring that memory usage stays bounded.
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

    // ═══════════════════════════════════════════════════════════════════════
    // Per-chunk state
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Encapsulates all mutable state that is scoped to a single chunk. A new instance
     * is created for every chunk and discarded once the chunk's leaves are fully processed.
     */
    static final class ChunkState {

        /** Node path of this chunk's root */
        final long chunkRootPath;

        /**
         * The rightmost descendant of this chunk's root at the leaf rank. For the last
         * chunk in the traversal, this value may exceed the tree's {@code lastLeafPath}
         * — callers must clamp against {@code lastLeafPath} when iterating leaves.
         */
        final long chunkLastLeafPath;

        /** The leaf rank of this chunk (either firstLeafRank or lastLeafRank) */
        final int chunkLastRank;

        /**
         * The rank at which initial chunk internals are seeded, equal to
         * {@code chunkRootRank + (chunkLastRank - chunkRootRank) / 2} — the midpoint
         * of the chunk. Used by {@code skipCleanPaths} to bound how far up the tree
         * it walks looking for clean ancestors.
         */
        final int chunkFirstCheckedRank;

        /**
         * Clean internal nodes for this chunk. Used by {@code skipCleanPaths} to skip
         * subtrees known to be identical on learner and teacher.
         */
        final Set<Long> cleanPaths = ConcurrentHashMap.newKeySet();

        /**
         * Dirty internal nodes within RANK_STEP of the leaf rank for this chunk. Together
         * with {@code cleanPaths}, used to determine whether a leaf can be sent or must wait.
         */
        final Set<Long> someDirtyPaths = ConcurrentHashMap.newKeySet();

        /**
         * Queue of internal node paths to send to the teacher for this chunk. Initially
         * populated with the chunk's seed internals, then extended via dirty drill-down.
         */
        final Queue<Long> internals = new ConcurrentLinkedQueue<>();

        /**
         * Creates a fully-initialized chunk state with its initial internals already seeded.
         *
         * @param chunkRootPath  the root path of this chunk
         * @param chunkLastRank  the leaf rank of this chunk
         */
        ChunkState(final long chunkRootPath, final int chunkLastRank) {
            this.chunkRootPath = chunkRootPath;
            this.chunkLastRank = chunkLastRank;
            final int chunkRootRank = MerklePathUtils.getRank(chunkRootPath);
            this.chunkLastLeafPath =
                    MerklePathUtils.getRightGrandChildPath(chunkRootPath, chunkLastRank - chunkRootRank);

            // Seed initial internals at the midpoint of the chunk
            final int chunkHeight = chunkLastRank - chunkRootRank;
            final int skipRanks = chunkHeight / 2;
            this.chunkFirstCheckedRank = chunkRootRank + skipRanks;
            final long firstPath = MerklePathUtils.getLeftGrandChildPath(chunkRootPath, skipRanks);
            final long lastPath = MerklePathUtils.getRightGrandChildPath(chunkRootPath, skipRanks);
            for (long path = firstPath; path <= lastPath; path++) {
                internals.add(path);
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Global (tree-level) state — not per-chunk
    // ═══════════════════════════════════════════════════════════════════════

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

    /** The rank of chunk root nodes. Same for all chunks. */
    private volatile int chunkRootRank;

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

    /**
     * Active chunk(s). Head = the current chunk whose leaves are being processed.
     * Subsequent entries are pre-fetched chunks whose internals have been seeded
     * but whose leaf phase has not started yet. Empty in simple mode.
     */
    private final Deque<ChunkState> activeChunks = new ConcurrentLinkedDeque<>();

    /**
     * The largest {@code chunkLastLeafPath} among all chunks that have finished processing
     * and been removed from {@link #activeChunks}. Included in error diagnostics if
     * {@link #nodeReceived} encounters a path with no owning chunk. Only increases over
     * time, since chunks are processed left-to-right in ascending path order.
     */
    private volatile long lastPoppedChunkRightmost = -1;

    /**
     * Set to {@code true} when the current chunk's leaf phase stalls (parent status unknown);
     * cleared to {@code false} only inside {@link #getNextLeafPathToSend()}, when a leaf becomes
     * sendable or the chunk completes.
     *
     * <p>Read by {@link #getNextInternalPathToSend()} to gate access to pre-fetched chunks'
     * internals. The current chunk's own internals (including dirty drill-down) always have
     * priority; pre-fetched chunks' internals are returned only while this flag is {@code true}.
     *
     * <p>Priority is soft, not immediate: the sender exhausts {@link #getNextInternalPathToSend()}
     * before it calls {@link #getNextLeafPathToSend()}, and only the latter clears this flag. So
     * once the current chunk's own internals are drained, the sender returns <em>every</em>
     * pre-fetched internal currently queued before the next leaf call clears the flag — not just
     * one. Current-chunk leaves therefore regain priority only after that queued pre-fetch backlog
     * drains. Leaf FIFO order is unaffected (leaves are still produced in ascending order); internal
     * send order is unconstrained, so this is a throughput/latency trade-off, not a correctness issue.
     *
     * <p>Concurrency: a stale {@code true} lets the sender drain the queued pre-fetch internals
     * before the flag clears; a stale {@code false} skips pre-fetch for one internal check and is
     * re-set on the next {@link #getNextLeafPathToSend()}. Both are benign.
     */
    private volatile boolean currentChunkStalled = false;

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

        firstLeafRank = MerklePathUtils.getRank(firstLeafPath);
        lastLeafRank = MerklePathUtils.getRank(lastLeafPath);

        if (firstLeafRank < 10) {
            simpleMode = true;
        } else {
            chunkRootRank = Math.max(1, lastLeafRank - DEFAULT_CHUNK_HEIGHT);
            final long startingLeaf = Math.max(firstLeafPath, oldFirstLeafPath);
            final int chunkLastRank = MerklePathUtils.getRank(startingLeaf);
            final long chunkRootPath = MerklePathUtils.getGrandParentPath(startingLeaf, chunkLastRank - chunkRootRank);
            activeChunks.addLast(new ChunkState(chunkRootPath, chunkLastRank));

            logger.debug(RECONNECT.getMarker(), "Pull start: chunk root rank = {}", chunkRootRank);
        }
    }

    /**
     * Finds the active chunk whose subtree contains the given internal path.
     *
     * @param path an internal node path; its rank must be greater than {@code chunkRootRank}
     * @return the owning {@link ChunkState}, or {@code null} if no active chunk owns the path
     *         (its chunk was already completed and popped — a benign race with in-flight responses)
     * @throws IllegalStateException if {@code path} is at or above the chunk root rank
     */
    @Nullable
    private ChunkState findOwningChunk(final long path) {
        final int rank = MerklePathUtils.getRank(path);
        if (rank <= chunkRootRank) {
            throw new IllegalStateException("Path " + path + " (rank " + rank + ") is at or above chunk root rank "
                    + chunkRootRank + " — not part of any chunk's subtree");
        }
        final long ancestor = MerklePathUtils.getGrandParentPath(path, rank - chunkRootRank);
        for (final ChunkState chunk : activeChunks) {
            if (chunk.chunkRootPath == ancestor) {
                return chunk;
            }
        }

        // No active chunk owns this path — its chunk was already completed and popped.
        // This is a benign race: the sender thread popped the chunk (all leaves resolved)
        // while in-flight internal responses from the teacher were still in the receiver
        // pipeline. The information is stale and safe to discard.
        logger.trace(
                RECONNECT.getMarker(),
                "Ignoring stale nodeReceived for path {} (rank {}): owning chunk already popped "
                        + "(lastPoppedRightmost={}, activeChunks={})",
                path,
                rank,
                lastPoppedChunkRightmost,
                activeChunks.size());
        return null;
    }

    @Override
    public void nodeReceived(final long path, final boolean isClean) {
        final boolean isLeaf = path >= firstLeafPath;
        if ((path == 0) || isLeaf) {
            return;
        }
        if (simpleMode) {
            // No chunks in simple mode — internal responses are not expected
            return;
        }
        final ChunkState chunk = findOwningChunk(path);
        if (chunk == null) {
            return; // stale response for a completed chunk
        }
        final int rank = MerklePathUtils.getRank(path);
        if (isClean) {
            chunk.cleanPaths.add(path);
        } else if (rank >= chunk.chunkLastRank - RANK_STEP) {
            chunk.someDirtyPaths.add(path);
        } else {
            final long left = MerklePathUtils.getLeftGrandChildPath(path, RANK_STEP);
            final long right = MerklePathUtils.getRightGrandChildPath(path, RANK_STEP);
            final long lastChunkInternal =
                    Math.min(firstLeafPath - 1, MerklePathUtils.getParentPath(chunk.chunkLastLeafPath));
            for (long p = left; p <= right; p++) {
                if (p <= lastChunkInternal) {
                    chunk.internals.add(p);
                }
            }
        }
    }

    @Override
    public long getNextInternalPathToSend() {
        if (simpleMode) {
            return MerklePathUtils.INVALID_PATH;
        }
        long leafPath = currentLeafPath.get();
        if (leafPath < oldFirstLeafPath) {
            // Proceed to leaves
            return MerklePathUtils.INVALID_PATH;
        }
        if (leafPath > oldLastLeafPath) {
            // Proceed to leaves
            return MerklePathUtils.INVALID_PATH;
        }
        // The current chunk's own internals are always sent first, regardless of currentChunkStalled.
        // This is not just stall handling: on chunk entry (and right after promotion) the flag is false
        // but no leaf can yet be classified clean/dirty — the chunk's seeded internals must go out and be
        // answered before getNextLeafPathToSend can make progress. This queue also carries dirty
        // drill-down children (added by nodeReceived), which confirm the next band of leaves and so must
        // precede leaf sends to keep the pipeline full. Sending leaves ahead of these would force an
        // unnecessary stall per chunk.
        final ChunkState current = activeChunks.peekFirst();
        if (current != null) {
            final Long internal = current.internals.poll();
            if (internal != null) {
                return internal;
            }
        }
        // Pre-fetched chunks' internals: returned only while the current chunk's leaf phase is
        // stalled. The flag is cleared only in getNextLeafPathToSend(), and the sender exhausts this
        // method before calling that one — so once the current chunk's own internals are drained,
        // every queued pre-fetch internal is returned before the next leaf call clears the flag, not
        // just one. Current-chunk leaves regain priority only after that backlog drains. Harmless:
        // internal send order is unconstrained and leaf FIFO order is unaffected.`
        if (!currentChunkStalled) {
            return MerklePathUtils.INVALID_PATH;
        }
        for (final ChunkState chunk : activeChunks) {
            final Long internal = chunk.internals.poll();
            if (internal != null) {
                return internal;
            }
        }
        // Proceed to leaves
        return MerklePathUtils.INVALID_PATH;
    }

    @Override
    public long getNextLeafPathToSend() {
        long leafPath = currentLeafPath.get();
        if (leafPath > lastLeafPath) {
            // Processing is over, this method must return INVALID_PATH
            return MerklePathUtils.INVALID_PATH;
        }
        if (simpleMode) {
            currentLeafPath.set(leafPath + 1);
            return leafPath;
        }
        if ((leafPath < oldFirstLeafPath) || (leafPath > oldLastLeafPath)) {
            // Leaves before or after the old path range are all known to be dirty
            currentLeafPath.set(leafPath + 1);
            return leafPath;
        }
        final ChunkState chunk = activeChunks.peekFirst();
        assert chunk != null : "activeChunks must not be empty outside simpleMode";
        // Skip all clean leaf paths starting from the current path
        leafPath = skipCleanPaths(chunk, leafPath);
        if (leafPath == MerklePathUtils.INVALID_PATH) {
            // The current chunk is finished because skipCleanPaths skipped all its remaining
            // leaves as clean (INVALID_PATH) — it completes WITHOUT sending those leaves and
            // WITHOUT waiting on the internals that would have classified them, so this chunk's
            // seeded/drill-down internal requests may still be unanswered and in flight. (This is
            // the all-clean-skip completion; a chunk that finishes by sending its last leaf has,
            // by contrast, already received that leaf's governing internal responses.) Pop the
            // chunk now — for both the last-chunk (return) and promote paths — and advance the
            // popped-rightmost watermark, so a late in-flight internal response for this chunk is
            // recognized as stale by findOwningChunk (returns null → discarded) rather than
            // triggering its "no owning chunk" IllegalStateException. Response reordering under
            // load makes such late arrivals routine, not exceptional.
            activeChunks.pollFirst();
            //noinspection NonAtomicOperationOnVolatileField
            lastPoppedChunkRightmost = Math.max(lastPoppedChunkRightmost, chunk.chunkLastLeafPath);

            leafPath = chunk.chunkLastLeafPath + 1;
            if (leafPath > lastLeafPath) {
                // This was the last chunk. Advance past the end so the next call terminates at the
                // top-of-method guard (the deque is now empty).
                currentLeafPath.set(leafPath);
                return MerklePathUtils.INVALID_PATH;
            }
            // Current chunk complete; its leaf stall (if any) is over.
            currentChunkStalled = false;
            if (activeChunks.isEmpty()) {
                // No pre-fetched chunk available — compute the next one.
                final ChunkState next = computeNextChunk(chunk);
                assert next != null : "computeNextChunk returned null despite leafPath <= lastLeafPath";
                activeChunks.addLast(next);
            }
            currentLeafPath.set(leafPath);
            // Proceed to internal nodes of the new chunk
            return PATH_NOT_AVAILABLE_YET;
        }
        // leafPath is not clean: either dirty (a parent is in someDirtyPaths) or unknown.
        if (!hasDirtyParentWithinRankStep(chunk, leafPath)) {
            // Neither clean nor dirty. Parent status unknown — stall, and seed the next chunk's
            // internals so the wire stays busy while we wait for the current chunk's responses.
            currentChunkStalled = true;
            seedNextPrefetchChunk();
            currentLeafPath.set(leafPath);
            return PATH_NOT_AVAILABLE_YET;
        }
        // Leaf has a dirty parent — send it. End any active stall.
        currentChunkStalled = false;
        currentLeafPath.set(leafPath + 1);
        return leafPath;
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Chunk computation and pre-fetch helpers
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Returns {@code true} if the chunk immediately following {@code lastChunk} crosses the
     * rank-change boundary — i.e., incrementing the chunk root path
     * ({@code lastChunk.chunkRootPath + 1}) lands at a rank higher than {@code chunkRootRank}.
     * This happens exactly once per traversal, where chunks transition from
     * {@code firstLeafRank} to {@code lastLeafRank}.
     *
     * <p>At that boundary, {@link #computeNextChunk} wraps the incremented root back via
     * {@code getParentPath()} to a root path already used by the pre-boundary chunk. During
     * promotion that is safe (the pre-boundary chunk has already been popped). During
     * pre-fetch it is not: if both chunks are live in {@link #activeChunks} simultaneously,
     * {@link #findOwningChunk} — which routes by {@code chunkRootPath} — would match the
     * earlier chunk and misroute the post-boundary chunk's responses, stalling it
     * indefinitely. Pre-fetch therefore must not seed across this boundary.
     */
    private boolean crossesRankBoundary(final ChunkState lastChunk) {
        return MerklePathUtils.getRank(lastChunk.chunkRootPath + 1) != chunkRootRank;
    }

    /**
     * Computes the {@link ChunkState} for the chunk immediately following the given chunk.
     * Handles the rank-change boundary where chunks transition from {@code firstLeafRank}
     * to {@code lastLeafRank}. Returns {@code null} if the tree has no more chunks
     * (i.e., {@code lastChunk} already covers the last leaf path).
     */
    @Nullable
    private ChunkState computeNextChunk(final ChunkState lastChunk) {
        if (lastChunk.chunkLastLeafPath + 1 > lastLeafPath) {
            return null;
        }
        long nextChunkRootPath = lastChunk.chunkRootPath + 1;
        int nextChunkLastRank = lastChunk.chunkLastRank;
        if (crossesRankBoundary(lastChunk)) {
            assert MerklePathUtils.getRank(nextChunkRootPath) == chunkRootRank + 1;
            nextChunkRootPath = MerklePathUtils.getParentPath(nextChunkRootPath);
            assert lastChunk.chunkLastRank == firstLeafRank;
            nextChunkLastRank = lastLeafRank;
        }
        return new ChunkState(nextChunkRootPath, nextChunkLastRank);
    }

    /**
     * Seeds one pre-fetch chunk at the tail of the deque, if there are more chunks to process within
     * the old leaf range and the next chunk does not cross the rank-change boundary.
     *
     * <p>At most one chunk is seeded per invocation: seeding allocates a {@link ChunkState} and
     * inserts its initial internals ({@code 2^(chunkHeight/2)} entries) under the view's sync lock, so
     * seeding several at once would hold that lock proportionally longer and block other senders.
     * Sender threads cycle back quickly, so a subsequent stall seeds the subsequent chunk if still
     * warranted. Pre-fetch is unbounded — sustained stalls grow the deque one chunk per stall.
     *
     * <p>Two gates apply here that promotion does not need:
     * <ul>
     *   <li>The next chunk's leaves must not be entirely past {@code oldLastLeafPath} — such leaves are
     *       sent immediately as dirty without internal queries, so pre-fetching their internals would
     *       waste bandwidth.</li>
     *   <li>The next chunk must not cross the rank-change boundary (see {@link #crossesRankBoundary}) —
     *       seeding across it would place two chunks with the same root path in the deque and misroute
     *       responses.</li>
     * </ul>
     */
    private void seedNextPrefetchChunk() {
        final ChunkState lastInDeque = activeChunks.peekLast();
        assert lastInDeque != null : "activeChunks must not be empty when seeding pre-fetch";
        if (lastInDeque.chunkLastLeafPath + 1 > oldLastLeafPath) {
            return;
        }
        if (crossesRankBoundary(lastInDeque)) {
            return;
        }
        final ChunkState prefetched = computeNextChunk(lastInDeque);
        if (prefetched != null) {
            activeChunks.addLast(prefetched);
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Clean path optimization
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Skip all clean paths starting from the given path at the same rank, up until the limit. If
     * all paths are clean up to and including the limit, MerklePathUtils.INVALID_PATH is returned.
     *
     * <p>The limit is clamped to {@code lastLeafPath}. For the final chunk in the traversal,
     * {@code chunk.chunkLastLeafPath} is the rightmost descendant of the chunk root at the leaf
     * rank by pure path geometry and may exceed the tree's real {@code lastLeafPath} (see the
     * {@code chunkLastLeafPath} field javadoc).
     */
    private long skipCleanPaths(final ChunkState chunk, long path) {
        final long limit = Math.min(chunk.chunkLastLeafPath, lastLeafPath);
        long result = skipCleanPath(chunk, path);
        while ((result < limit) && (result != path)) {
            path = result;
            result = skipCleanPath(chunk, path);
        }
        return (result <= limit) ? result : MerklePathUtils.INVALID_PATH;
    }

    /**
     * For a given path, find its highest parent path in cleanPaths. If such a parent exists,
     * skip all paths at the original path's rank in the parent sub-tree and return the first
     * path after that. If no clean parent is found, the original path is returned.
     */
    private long skipCleanPath(final ChunkState chunk, final long path) {
        assert path > 0;
        final int rank = MerklePathUtils.getRank(path);
        long parent = MerklePathUtils.getParentPath(path);
        int parentRank = rank - 1;
        long cleanParent = MerklePathUtils.INVALID_PATH;
        while (parentRank >= chunk.chunkFirstCheckedRank) {
            if (chunk.cleanPaths.contains(parent)) {
                cleanParent = parent;
                break;
            }
            parent = MerklePathUtils.getParentPath(parent);
            parentRank--;
        }
        final long result;
        if (cleanParent == MerklePathUtils.INVALID_PATH) {
            // no clean parent found
            result = path;
        } else {
            result = MerklePathUtils.getRightGrandChildPath(cleanParent, rank - parentRank) + 1;
        }
        assert result >= path;
        return result;
    }

    /**
     * Returns {@code true} if any of the {@code RANK_STEP} ancestors immediately above the
     * given leaf path — its parent, grandparent, and so on up to {@code RANK_STEP} ranks —
     * is in the chunk's {@code someDirtyPaths}. The leaf itself is not checked.
     *
     * <p>This mirrors the dirty drill-down bound: when a dirty internal is found, its
     * grandchildren {@code RANK_STEP} ranks below are queued (see {@link #nodeReceived}),
     * so a leaf is known to be dirty exactly when one of its nearest {@code RANK_STEP}
     * ancestors is a confirmed-dirty internal.
     */
    private boolean hasDirtyParentWithinRankStep(final ChunkState chunk, final long leafPath) {
        long parentPath = MerklePathUtils.getParentPath(leafPath);
        for (int i = 0; i < RANK_STEP; i++) {
            if (chunk.someDirtyPaths.contains(parentPath)) {
                return true;
            }
            parentPath = MerklePathUtils.getParentPath(parentPath);
        }
        return false;
    }
}
