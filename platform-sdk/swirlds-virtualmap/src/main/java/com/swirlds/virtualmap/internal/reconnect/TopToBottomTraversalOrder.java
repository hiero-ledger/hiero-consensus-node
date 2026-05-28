// SPDX-License-Identifier: Apache-2.0
package com.swirlds.virtualmap.internal.reconnect;

import static com.swirlds.logging.legacy.LogMarker.RECONNECT;

import com.swirlds.virtualmap.internal.Path;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.Deque;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;
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

        // ── Observability (mutated only from the view's synchronized block) ──

        /** Number of PATH_NOT_AVAILABLE_YET returns caused by hasDirtyParent failure */
        int leafStallCount;
        /** Accumulated wall-clock nanoseconds spent in stall state */
        long chunkStallNanos;
        /** Timestamp of the current stall episode start, or 0 if not stalling */
        long stallStartNanos;

        /**
         * Creates a fully-initialized chunk state with its initial internals already seeded.
         *
         * @param chunkRootPath  the root path of this chunk
         * @param chunkLastRank  the leaf rank of this chunk
         */
        ChunkState(final long chunkRootPath, final int chunkLastRank) {
            this.chunkRootPath = chunkRootPath;
            this.chunkLastRank = chunkLastRank;
            final int chunkRootRank = Path.getRank(chunkRootPath);
            this.chunkLastLeafPath = Path.getRightGrandChildPath(chunkRootPath, chunkLastRank - chunkRootRank);

            // Seed initial internals at the midpoint of the chunk
            final int chunkHeight = chunkLastRank - chunkRootRank;
            final int skipRanks = chunkHeight / 2;
            this.chunkFirstCheckedRank = chunkRootRank + skipRanks;
            final long firstPath = Path.getLeftGrandChildPath(chunkRootPath, skipRanks);
            final long lastPath = Path.getRightGrandChildPath(chunkRootPath, skipRanks);
            for (long path = firstPath; path <= lastPath; path++) {
                internals.add(path);
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Global (tree-level) state — not per-chunk
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Maximum number of chunks that may be pre-fetched ahead of the current chunk.
     * 0 = no pre-fetch (baseline behavior). Negative = unbounded.
     */
    private final int chunkPrefetchDepth;

    /**
     * Creates a traversal order with the default pre-fetch depth of 1.
     */
    public TopToBottomTraversalOrder() {
        this(1);
    }

    /**
     * Creates a traversal order with the specified pre-fetch depth.
     *
     * @param chunkPrefetchDepth maximum number of chunks to pre-fetch ahead of the
     *     current chunk. 0 disables pre-fetch (baseline behavior), negative values
     *     allow unbounded pre-fetch (experimental).
     */
    public TopToBottomTraversalOrder(final int chunkPrefetchDepth) {
        this.chunkPrefetchDepth = chunkPrefetchDepth;
    }

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
     * Set to {@code true} when the current chunk's leaf phase is stalled (parent status
     * unknown), {@code false} when a leaf is successfully sent or a chunk transition occurs.
     * Read by {@link #getNextInternalPathToSend()} (outside the view's sync block) to gate
     * access to pre-fetched chunks' internals: pre-fetch internals are only returned while
     * the current chunk is stalled, ensuring that current-chunk leaves take priority over
     * pre-fetch work once the stall resolves. Races are benign — a stale {@code true} sends
     * one extra pre-fetch internal; a stale {@code false} skips one, caught on the next call.
     */
    private volatile boolean currentChunkStalled = false;

    // ── Observability (mutated only from the view's synchronized block) ──

    /** Number of chunks that have finished processing (promoted or finalized) */
    private int completedChunks;
    /** Accumulated stall nanoseconds across all completed chunks */
    private long totalStallNanos;
    /** Accumulated nanoseconds where stalls occurred with all pre-fetch queues empty */
    private long totalPrefetchExhaustedNanos;
    /** Timestamp when pre-fetch exhaustion began, or 0 if not currently exhausted */
    private long prefetchExhaustedSince;
    /** Peak number of pre-fetched chunks observed during the traversal */
    private int maxLookaheadReached;
    /** Guard to ensure the end-of-traversal summary is logged exactly once */
    private boolean traversalComplete;

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
            final int chunkLastRank = Path.getRank(startingLeaf);
            final long chunkRootPath = Path.getGrandParentPath(startingLeaf, chunkLastRank - chunkRootRank);
            activeChunks.addLast(new ChunkState(chunkRootPath, chunkLastRank));

            logger.debug(RECONNECT.getMarker(), "Pull start: chunk root rank = {}", chunkRootRank);
        }
    }

    /**
     * Finds the active chunk whose subtree contains the given internal path, or
     * {@code null} if no active chunk owns it.
     *
     * @throws IllegalStateException if no active chunk owns the path
     */
    @NonNull
    private ChunkState findOwningChunk(final long path) {
        final int rank = Path.getRank(path);
        if (rank <= chunkRootRank) {
            throw new IllegalStateException("Path " + path + " (rank " + rank + ") is at or above chunk root rank "
                    + chunkRootRank + " — not part of any chunk's subtree");
        }
        final long ancestor = Path.getGrandParentPath(path, rank - chunkRootRank);
        for (final ChunkState chunk : activeChunks) {
            if (chunk.chunkRootPath == ancestor) {
                return chunk;
            }
        }
        throw new IllegalStateException("nodeReceived for path " + path + " (rank " + rank
                + ") with no owning chunk; lastPoppedRightmost="
                + lastPoppedChunkRightmost + " activeChunks=" + activeChunks.size());
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
        final int rank = Path.getRank(path);
        if (isClean) {
            chunk.cleanPaths.add(path);
        } else if (rank >= chunk.chunkLastRank - RANK_STEP) {
            chunk.someDirtyPaths.add(path);
        } else {
            final long left = Path.getLeftGrandChildPath(path, RANK_STEP);
            final long right = Path.getRightGrandChildPath(path, RANK_STEP);
            final long lastChunkInternal = Math.min(firstLeafPath - 1, Path.getParentPath(chunk.chunkLastLeafPath));
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
        // Current chunk's internals always have highest priority — these are
        // drill-down children that help resolve the current chunk's leaf stall.
        final ChunkState current = activeChunks.peekFirst();
        if (current != null) {
            final Long internal = current.internals.poll();
            if (internal != null) {
                return internal;
            }
        }
        // Pre-fetched chunks' internals: only returned while the current chunk's
        // leaf phase is stalled. Once the stall resolves (a leaf becomes sendable),
        // currentChunkStalled is cleared, and pre-fetch internals yield to leaves.
        if (!currentChunkStalled) {
            return Path.INVALID_PATH;
        }
        boolean first = true;
        for (final ChunkState chunk : activeChunks) {
            if (first) {
                first = false;
                continue; // current chunk already polled above
            }
            final Long internal = chunk.internals.poll();
            if (internal != null) {
                return internal;
            }
        }
        // Proceed to leaves
        return Path.INVALID_PATH;
    }

    @Override
    public long getNextLeafPathToSend() {
        long leafPath = currentLeafPath.get();
        if (leafPath > lastLeafPath) {
            // Processing is over, this method must return INVALID_PATH
            finalizeTraversal(activeChunks.peekFirst());
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
        final ChunkState chunk = activeChunks.peekFirst();
        assert chunk != null : "activeChunks must not be empty outside simpleMode";
        // Skip all clean leaf paths starting from the current path
        leafPath = skipCleanPaths(chunk, leafPath);
        if (leafPath == Path.INVALID_PATH) {
            // All remaining leaves in the current chunk are clean, promote to next chunk
            leafPath = chunk.chunkLastLeafPath + 1;
            if (leafPath > lastLeafPath) {
                // This was the last chunk. Done
                finalizeTraversal(chunk);
                return Path.INVALID_PATH;
            }
            // Finalize stall timing and accumulate totals for the completing chunk
            currentChunkStalled = false;
            endStallIfActive(chunk);
            completedChunks++;
            totalStallNanos += chunk.chunkStallNanos;
            final int prefetchDepthAtPromotion = activeChunks.size() - 1;
            logger.debug(
                    RECONNECT.getMarker(),
                    "Chunk promoted: root={} leafStallCount={} prefetchDepthAtPromotion={} "
                            + "chunkStallNanos={} memCleanPaths={} memSomeDirtyPaths={} memInternals={}",
                    chunk.chunkRootPath,
                    chunk.leafStallCount,
                    prefetchDepthAtPromotion,
                    chunk.chunkStallNanos,
                    chunk.cleanPaths.size(),
                    chunk.someDirtyPaths.size(),
                    chunk.internals.size());
            // Promote: pop current chunk, advance to next
            activeChunks.pollFirst();
            //noinspection NonAtomicOperationOnVolatileField
            lastPoppedChunkRightmost = Math.max(lastPoppedChunkRightmost, chunk.chunkLastLeafPath);
            if (activeChunks.isEmpty()) {
                // No pre-fetched chunk available, compute the next one.
                final ChunkState next = computeNextChunk(chunk);
                assert next != null : "computeNextChunk returned null despite leafPath <= lastLeafPath";
                activeChunks.addLast(next);
            }
            // else: pre-fetched chunk is already the new head
            currentLeafPath.set(leafPath);
            // Proceed to internal nodes of the new chunk
            return PATH_NOT_AVAILABLE_YET;
        }
        // OK, leafPath is not clean. It can be either dirty (if one of its parents is
        // in someDirtyPaths) or not known
        boolean hasDirtyParent = false;
        long parentPath = Path.getParentPath(leafPath);
        for (int i = 0; i < RANK_STEP; i++) {
            if (chunk.someDirtyPaths.contains(parentPath)) {
                hasDirtyParent = true;
                break;
            }
            parentPath = Path.getParentPath(parentPath);
        }
        if (!hasDirtyParent) {
            // Neither clean, nor dirty. Parent status unknown — stall.
            currentChunkStalled = true;
            chunk.leafStallCount++;
            if (chunk.stallStartNanos == 0) {
                chunk.stallStartNanos = System.nanoTime();
            }
            // Seed the next chunk's internals if pre-fetch depth allows, so the
            // wire stays busy while we wait for the current chunk's responses.
            seedPrefetchChunkIfAllowed();
            // Track pre-fetch exhaustion: stalling with no pre-fetch work available
            updatePrefetchExhaustionTracking();
            currentLeafPath.set(leafPath);
            return PATH_NOT_AVAILABLE_YET;
        }
        // Leaf has a dirty parent — send it. End any active stall.
        currentChunkStalled = false;
        endStallIfActive(chunk);
        currentLeafPath.set(leafPath + 1);
        return leafPath;
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Observability helpers
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * If the given chunk is in a stall, finalizes the stall duration and clears the
     * stall state. Also finalizes any active pre-fetch exhaustion tracking.
     */
    private void endStallIfActive(final ChunkState chunk) {
        if (chunk.stallStartNanos != 0) {
            chunk.chunkStallNanos += System.nanoTime() - chunk.stallStartNanos;
            chunk.stallStartNanos = 0;
        }
        if (prefetchExhaustedSince != 0) {
            totalPrefetchExhaustedNanos += System.nanoTime() - prefetchExhaustedSince;
            prefetchExhaustedSince = 0;
        }
    }

    /**
     * Checks whether pre-fetch is exhausted (all pre-fetched chunks' internal queues
     * are empty) and updates the exhaustion duration tracking accordingly.
     */
    private void updatePrefetchExhaustionTracking() {
        final boolean exhausted = isPrefetchExhausted();
        if (exhausted && prefetchExhaustedSince == 0) {
            prefetchExhaustedSince = System.nanoTime();
        } else if (!exhausted && prefetchExhaustedSince != 0) {
            totalPrefetchExhaustedNanos += System.nanoTime() - prefetchExhaustedSince;
            prefetchExhaustedSince = 0;
        }
    }

    /**
     * Returns {@code true} if at least one pre-fetched chunk exists but all of their
     * internal queues are empty. Returns {@code false} if there are no pre-fetched
     * chunks (exhaustion is not meaningful without pre-fetch) or if any pre-fetched
     * chunk still has internals to send.
     */
    private boolean isPrefetchExhausted() {
        if (activeChunks.size() <= 1) {
            return false;
        }
        boolean first = true;
        for (final ChunkState cs : activeChunks) {
            if (first) {
                first = false;
                continue; // skip the current chunk (head)
            }
            if (!cs.internals.isEmpty()) {
                return false;
            }
        }
        return true;
    }

    /**
     * Finalizes observability for the traversal and logs the end-of-traversal summary.
     * Called when the traversal is complete (no more leaves to send). Guarded to run
     * at most once.
     */
    private void finalizeTraversal(final ChunkState lastChunk) {
        if (traversalComplete || simpleMode) {
            return;
        }
        traversalComplete = true;
        if (lastChunk != null) {
            endStallIfActive(lastChunk);
            completedChunks++;
            totalStallNanos += lastChunk.chunkStallNanos;
        }
        logger.info(
                RECONNECT.getMarker(),
                "Traversal complete: chunks={} totalStallMs={} prefetchExhaustedMs={} "
                        + "maxLookahead={} configuredDepth={}",
                completedChunks,
                TimeUnit.NANOSECONDS.toMillis(totalStallNanos),
                TimeUnit.NANOSECONDS.toMillis(totalPrefetchExhaustedNanos),
                maxLookaheadReached,
                chunkPrefetchDepth);
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Chunk computation and pre-fetch helpers
    // ═══════════════════════════════════════════════════════════════════════

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
        if (Path.getRank(nextChunkRootPath) != chunkRootRank) {
            assert Path.getRank(nextChunkRootPath) == chunkRootRank + 1;
            nextChunkRootPath = Path.getParentPath(nextChunkRootPath);
            assert lastChunk.chunkLastRank == firstLeafRank;
            nextChunkLastRank = lastLeafRank;
        }
        return new ChunkState(nextChunkRootPath, nextChunkLastRank);
    }

    /**
     * Computes the next chunk for pre-fetch purposes, or returns {@code null} if no useful
     * pre-fetch target exists beyond the given chunk. In addition to the end-of-tree check
     * in {@link #computeNextChunk}, returns {@code null} when:
     * <ul>
     *   <li>The next chunk's leaves are entirely past {@code oldLastLeafPath} — those
     *       leaves are sent immediately as dirty without needing internal queries, so
     *       pre-fetching internals for them would waste bandwidth</li>
     *   <li>The next chunk crosses the rank-change boundary. At the boundary,
     *       {@code lastChunk.chunkRootPath + 1} has a higher rank than {@code chunkRootRank},
     *       so {@link #computeNextChunk} wraps it via {@code getParentPath()} back to a root
     *       path that was already used by a pre-boundary chunk. For example, with
     *       {@code chunkRootRank=1}, pre-boundary chunks use roots 1 and 2, and the first
     *       post-boundary chunk wraps back to root 1. If both are in the deque simultaneously,
     *       {@link #findOwningChunk} — which routes by {@code chunkRootPath} — would match
     *       the earlier chunk first, misrouting the post-boundary chunk's responses into the
     *       pre-boundary chunk's state. This causes the post-boundary chunk to never see its
     *       parent statuses, leading to indefinite stalls. The rank change occurs exactly once
     *       per traversal, so blocking pre-fetch at this single transition has negligible
     *       performance impact.</li>
     * </ul>
     *
     * <p>These additional checks apply to pre-fetch only. Promotion uses
     * {@link #computeNextChunk} directly, because chunks past the old range still need
     * to be created for the immediate-send leaf path to work correctly, and the rank-change
     * boundary is safe during promotion (the old chunk with the conflicting root has already
     * been popped from the deque).
     */
    @Nullable
    private ChunkState computeNextChunkForPrefetch(final ChunkState lastChunk) {
        final long nextFirstLeaf = lastChunk.chunkLastLeafPath + 1;
        if (nextFirstLeaf > oldLastLeafPath) {
            return null;
        }
        // Don't pre-fetch across the rank-change boundary — see javadoc above.
        final long nextChunkRootPath = lastChunk.chunkRootPath + 1;
        if (Path.getRank(nextChunkRootPath) != chunkRootRank) {
            return null;
        }
        return computeNextChunk(lastChunk);
    }

    /**
     * Seeds at most one pre-fetch chunk at the tail of the deque, if the configured
     * pre-fetch depth allows and there are more chunks to process within the old leaf range.
     *
     * <p>At most one chunk is seeded per invocation. Seeding a chunk allocates a
     * {@link ChunkState}, computes geometry, and inserts ~2048 entries into its internals
     * queue — roughly 200μs of work under the view's sync lock. Seeding multiple chunks
     * in one call would hold the lock for a multiple of that duration, blocking other
     * threads waiting to call {@code getNextLeafPathToSend}. Additionally, by the time
     * a second pre-fetched chunk is needed, the current chunk's stall may have already
     * resolved, making the second pre-fetch unnecessary. Sender threads cycle back through
     * the send loop quickly; subsequent stalls will seed subsequent chunks if conditions
     * still warrant it.
     */
    private void seedPrefetchChunkIfAllowed() {
        if (chunkPrefetchDepth == 0) {
            return;
        }
        final int currentDepth = activeChunks.size() - 1;
        if (chunkPrefetchDepth > 0 && currentDepth >= chunkPrefetchDepth) {
            return;
        }
        final ChunkState lastInDeque = activeChunks.peekLast();
        assert lastInDeque != null : "activeChunks must not be empty when seeding pre-fetch";
        final ChunkState prefetched = computeNextChunkForPrefetch(lastInDeque);
        if (prefetched != null) {
            activeChunks.addLast(prefetched);
            final int depth = activeChunks.size() - 1;
            if (depth > maxLookaheadReached) {
                maxLookaheadReached = depth;
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Clean path optimization
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Skip all clean paths starting from the given path at the same rank, up until the limit. If
     * all paths are clean up to and including the limit, Path.INVALID_PATH is returned.
     */
    private long skipCleanPaths(final ChunkState chunk, long path) {
        final long limit = chunk.chunkLastLeafPath;
        long result = skipCleanPath(chunk, path);
        while ((result < limit) && (result != path)) {
            path = result;
            result = skipCleanPath(chunk, path);
        }
        return (result <= limit) ? result : Path.INVALID_PATH;
    }

    /**
     * For a given path, find its highest parent path in cleanPaths. If such a parent exists,
     * skip all paths at the original path's rank in the parent sub-tree and return the first
     * path after that. If no clean parent is found, the original path is returned.
     */
    private long skipCleanPath(final ChunkState chunk, final long path) {
        assert path > 0;
        final int rank = Path.getRank(path);
        long parent = Path.getParentPath(path);
        int parentRank = rank - 1;
        long cleanParent = Path.INVALID_PATH;
        while (parentRank >= chunk.chunkFirstCheckedRank) {
            if (chunk.cleanPaths.contains(parent)) {
                cleanParent = parent;
                break;
            }
            parent = Path.getParentPath(parent);
            parentRank--;
        }
        final long result;
        if (cleanParent == Path.INVALID_PATH) {
            // no clean parent found
            result = path;
        } else {
            result = Path.getRightGrandChildPath(cleanParent, rank - parentRank) + 1;
        }
        assert result >= path;
        return result;
    }
}
