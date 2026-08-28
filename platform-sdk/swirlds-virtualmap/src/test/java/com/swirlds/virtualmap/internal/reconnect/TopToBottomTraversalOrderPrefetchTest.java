// SPDX-License-Identifier: Apache-2.0
package com.swirlds.virtualmap.internal.reconnect;

import static com.swirlds.virtualmap.internal.Path.INVALID_PATH;
import static com.swirlds.virtualmap.internal.reconnect.NodeTraversalOrder.PATH_NOT_AVAILABLE_YET;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Tests for the chunk pre-fetch optimization in {@link TopToBottomTraversalOrder}.
 *
 * <p>Uses the same two-chunk reference tree as the main test class:
 * firstLeafPath=1023, lastLeafPath=2046, chunkRootRank=1.
 * <ul>
 *   <li>Chunk 1: root=1, leaves 1023–1534, initial internals at rank 5: paths 31–46</li>
 *   <li>Chunk 2: root=2, leaves 1535–2046, initial internals at rank 5: paths 47–62</li>
 * </ul>
 */
@DisplayName("TopToBottomTraversalOrder — Chunk Pre-fetch")
class TopToBottomTraversalOrderPrefetchTest {

    private static final long CHUNK_FIRST = 1023L;
    private static final long CHUNK_LAST = 2 * CHUNK_FIRST; // 2046

    // Chunk 1 initial internals (rank 5)
    private static final long CHUNK1_INIT_LO = 31L;
    private static final long CHUNK1_INIT_HI = 46L;
    // Chunk 2 initial internals (rank 5)
    private static final long CHUNK2_INIT_LO = 47L;
    private static final long CHUNK2_INIT_HI = 62L;

    // Simple-mode tree (rank 9, firstLeafRank < 10)
    private static final long SIMPLE_FIRST = 511L;
    private static final long SIMPLE_LAST = 2 * SIMPLE_FIRST; // 1022

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static List<Long> drainInternals(final TopToBottomTraversalOrder order) {
        final List<Long> result = new ArrayList<>();
        long path;
        while ((path = order.getNextInternalPathToSend()) != INVALID_PATH) {
            result.add(path);
        }
        return result;
    }

    private static List<Long> driveAllDirty(final TopToBottomTraversalOrder order) {
        final List<Long> leaves = new ArrayList<>();
        int stalls = 0;
        while (true) {
            final long internal = order.getNextInternalPathToSend();
            if (internal != INVALID_PATH) {
                order.nodeReceived(internal, false);
                stalls = 0;
                continue;
            }
            final long leaf = order.getNextLeafPathToSend();
            if (leaf == INVALID_PATH) {
                break;
            }
            if (leaf == PATH_NOT_AVAILABLE_YET) {
                assertTrue(++stalls <= 100_000, "Algorithm stalled waiting for leaf path");
            } else {
                leaves.add(leaf);
                stalls = 0;
            }
        }
        return leaves;
    }

    private static List<Long> driveAllClean(final TopToBottomTraversalOrder order) {
        final List<Long> leaves = new ArrayList<>();
        int stalls = 0;
        while (true) {
            final long internal = order.getNextInternalPathToSend();
            if (internal != INVALID_PATH) {
                order.nodeReceived(internal, true);
                stalls = 0;
                continue;
            }
            final long leaf = order.getNextLeafPathToSend();
            if (leaf == INVALID_PATH) {
                break;
            }
            if (leaf == PATH_NOT_AVAILABLE_YET) {
                assertTrue(++stalls <= 100_000, "Algorithm stalled waiting for leaf path");
            } else {
                leaves.add(leaf);
                stalls = 0;
            }
        }
        return leaves;
    }

    /**
     * Drives the algorithm to completion feeding every internal as dirty, verifying leaf
     * ordering inline without materializing the full leaf list. Returns the number of
     * leaves sent. Asserts strict ascending order (which also proves no duplicates).
     * Use this instead of {@link #driveAllDirty} for large trees to avoid heap pressure
     * from boxing every leaf into a list.
     */
    private static long driveAllDirtyStreaming(
            final TopToBottomTraversalOrder order, final long expectedFirst, final long expectedLast) {
        long count = 0;
        long prev = -1;
        int stalls = 0;
        while (true) {
            final long internal = order.getNextInternalPathToSend();
            if (internal != INVALID_PATH) {
                order.nodeReceived(internal, false);
                stalls = 0;
                continue;
            }
            final long leaf = order.getNextLeafPathToSend();
            if (leaf == INVALID_PATH) {
                break;
            }
            if (leaf == PATH_NOT_AVAILABLE_YET) {
                assertTrue(++stalls <= 100_000, "Algorithm stalled waiting for leaf path");
            } else {
                if (count == 0) {
                    assertEquals(expectedFirst, leaf, "First leaf must equal firstLeafPath");
                }
                assertTrue(leaf > prev, "Leaves must be in strictly ascending order (no duplicates)");
                prev = leaf;
                count++;
                stalls = 0;
            }
        }
        assertEquals(expectedLast, prev, "Last leaf must equal lastLeafPath");
        return count;
    }

    @SuppressWarnings("unchecked")
    private static Deque<TopToBottomTraversalOrder.ChunkState> getActiveChunks(final TopToBottomTraversalOrder order)
            throws Exception {
        final Field field = TopToBottomTraversalOrder.class.getDeclaredField("activeChunks");
        field.setAccessible(true);
        return (Deque<TopToBottomTraversalOrder.ChunkState>) field.get(order);
    }

    private static int getMaxLookaheadReached(final TopToBottomTraversalOrder order) throws Exception {
        final Field field = TopToBottomTraversalOrder.class.getDeclaredField("maxLookaheadReached");
        field.setAccessible(true);
        return (int) field.get(order);
    }

    /**
     * Triggers a stall in the current chunk by draining its initial internals without
     * providing any nodeReceived responses, then calling getNextLeafPathToSend.
     */
    private static void triggerStall(final TopToBottomTraversalOrder order) {
        // Drain internals without feeding responses — leaf will stall
        drainInternals(order);
        assertEquals(
                PATH_NOT_AVAILABLE_YET,
                order.getNextLeafPathToSend(),
                "Leaf must stall when no parent status is known");
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  Pre-fetch trigger
    // ═════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Pre-fetch trigger on stall")
    class PrefetchTriggerTests {

        @Test
        @DisplayName("Stall seeds a second ChunkState in the deque")
        void stallSeedsPrefetchChunk() throws Exception {
            final var order = new TopToBottomTraversalOrder();
            order.start(CHUNK_FIRST, CHUNK_LAST, CHUNK_FIRST, CHUNK_LAST);

            triggerStall(order);

            final var chunks = getActiveChunks(order);
            assertEquals(2, chunks.size(), "Deque must have 2 chunks after stall with depth=1");

            // Verify the pre-fetched chunk has internals seeded
            final var prefetched = chunks.peekLast();
            assertFalse(prefetched.internals.isEmpty(), "Pre-fetched chunk must have internals seeded");
        }

        @Test
        @DisplayName("Pre-fetched chunk's internals are for chunk 2 (paths 47–62)")
        void prefetchedChunkHasCorrectInternals() throws Exception {
            final var order = new TopToBottomTraversalOrder();
            order.start(CHUNK_FIRST, CHUNK_LAST, CHUNK_FIRST, CHUNK_LAST);

            // Drain chunk 1 internals, stall
            drainInternals(order);
            order.getNextLeafPathToSend(); // triggers pre-fetch

            // Now getNextInternalPathToSend should return chunk 2's internals
            final List<Long> prefetchInternals = drainInternals(order);
            assertFalse(prefetchInternals.isEmpty(), "Pre-fetch internals must be available");
            assertTrue(prefetchInternals.get(0) >= CHUNK2_INIT_LO, "Pre-fetched internals must start at chunk 2 range");
            assertTrue(
                    prefetchInternals.get(prefetchInternals.size() - 1) <= CHUNK2_INIT_HI,
                    "Pre-fetched internals must end within chunk 2 range");
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    // Internal priority
    // ═════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Current chunk internals have priority over pre-fetch")
    class InternalPriorityTests {

        @Test
        @DisplayName("Current chunk drill-down internals polled before pre-fetch internals")
        void currentChunkInternalsPriority() throws Exception {
            final var order = new TopToBottomTraversalOrder();
            order.start(CHUNK_FIRST, CHUNK_LAST, CHUNK_FIRST, CHUNK_LAST);

            // Drain chunk 1 initial internals, feed one as dirty to trigger drill-down
            drainInternals(order);
            // Path 31 (rank 5) drills down to rank-8 children (255–262)
            order.nodeReceived(31L, false);

            // Stall to trigger pre-fetch of chunk 2
            order.getNextLeafPathToSend();

            // Now both chunk 1's drill-down (255–262) and chunk 2's initial internals
            // (47–62) are available. Chunk 1's must come first.
            final long first = order.getNextInternalPathToSend();
            assertTrue(
                    first >= 255L && first <= 262L,
                    "First internal returned must be from chunk 1's drill-down (255–262), got " + first);
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    // nodeReceived routing
    // ═════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("nodeReceived routes to the correct chunk")
    class NodeReceivedRoutingTests {

        @Test
        @DisplayName("Responses for chunk 2 internals route to chunk 2's state")
        void prefetchResponseRoutedCorrectly() throws Exception {
            final var order = new TopToBottomTraversalOrder();
            order.start(CHUNK_FIRST, CHUNK_LAST, CHUNK_FIRST, CHUNK_LAST);

            // Drain chunk 1 internals, stall to seed chunk 2
            drainInternals(order);
            order.getNextLeafPathToSend();

            // Drain chunk 2's pre-fetched internals and feed responses
            final List<Long> prefetchInternals = drainInternals(order);
            for (long p : prefetchInternals) {
                order.nodeReceived(p, true); // all clean
            }

            // Verify chunk 2's cleanPaths got populated (not chunk 1's)
            final var chunks = getActiveChunks(order);
            final var chunk1 = chunks.peekFirst();
            final var chunk2 = chunks.peekLast();
            assertNotEquals(chunk1, chunk2, "Must have two distinct chunks");

            // Chunk 1 should have no clean paths (we never fed it responses)
            assertTrue(chunk1.cleanPaths.isEmpty(), "Chunk 1 cleanPaths must be empty (no responses fed to it)");
            // Chunk 2 should have clean paths from the pre-fetch responses
            assertFalse(chunk2.cleanPaths.isEmpty(), "Chunk 2 cleanPaths must be populated from pre-fetch responses");
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    // Chunk promotion with pre-fetch
    // ═════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Promotion uses pre-fetched chunk")
    class PromotionWithPrefetchTests {

        @Test
        @DisplayName("Completing chunk 1 promotes pre-fetched chunk 2 without recomputing")
        void promotionUsesPrefetchedChunk() throws Exception {
            final var order = new TopToBottomTraversalOrder();
            order.start(CHUNK_FIRST, CHUNK_LAST, CHUNK_FIRST, CHUNK_LAST);

            // Drain chunk 1 internals, stall to seed chunk 2 pre-fetch
            final List<Long> chunk1Internals = drainInternals(order);
            order.getNextLeafPathToSend(); // stall → pre-fetch

            // Remember chunk 2's identity before promotion
            final var chunksBefore = getActiveChunks(order);
            assertEquals(2, chunksBefore.size());
            final var prefetchedBefore = chunksBefore.peekLast();

            // Now complete chunk 1: feed all internals as clean → all leaves skipped → transition
            for (long p : chunk1Internals) {
                order.nodeReceived(p, true);
            }

            // Single call: skipCleanPaths skips all chunk 1 leaves → transition
            assertEquals(PATH_NOT_AVAILABLE_YET, order.getNextLeafPathToSend());

            // After transition, chunk 2 (pre-fetched) is now the head
            final var chunksAfter = getActiveChunks(order);
            assertEquals(1, chunksAfter.size(), "After promotion, deque must have exactly one chunk");
            // The promoted chunk must be the SAME object as the pre-fetched one
            assertTrue(
                    chunksAfter.peekFirst() == prefetchedBefore,
                    "Promoted chunk must be the same instance as the pre-fetched one");
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    // One-chunk-per-call invariant
    // ═════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("At most one chunk seeded per call")
    class OneChunkPerCallTests {

        @Test
        @DisplayName("Depth=5 seeds only one chunk per stall call")
        void onlyOneChunkPerCall() throws Exception {
            // Use the large tree for multiple chunks
            final long first = 100_000_000L;
            final long last = 200_000_000L;
            final var order = new TopToBottomTraversalOrder();
            order.start(first, last, first, last);

            // First stall
            triggerStall(order);
            assertEquals(2, getActiveChunks(order).size(), "First stall must seed exactly one pre-fetch chunk");

            // Drain the pre-fetched internals so the queue is empty
            drainInternals(order);

            // Second stall (re-stall on the same chunk)
            assertEquals(PATH_NOT_AVAILABLE_YET, order.getNextLeafPathToSend());
            assertEquals(3, getActiveChunks(order).size(), "Second stall must seed exactly one more pre-fetch chunk");
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    // Pre-fetch past end of tree (no-op)
    // ═════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("No pre-fetch when current chunk is the last")
    class PrefetchPastEndTests {

        @Test
        @DisplayName("Single-chunk-mode tree: stall does not seed pre-fetch")
        void noPreFetchInSingleChunkTree() throws Exception {
            // In the CHUNK_FIRST/CHUNK_LAST tree, there are exactly 2 chunks.
            // Use a tree where chunk 2 is the last chunk. Stall inside chunk 2.
            final var order = new TopToBottomTraversalOrder();
            order.start(CHUNK_FIRST, CHUNK_LAST, CHUNK_FIRST, CHUNK_LAST);

            // Complete chunk 1 quickly (all clean) to reach chunk 2
            final List<Long> chunk1Internals = drainInternals(order);
            for (long p : chunk1Internals) {
                order.nodeReceived(p, true);
            }
            // Chunk transition: exactly one PATH_NOT_AVAILABLE_YET
            assertEquals(PATH_NOT_AVAILABLE_YET, order.getNextLeafPathToSend());

            // Now we're in chunk 2 (the last chunk). Drain its internals, stall.
            drainInternals(order);
            assertEquals(PATH_NOT_AVAILABLE_YET, order.getNextLeafPathToSend());

            // No pre-fetch should have been seeded (chunk 2 is the last)
            assertEquals(1, getActiveChunks(order).size(), "No pre-fetch when current chunk is the last in the tree");
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    // Pre-fetch past old range (no-op)
    // ═════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("No pre-fetch when next chunk is past old range")
    class PrefetchPastOldRangeTests {

        @Test
        @DisplayName("Next chunk past oldLastLeafPath → no pre-fetch")
        void noPreFetchPastOldRange() throws Exception {
            // Set old range to cover only chunk 1's leaves (1023–1534).
            // Chunk 2's leaves (1535–2046) are past oldLastLeafPath=1534.
            final var order = new TopToBottomTraversalOrder();
            order.start(CHUNK_FIRST, 1534L, CHUNK_FIRST, CHUNK_LAST);

            // Stall in chunk 1
            triggerStall(order);

            // Pre-fetch should NOT be seeded: chunk 2 is past oldLastLeafPath
            assertEquals(
                    1, getActiveChunks(order).size(), "No pre-fetch when next chunk's leaves are past oldLastLeafPath");
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    // Promotion at end of tree
    // ═════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Termination when last chunk completes")
    class TerminationTests {

        @Test
        @DisplayName("Completing the last chunk returns INVALID_PATH")
        void lastChunkReturnsInvalidPath() {
            final var order = new TopToBottomTraversalOrder();
            order.start(CHUNK_FIRST, CHUNK_LAST, CHUNK_FIRST, CHUNK_LAST);

            final List<Long> leaves = driveAllDirty(order);

            assertEquals(CHUNK_LAST - CHUNK_FIRST + 1, leaves.size(), "All leaves must be sent before termination");
            assertEquals(INVALID_PATH, order.getNextLeafPathToSend(), "Must return INVALID_PATH after last chunk");
            assertEquals(INVALID_PATH, order.getNextLeafPathToSend(), "INVALID_PATH must be idempotent");
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    // Simple mode with pre-fetch enabled
    // ═════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Simple mode ignores pre-fetch")
    class SimpleModeTests {

        @Test
        @DisplayName("Simple mode tree with depth=1: no chunks, all leaves sent in order")
        void simpleModeNoChunks() throws Exception {
            final var order = new TopToBottomTraversalOrder();
            order.start(SIMPLE_FIRST, SIMPLE_LAST, SIMPLE_FIRST, SIMPLE_LAST);

            // activeChunks must be empty in simple mode
            assertTrue(getActiveChunks(order).isEmpty(), "activeChunks must be empty in simple mode");

            // All leaves sent directly
            final List<Long> leaves = new ArrayList<>();
            long leaf;
            while ((leaf = order.getNextLeafPathToSend()) != INVALID_PATH) {
                leaves.add(leaf);
            }
            assertEquals(SIMPLE_LAST - SIMPLE_FIRST + 1, leaves.size());
            assertEquals(SIMPLE_FIRST, leaves.get(0));
            assertEquals(SIMPLE_LAST, leaves.get(leaves.size() - 1));
        }

        @Test
        @DisplayName("Simple mode: getNextInternalPathToSend always returns INVALID_PATH")
        void simpleModeNoInternals() {
            final var order = new TopToBottomTraversalOrder();
            order.start(SIMPLE_FIRST, SIMPLE_LAST, SIMPLE_FIRST, SIMPLE_LAST);

            assertEquals(INVALID_PATH, order.getNextInternalPathToSend());
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    // Unbounded mode
    // ═════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Unbounded pre-fetch depth")
    class UnboundedModeTests {

        @Test
        @DisplayName("Unbounded depth: all-dirty completes correctly")
        void unboundedAllDirtyCompletes() {
            final var order = new TopToBottomTraversalOrder();
            order.start(CHUNK_FIRST, CHUNK_LAST, CHUNK_FIRST, CHUNK_LAST);

            final List<Long> leaves = driveAllDirty(order);
            assertEquals(CHUNK_LAST - CHUNK_FIRST + 1, leaves.size());
        }

        @Test
        @DisplayName("Unbounded depth: all-clean completes correctly")
        void unboundedAllCleanCompletes() {
            final var order = new TopToBottomTraversalOrder();
            order.start(CHUNK_FIRST, CHUNK_LAST, CHUNK_FIRST, CHUNK_LAST);

            final List<Long> leaves = driveAllClean(order);
            assertTrue(leaves.isEmpty());
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    // Before-old-range with pre-fetch
    // ═════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Before-old-range leaves don't trigger pre-fetch")
    class BeforeOldRangeTests {

        @Test
        @DisplayName("Leaves before old range sent immediately, no internals or pre-fetch")
        void beforeOldRangeNoInternals() throws Exception {
            // Teacher range wider than learner's old range: leaves before old range are dirty
            final var order = new TopToBottomTraversalOrder();
            order.start(1100L, CHUNK_LAST, CHUNK_FIRST, CHUNK_LAST);

            // Leaves 1023–1099 are before oldFirstLeafPath=1100, sent immediately
            final List<Long> immediateDirty = new ArrayList<>();
            long leaf;
            while ((leaf = order.getNextLeafPathToSend()) != PATH_NOT_AVAILABLE_YET && leaf != INVALID_PATH) {
                immediateDirty.add(leaf);
                if (leaf >= 1099L) break;
            }
            assertFalse(immediateDirty.isEmpty(), "Leaves before old range must be sent immediately");
            assertTrue(
                    immediateDirty.stream().allMatch(p -> p < 1100L),
                    "All immediate leaves must be before oldFirstLeafPath");

            // During this phase, no internals were sent
            // (getNextInternalPathToSend returns INVALID_PATH when currentLeafPath < oldFirstLeafPath)
            // This means no stall, no pre-fetch
            // Deque should still have just 1 chunk
            assertEquals(1, getActiveChunks(order).size(), "No pre-fetch during before-old-range phase");
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    // End-to-end with pre-fetch benefit
    // ═════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("End-to-end: pre-fetch correctness on multi-chunk tree")
    class EndToEndTests {

        // Moderate multi-chunk tree: firstLeafPath=100_000 (rank 17),
        // lastLeafPath=200_000 (rank 18), = 2 * firstLeafPath. chunkRootRank = 1,
        // so the tree spans several chunks including a rank-change boundary, with
        // ~100K leaves — large enough to exercise repeated chunk transitions but
        // small enough to keep heap usage modest in CI.
        private static final long E2E_FIRST = 100_000L;
        private static final long E2E_LAST = 200_000L; // 2 * E2E_FIRST

        @Test
        @DisplayName("Multi-chunk tree completes correctly with depth=1 (all dirty)")
        void multiChunkAllDirtyWithPrefetch() {
            final var order = new TopToBottomTraversalOrder();
            order.start(E2E_FIRST, E2E_LAST, E2E_FIRST, E2E_LAST);

            // Streaming verifier checks count, first, last, and strict ascending order
            // (which implies no duplicates) without materializing the leaf list.
            final long count = driveAllDirtyStreaming(order, E2E_FIRST, E2E_LAST);
            assertEquals(E2E_LAST - E2E_FIRST + 1, count, "All leaves must be sent");
        }

        @Test
        @DisplayName("Multi-chunk tree completes correctly with all-clean and depth=1")
        void multiChunkAllCleanWithPrefetch() {
            final var order = new TopToBottomTraversalOrder();
            order.start(E2E_FIRST, E2E_LAST, E2E_FIRST, E2E_LAST);

            final List<Long> leaves = driveAllClean(order);
            assertTrue(leaves.isEmpty(), "No leaves sent in all-clean tree");
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    // Rank-change boundary: no pre-fetch across the boundary
    // ═════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Rank-change boundary blocks pre-fetch")
    class RankChangeBoundaryTests {

        // Mixed-rank tree: firstLeafPath=1100 (rank 10), lastLeafPath=2200 (rank 11)
        // chunkRootRank = max(1, 11-23) = 1
        // Chunks:
        //   A: root=1, chunkLastRank=10, leaves 1100–1534
        //   B: root=2, chunkLastRank=10, leaves 1535–2046
        //   C: root=1, chunkLastRank=11, leaves 2047–2200
        // A and C share chunkRootPath=1 — this is the collision that must be prevented.
        private static final long MR_FIRST = 1100L;
        private static final long MR_LAST = 2200L;

        @Test
        @DisplayName("Depth=2 does not pre-fetch across the rank-change boundary (no duplicate roots)")
        void noPrefetchAcrossRankChangeBoundary() throws Exception {
            final var order = new TopToBottomTraversalOrder();
            order.start(MR_FIRST, MR_LAST, MR_FIRST, MR_LAST);

            // Stall in chunk A to trigger pre-fetch
            triggerStall(order);

            final var chunks = getActiveChunks(order);
            // First stall: chunk B seeded. Deque = [A, B].
            assertEquals(2, chunks.size(), "First stall must seed chunk B");

            // Drain B's pre-fetched internals so the queue is empty
            drainInternals(order);

            // Second stall: B is the last in the deque. B.chunkRootPath + 1 crosses
            // the rank boundary, so computeNextChunkForPrefetch must return null.
            assertEquals(PATH_NOT_AVAILABLE_YET, order.getNextLeafPathToSend());
            assertEquals(
                    2, chunks.size(), "Second stall must NOT seed chunk C (rank-change boundary blocks pre-fetch)");

            // Verify no duplicate chunkRootPath in the deque
            final Set<Long> roots = new HashSet<>();
            for (final var chunk : chunks) {
                assertTrue(
                        roots.add(chunk.chunkRootPath),
                        "Duplicate chunkRootPath " + chunk.chunkRootPath + " found in active chunks");
            }
        }

        @Test
        @DisplayName("Mixed-rank tree with depth=2 completes correctly despite boundary block")
        void mixedRankTreeCompletesWithPrefetch() {
            final var order = new TopToBottomTraversalOrder();
            order.start(MR_FIRST, MR_LAST, MR_FIRST, MR_LAST);

            final List<Long> leaves = driveAllDirty(order);

            assertEquals(MR_LAST - MR_FIRST + 1, leaves.size(), "All leaves must be sent");
            assertEquals(MR_FIRST, leaves.get(0));
            assertEquals(MR_LAST, leaves.get(leaves.size() - 1));
            for (int i = 1; i < leaves.size(); i++) {
                assertTrue(leaves.get(i) > leaves.get(i - 1), "Leaves must be strictly ascending");
            }
        }

        @Test
        @DisplayName("Unbounded depth also blocks at rank-change boundary")
        void unboundedBlocksAtRankChange() throws Exception {
            final var order = new TopToBottomTraversalOrder();
            order.start(MR_FIRST, MR_LAST, MR_FIRST, MR_LAST);

            // Stall repeatedly to try to seed as many chunks as possible
            triggerStall(order);
            drainInternals(order);
            order.getNextLeafPathToSend(); // second stall attempt

            final var chunks = getActiveChunks(order);
            // Even with unbounded depth, deque must not exceed 2 (A + B)
            // because the boundary blocks C
            assertTrue(
                    chunks.size() <= 2,
                    "Unbounded depth must still block pre-fetch at rank-change boundary, got " + chunks.size());

            // Verify no duplicate roots
            final Set<Long> roots = new HashSet<>();
            for (final var chunk : chunks) {
                assertTrue(
                        roots.add(chunk.chunkRootPath),
                        "Duplicate chunkRootPath " + chunk.chunkRootPath + " found in active chunks");
            }
        }

        @Test
        @DisplayName("Chunk C (post-boundary) is correctly created via promotion, not pre-fetch")
        void postBoundaryChunkCreatedViaPromotion() {
            // Drive to completion and verify chunk C's leaves are handled.
            // Leaves 2047–2200 are in chunk C (post-boundary, root=1, rank=11).
            // These must be sent despite C never being pre-fetched.
            final var order = new TopToBottomTraversalOrder();
            order.start(MR_FIRST, MR_LAST, MR_FIRST, MR_LAST);

            final List<Long> leaves = driveAllDirty(order);
            final Set<Long> leafSet = new HashSet<>(leaves);

            // Verify chunk C's leaves are present
            for (long p = 2047L; p <= MR_LAST; p++) {
                assertTrue(leafSet.contains(p), "Post-boundary leaf " + p + " (chunk C) must be sent");
            }
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    // Stall flag priority — pre-fetch internals gated by currentChunkStalled
    // ═════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Stall flag — pre-fetch internals yield to current-chunk leaves")
    class StallFlagPriorityTests {

        @Test
        @DisplayName("Pre-fetch internals are returned while current chunk is stalled")
        void prefetchInternalsAvailableDuringStall() {
            final var order = new TopToBottomTraversalOrder();
            order.start(CHUNK_FIRST, CHUNK_LAST, CHUNK_FIRST, CHUNK_LAST);

            // Drain chunk 1 internals, stall to seed chunk 2
            drainInternals(order);
            assertEquals(PATH_NOT_AVAILABLE_YET, order.getNextLeafPathToSend());

            // currentChunkStalled = true → pre-fetch internals must be available
            final long prefetchInternal = order.getNextInternalPathToSend();
            assertNotEquals(
                    INVALID_PATH,
                    prefetchInternal,
                    "Pre-fetch internals must be returned while current chunk is stalled");
            assertTrue(
                    prefetchInternal >= CHUNK2_INIT_LO && prefetchInternal <= CHUNK2_INIT_HI,
                    "Returned internal must be from chunk 2's initial range, got " + prefetchInternal);
        }

        @Test
        @DisplayName("Pre-fetch internals blocked after stall resolves, even though queue is non-empty")
        void prefetchInternalsBlockedAfterStallResolves() {
            final var order = new TopToBottomTraversalOrder();
            order.start(CHUNK_FIRST, CHUNK_LAST, CHUNK_FIRST, CHUNK_LAST);

            // Drain chunk 1 internals, stall to seed chunk 2
            drainInternals(order);
            assertEquals(PATH_NOT_AVAILABLE_YET, order.getNextLeafPathToSend());

            // Take one pre-fetch internal to confirm they're available
            assertNotEquals(INVALID_PATH, order.getNextInternalPathToSend());

            // Resolve the stall: path 127 (rank 7) → someDirtyPaths, covers leaves 1023–1030.
            // The leaf is now sendable, but currentChunkStalled is still true — it's only
            // cleared by getNextLeafPathToSend(), not by nodeReceived().
            order.nodeReceived(127L, false);

            // Production call order: internals checked BEFORE leaves. The flag is still
            // true at this point, so pre-fetch internals are still returned. This is the
            // expected transient window — one extra pre-fetch internal may be sent between
            // "stall resolved" and "flag cleared".
            final long transientInternal = order.getNextInternalPathToSend();
            assertNotEquals(
                    INVALID_PATH,
                    transientInternal,
                    "Pre-fetch internals must still be returned in the transient window "
                            + "before getNextLeafPathToSend clears the flag");

            // Now the sender falls through to leaves — this clears currentChunkStalled
            final long leaf = order.getNextLeafPathToSend();
            assertEquals(CHUNK_FIRST, leaf, "Leaf 1023 must be sent after stall resolves");

            // After the flag is cleared: chunk 2's queue still has internals, but
            // getNextInternalPathToSend must return INVALID_PATH (skip pre-fetch).
            assertEquals(
                    INVALID_PATH,
                    order.getNextInternalPathToSend(),
                    "Pre-fetch internals must be blocked after getNextLeafPathToSend clears the flag");
        }

        @Test
        @DisplayName("Pre-fetch internals resume when current chunk stalls again")
        void prefetchInternalsResumeOnNextStall() {
            final var order = new TopToBottomTraversalOrder();
            order.start(CHUNK_FIRST, CHUNK_LAST, CHUNK_FIRST, CHUNK_LAST);

            // Drain chunk 1 internals, stall to seed chunk 2
            drainInternals(order);
            assertEquals(PATH_NOT_AVAILABLE_YET, order.getNextLeafPathToSend());

            // Resolve stall: dirty path 127 covers leaves 1023–1030
            order.nodeReceived(127L, false);

            // Send leaves 1023–1030 (all have dirty parent 127)
            for (long expected = CHUNK_FIRST; expected <= 1030L; expected++) {
                assertEquals(
                        expected,
                        order.getNextLeafPathToSend(),
                        "Leaf " + expected + " must be sent (dirty parent 127)");
            }

            // Leaf 1031's parents (515, 257, 128) are NOT in someDirtyPaths → stall again
            assertEquals(
                    PATH_NOT_AVAILABLE_YET,
                    order.getNextLeafPathToSend(),
                    "Leaf 1031 must stall (no dirty parent known)");

            // Flag is true again → pre-fetch internals must be available
            final long prefetchInternal = order.getNextInternalPathToSend();
            assertNotEquals(
                    INVALID_PATH, prefetchInternal, "Pre-fetch internals must resume after current chunk stalls again");
        }

        @Test
        @DisplayName("Current chunk's own drill-down internals returned regardless of stall flag")
        void currentChunkDrillDownAlwaysReturned() {
            final var order = new TopToBottomTraversalOrder();
            order.start(CHUNK_FIRST, CHUNK_LAST, CHUNK_FIRST, CHUNK_LAST);

            // Drain chunk 1 internals, stall to seed chunk 2
            drainInternals(order);
            assertEquals(PATH_NOT_AVAILABLE_YET, order.getNextLeafPathToSend());

            // Resolve stall so flag = false
            order.nodeReceived(127L, false);
            assertEquals(CHUNK_FIRST, order.getNextLeafPathToSend());

            // Now inject a dirty response for a chunk-1 internal that triggers drill-down.
            // Path 31 (rank 5 < threshold 7) → enqueues rank-8 children 255–262 into chunk 1's queue.
            order.nodeReceived(31L, false);

            // Even with flag = false, current chunk's own internals must always be returned
            final long drillDown = order.getNextInternalPathToSend();
            assertNotEquals(
                    INVALID_PATH,
                    drillDown,
                    "Current chunk's drill-down internals must be returned regardless of stall flag");
            assertTrue(
                    drillDown >= 255L && drillDown <= 262L,
                    "Drill-down internal must be from chunk 1's range (255–262), got " + drillDown);
        }

        @Test
        @DisplayName("Flag cleared on chunk promotion — new chunk starts unstalled")
        void flagClearedOnPromotion() {
            final var order = new TopToBottomTraversalOrder();
            order.start(CHUNK_FIRST, CHUNK_LAST, CHUNK_FIRST, CHUNK_LAST);

            // Drain chunk 1 internals, stall to seed chunk 2
            drainInternals(order);
            assertEquals(PATH_NOT_AVAILABLE_YET, order.getNextLeafPathToSend());
            // flag = true here

            // Complete chunk 1: feed all initial internals as clean → all leaves skipped → promotion
            for (long p = CHUNK1_INIT_LO; p <= CHUNK1_INIT_HI; p++) {
                order.nodeReceived(p, true);
            }
            assertEquals(PATH_NOT_AVAILABLE_YET, order.getNextLeafPathToSend());
            // Promotion happened, flag = false

            // Chunk 2 is now current. Its initial internals (from pre-fetch) should be
            // returned as current-chunk internals (head of deque), not as pre-fetch.
            // There are no further pre-fetched chunks. Verify internals are available.
            final long internal = order.getNextInternalPathToSend();
            assertNotEquals(INVALID_PATH, internal, "After promotion, new current chunk's internals must be available");
        }
    }
}
