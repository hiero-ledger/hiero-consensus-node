// SPDX-License-Identifier: Apache-2.0
package com.swirlds.virtualmap.internal.reconnect;

import static com.swirlds.virtualmap.internal.Path.INVALID_PATH;
import static com.swirlds.virtualmap.internal.reconnect.NodeTraversalOrder.PATH_NOT_AVAILABLE_YET;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.swirlds.virtualmap.internal.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link TopToBottomTraversalOrder}.
 *
 * <p>Tree path encoding used throughout: root=0, left child of N = 2N+1, right child = 2N+2.
 * Rank = floor(log2(path+1)).
 *
 * <p>Two reference trees are used:
 * <ul>
 *   <li><b>Simple-mode tree</b>: rank-9 leaves, firstLeafPath=511, lastLeafPath=1022 (512 leaves).
 *       firstLeafRank=9 &lt; 10 → simple mode (no internal queries).
 *   <li><b>Chunk-mode tree</b>: rank-10 leaves, firstLeafPath=1023, lastLeafPath=2046 (1024 leaves).
 *       chunkRootRank=1 → two chunks:
 *       <ul>
 *         <li>Chunk 1: root=path 1, leaves 1023–1534; initial internals at rank 5: paths 31–46.</li>
 *         <li>Chunk 2: root=path 2, leaves 1535–2046; initial internals at rank 5: paths 47–62.</li>
 *       </ul>
 *       RANK_STEP=3, chunkLastRank=10; someDirtyPaths threshold: rank &ge; 7.
 *       A dirty rank-5 path enqueues its 8 rank-8 grand-children (e.g., dirty path 31 → paths 255–262).
 * </ul>
 */
@DisplayName("TopToBottomTraversalOrder")
class TopToBottomTraversalOrderTest {

    // ── Simple-mode tree (rank 9, firstLeafRank < 10) ────────────────────────
    private static final long SIMPLE_FIRST = 511L; // 2^9  - 1
    private static final long SIMPLE_LAST = 2 * SIMPLE_FIRST; // 2^10 - 2

    // ── Chunk-mode tree (rank 10) ─────────────────────────────────────────────
    private static final long CHUNK_FIRST = 1023L; // 2^10 - 1
    private static final long CHUNK_LAST = 2 * CHUNK_FIRST; // 2^11 - 2

    // Chunk boundaries: last leaf of chunk-1 = getRightGrandChildPath(1, 9) = 1534
    // CHUNK1_FIRST_LEAF is a learner-side first-leaf used to define an old range whose
    // last leaf equals 2 * 767 = 1534, matching the chunk-1 boundary exactly.
    private static final long OTHER_FIRST_LEAF = 767L;
    private static final long OTHER_LAST_LEAF = 2 * OTHER_FIRST_LEAF; // 1534

    // Initial internals for each chunk (rank 5, 16 paths per chunk)
    private static final long CHUNK1_INIT_LO = 31L;
    private static final long CHUNK1_INIT_HI = 46L;
    private static final long CHUNK2_INIT_LO = 47L;
    private static final long CHUNK2_INIT_HI = 62L;

    // RANK_STEP grand-children of dirty path 31 (rank 8, paths 255–262)
    private static final long DIRTY_31_CHILDREN_LO = 255L;
    private static final long DIRTY_31_CHILDREN_HI = 262L;

    // ── Helpers ───────────────────────────────────────────────────────────────

    /** Collect every path currently available from getNextInternalPathToSend(). */
    private static List<Long> drainInternals(final TopToBottomTraversalOrder order) {
        final List<Long> result = new ArrayList<>();
        long path;
        while ((path = order.getNextInternalPathToSend()) != INVALID_PATH) {
            result.add(path);
        }
        return result;
    }

    /**
     * Drive the algorithm to INVALID_PATH, feeding every internal response as dirty.
     * Returns the ordered list of leaf paths that were sent to the teacher.
     */
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

    /**
     * Drive the algorithm to INVALID_PATH, feeding every internal response as clean.
     * Returns the ordered list of leaf paths that were sent to the teacher (usually empty).
     */
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

    // ═════════════════════════════════════════════════════════════════════════
    // Group 1 — Simple mode (firstLeafRank < 10)
    // ═════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Group 1 — Simple mode")
    class SimpleModeTests {

        @Test
        @DisplayName("1.1 — All leaves returned in order, no internals ever returned")
        void allLeavesReturnedNoInternals() {
            final var order = new TopToBottomTraversalOrder();
            order.start(SIMPLE_FIRST, SIMPLE_LAST, SIMPLE_FIRST, SIMPLE_LAST);

            assertEquals(
                    INVALID_PATH, order.getNextInternalPathToSend(), "Simple mode must never return an internal path");

            final List<Long> leaves = new ArrayList<>();
            long path;
            while ((path = order.getNextLeafPathToSend()) != INVALID_PATH) {
                assertNotEquals(PATH_NOT_AVAILABLE_YET, path, "Simple mode must never stall on leaves");
                leaves.add(path);
            }

            assertEquals(512, leaves.size(), "All 512 leaves must be returned");
            for (int i = 0; i < leaves.size(); i++) {
                assertEquals(SIMPLE_FIRST + i, leaves.get(i), "Leaves must be returned in ascending order");
            }
        }

        @Test
        @DisplayName("1.2 — Boundary: rank 9 is simple mode, rank 10 is chunk mode")
        void simpleModeThreshold() {
            // rank-9 firstLeafPath → simple mode
            final var order = new TopToBottomTraversalOrder();
            order.start(SIMPLE_FIRST, SIMPLE_LAST, SIMPLE_FIRST, SIMPLE_LAST);
            assertEquals(INVALID_PATH, order.getNextInternalPathToSend(), "Rank-9 tree must be in simple mode");

            // rank-10 firstLeafPath → chunk mode (initial internals are available)
            var chunk = new TopToBottomTraversalOrder();
            chunk.start(CHUNK_FIRST, CHUNK_LAST, CHUNK_FIRST, CHUNK_LAST);
            assertNotEquals(
                    INVALID_PATH,
                    chunk.getNextInternalPathToSend(),
                    "Rank-10 tree must be in chunk mode with initial internals");
        }

        @Test
        @DisplayName("1.3 — nodeReceived() calls are ignored in simple mode")
        void nodeReceivedIgnoredInSimpleMode() {
            final var order = new TopToBottomTraversalOrder();
            order.start(SIMPLE_FIRST, SIMPLE_LAST, SIMPLE_FIRST, SIMPLE_LAST);

            // Call nodeReceived with various paths — must not affect leaf output
            order.nodeReceived(0, true);
            order.nodeReceived(SIMPLE_FIRST, false);
            order.nodeReceived(1L, true);

            final List<Long> leaves = new ArrayList<>();
            long path;
            while ((path = order.getNextLeafPathToSend()) != INVALID_PATH) {
                leaves.add(path);
            }

            assertEquals(512, leaves.size(), "nodeReceived must not affect leaf count in simple mode");
            assertEquals(SIMPLE_FIRST, leaves.get(0), "First leaf must be unchanged");
            assertEquals(SIMPLE_LAST, leaves.get(511), "Last leaf must be unchanged");
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    // Group 2 — Initialization / start() side effects
    // ═════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Group 2 — Initialization")
    class InitializationTests {

        @Test
        @DisplayName("2.1 — Initial internals seeded at correct rank with correct count")
        void initialInternalsCorrectRankAndCount() {
            final var order = new TopToBottomTraversalOrder();
            order.start(CHUNK_FIRST, CHUNK_LAST, CHUNK_FIRST, CHUNK_LAST);

            final List<Long> internals = drainInternals(order);

            // chunkHeight = 10 - 1 = 9, skipRanks = 9/2 = 4 → 2^4 = 16 initial internals
            assertEquals(16, internals.size(), "Must seed 2^(chunkHeight/2) = 16 initial internals");
            // All must be at rank chunkRootRank + skipRanks = 1 + 4 = 5
            for (long p : internals) {
                assertEquals(5, Path.getRank(p), "All initial internals must be at rank 5, got path " + p);
            }
            // Must equal paths 31..46
            assertEquals(CHUNK1_INIT_LO, internals.get(0));
            assertEquals(CHUNK1_INIT_HI, internals.get(internals.size() - 1));
        }

        @Test
        @DisplayName("2.2 — Leaf iteration starts at firstLeafPath")
        void leafIterationStartsAtFirstLeaf() {
            final var order = new TopToBottomTraversalOrder();
            // Use ranges where all leaves before oldFirstLeafPath are sent immediately
            order.start(CHUNK_FIRST, CHUNK_LAST, CHUNK_FIRST, CHUNK_LAST);

            // Feed one dirty near-leaf-rank node so a leaf can be sent
            order.nodeReceived(127L, false); // rank 7 >= 7 → someDirtyPaths, covers leaves 1023-1030

            long firstLeaf = order.getNextLeafPathToSend();
            assertEquals(CHUNK_FIRST, firstLeaf, "First returned leaf must be firstLeafPath");
        }

        @Test
        @DisplayName("2.3 — Leaves before old range sent immediately without internal queries")
        void leavesBeforeOldRangeSentImmediately() {
            // oldFirstLeafPath = 1100, so leaves 1023-1099 are before old range
            final var order = new TopToBottomTraversalOrder();
            order.start(1100L, 2200L, CHUNK_FIRST, CHUNK_LAST);

            assertEquals(
                    INVALID_PATH,
                    order.getNextInternalPathToSend(),
                    "Leaves before old range must be sent before internal nodes");

            // Leaves before old range must be returned without any nodeReceived() calls
            for (long expected = CHUNK_FIRST; expected < 1100L; expected++) {
                long leaf = order.getNextLeafPathToSend();
                assertEquals(expected, leaf, "Leaf " + expected + " (before old range) must be sent immediately");
            }
        }

        @Test
        @DisplayName("2.4 — Leaves after old range sent immediately without internal queries")
        void leavesAfterOldRangeSentImmediately() {
            // Learner had [767, 1534] (= 2*767); teacher has [1023, 2046].
            // Teacher leaves 1535-2046 are after the old range and must be sent immediately.
            final var order = new TopToBottomTraversalOrder();
            order.start(OTHER_FIRST_LEAF, OTHER_LAST_LEAF, CHUNK_FIRST, CHUNK_LAST);

            List<Long> leaves = driveAllDirty(order);

            // All 512 leaves after old range (1535–2046) must appear in results
            assertTrue(
                    leaves.containsAll(rangeClosed(OTHER_LAST_LEAF + 1, CHUNK_LAST)),
                    "All leaves after old range must be sent");
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    // Group 3 — getNextInternalPathToSend() conditions
    // ═════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Group 3 — getNextInternalPathToSend()")
    class GetNextInternalTests {

        @Test
        @DisplayName("3.1 — Returns INVALID_PATH after all initial internals drained (no dirty responses)")
        void invalidPathWhenQueueEmpty() {
            final var order = new TopToBottomTraversalOrder();
            order.start(CHUNK_FIRST, CHUNK_LAST, CHUNK_FIRST, CHUNK_LAST);

            drainInternals(order); // drain 31-46
            assertEquals(
                    INVALID_PATH,
                    order.getNextInternalPathToSend(),
                    "Must return INVALID_PATH when internals queue is empty");
        }

        @Test
        @DisplayName("3.2 — Returns INVALID_PATH when currentLeafPath is before old range")
        void invalidPathWhenLeafBeforeOldRange() {
            // currentLeafPath starts at CHUNK_FIRST=1023 < oldFirstLeafPath=1100
            final var order = new TopToBottomTraversalOrder();
            order.start(1100L, 2200L, CHUNK_FIRST, CHUNK_LAST);

            assertEquals(
                    INVALID_PATH,
                    order.getNextInternalPathToSend(),
                    "Must return INVALID_PATH when currentLeafPath < oldFirstLeafPath");
        }

        @Test
        @DisplayName("3.3 — Returns INVALID_PATH when currentLeafPath is past old range")
        void invalidPathWhenLeafAfterOldRange() {
            // oldLastLeafPath=500 is entirely below CHUNK_FIRST=1023
            // so currentLeafPath (1023) > oldLastLeafPath (200) from the start
            final var order = new TopToBottomTraversalOrder();
            order.start(100L, 200L, CHUNK_FIRST, CHUNK_LAST);

            assertEquals(
                    INVALID_PATH,
                    order.getNextInternalPathToSend(),
                    "Must return INVALID_PATH when currentLeafPath > oldLastLeafPath");
        }

        @Test
        @DisplayName("3.4 — Dirty response enqueues grand-children returned by subsequent calls")
        void dirtyResponseEnqueuesGrandChildren() {
            final var order = new TopToBottomTraversalOrder();
            order.start(CHUNK_FIRST, CHUNK_LAST, CHUNK_FIRST, CHUNK_LAST);

            // Drain and discard initial internals, then inject a dirty response for path 31
            drainInternals(order);
            // nodeReceived(31, false): rank 5 < 7 → enqueue grand-children at rank 8 (255-262)
            order.nodeReceived(31L, false);

            final List<Long> newInternals = drainInternals(order);
            assertEquals(8, newInternals.size(), "Dirty rank-5 node must enqueue 2^RANK_STEP=8 grand-children");
            assertEquals(DIRTY_31_CHILDREN_LO, newInternals.get(0));
            assertEquals(DIRTY_31_CHILDREN_HI, newInternals.get(newInternals.size() - 1));
            for (long p : newInternals) {
                assertEquals(8, Path.getRank(p), "Grand-children must be at rank 5+3=8, got path " + p);
            }
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    // Group 4 — nodeReceived() routing
    // ═════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Group 4 — nodeReceived() routing")
    class NodeReceivedTests {

        @Test
        @DisplayName("4.1 — Root response (path=0) is silently ignored")
        void rootResponseIgnored() {
            final var order = new TopToBottomTraversalOrder();
            order.start(CHUNK_FIRST, CHUNK_LAST, CHUNK_FIRST, CHUNK_LAST);

            drainInternals(order); // empty the queue
            order.nodeReceived(0L, false);

            assertEquals(
                    INVALID_PATH,
                    order.getNextInternalPathToSend(),
                    "Root nodeReceived must not add any internals to the queue");
        }

        @Test
        @DisplayName("4.2 — Leaf response is silently ignored")
        void leafResponseIgnored() {
            final var order = new TopToBottomTraversalOrder();
            order.start(CHUNK_FIRST, CHUNK_LAST, CHUNK_FIRST, CHUNK_LAST);

            drainInternals(order);
            order.nodeReceived(CHUNK_FIRST, false); // CHUNK_FIRST is a leaf path

            assertEquals(
                    INVALID_PATH,
                    order.getNextInternalPathToSend(),
                    "Leaf nodeReceived must not add any internals to the queue");
        }

        @Test
        @DisplayName("4.3 — Clean internal added to cleanPaths, causing its leaves to be skipped")
        void cleanInternalSkipsLeaves() {
            final var order = new TopToBottomTraversalOrder();
            order.start(CHUNK_FIRST, CHUNK_LAST, CHUNK_FIRST, CHUNK_LAST);

            drainInternals(order);
            // Path 255 (rank 8) covers leaves 1023-1026 (4 leaves, 2 ranks above leaf rank 10)
            order.nodeReceived(255L, true);

            // These leaves must be skipped; their parent 255 is clean
            long leaf = order.getNextLeafPathToSend();
            // 1023-1026 are skipped; next expected is either PATH_NOT_AVAILABLE_YET or 1027
            assertNotEquals(1023L, leaf, "Leaf 1023 must be skipped (clean parent 255)");
            assertNotEquals(1024L, leaf, "Leaf 1024 must be skipped (clean parent 255)");
            assertNotEquals(1025L, leaf, "Leaf 1025 must be skipped (clean parent 255)");
            assertNotEquals(1026L, leaf, "Leaf 1026 must be skipped (clean parent 255)");
        }

        @Test
        @DisplayName("4.4 — Dirty internal far from leaf rank enqueues RANK_STEP grand-children")
        void dirtyFarFromLeafEnqueuesGrandChildren() {
            final var order = new TopToBottomTraversalOrder();
            order.start(CHUNK_FIRST, CHUNK_LAST, CHUNK_FIRST, CHUNK_LAST);

            drainInternals(order);
            // Path 31 is at rank 5, chunkLastRank=10, 5 < 10-3=7 → enqueue grand-children
            order.nodeReceived(31L, false);

            final List<Long> newInternals = drainInternals(order);
            assertEquals(8, newInternals.size());
            for (long p : newInternals) {
                assertEquals(8, Path.getRank(p), "Grand-children must be 3 ranks below rank 5: rank 8");
                assertTrue(p >= DIRTY_31_CHILDREN_LO && p <= DIRTY_31_CHILDREN_HI);
            }
        }

        @Test
        @DisplayName("4.5 — Dirty internal near leaf rank added to someDirtyPaths, leaf becomes sendable")
        void dirtyNearLeafRankEnableSendingLeaf() {
            final var order = new TopToBottomTraversalOrder();
            order.start(CHUNK_FIRST, CHUNK_LAST, CHUNK_FIRST, CHUNK_LAST);

            drainInternals(order);
            // Path 127 (rank 7 >= 10-3=7) → someDirtyPaths; covers leaves 1023-1030
            order.nodeReceived(127L, false);

            // No new internals queued
            assertEquals(
                    INVALID_PATH,
                    order.getNextInternalPathToSend(),
                    "Near-leaf dirty node must not enqueue grand-children");

            // Leaf 1023 has dirty ancestor 127 within RANK_STEP → must be sendable
            long leaf = order.getNextLeafPathToSend();
            assertEquals(CHUNK_FIRST, leaf, "Leaf 1023 must be sendable when ancestor 127 is dirty");
        }

        @Test
        @DisplayName("4.6 — Boundary: rank == chunkLastRank-RANK_STEP goes to someDirtyPaths, rank below goes to queue")
        void rankBoundaryRouting() {
            // rank 7 (= 10-3) → someDirtyPaths (no new internals)
            final var order7 = new TopToBottomTraversalOrder();
            order7.start(CHUNK_FIRST, CHUNK_LAST, CHUNK_FIRST, CHUNK_LAST);
            drainInternals(order7);
            order7.nodeReceived(127L, false); // rank 7
            assertEquals(
                    INVALID_PATH,
                    order7.getNextInternalPathToSend(),
                    "Rank 7 (==threshold) must go to someDirtyPaths, not enqueue children");

            // rank 6 (= 10-4, below threshold) → enqueue grand-children
            final var order6 = new TopToBottomTraversalOrder();
            order6.start(CHUNK_FIRST, CHUNK_LAST, CHUNK_FIRST, CHUNK_LAST);
            drainInternals(order6);
            order6.nodeReceived(63L, false); // rank 6
            assertNotEquals(
                    INVALID_PATH,
                    order6.getNextInternalPathToSend(),
                    "Rank 6 (<threshold) must enqueue grand-children");
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    // Group 5 — getNextLeafPathToSend() basic behaviour
    // ═════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Group 5 — getNextLeafPathToSend() basics")
    class GetNextLeafBasicTests {

        @Test
        @DisplayName("5.1 — Unknown parent status returns PATH_NOT_AVAILABLE_YET")
        void unknownParentReturnsNotAvailable() {
            final var order = new TopToBottomTraversalOrder();
            order.start(CHUNK_FIRST, CHUNK_LAST, CHUNK_FIRST, CHUNK_LAST);

            drainInternals(order); // no nodeReceived calls, so no dirty/clean info

            long leaf = order.getNextLeafPathToSend();
            assertEquals(
                    PATH_NOT_AVAILABLE_YET,
                    leaf,
                    "Leaf with no known parent status must return PATH_NOT_AVAILABLE_YET");
        }

        @Test
        @DisplayName("5.2 — Dirty parent within RANK_STEP causes leaf to be sent")
        void dirtyParentWithinRankStepSendsLeaf() {
            final var order = new TopToBottomTraversalOrder();
            order.start(CHUNK_FIRST, CHUNK_LAST, CHUNK_FIRST, CHUNK_LAST);

            drainInternals(order);
            // Path 255 (rank 8, 2 hops from leaf 1023) → someDirtyPaths
            order.nodeReceived(255L, false);

            long leaf = order.getNextLeafPathToSend();
            assertEquals(CHUNK_FIRST, leaf, "Leaf 1023 must be sent when ancestor 255 (rank 8) is dirty");
        }

        @Test
        @DisplayName("5.3 — Clean parent causes leaf to be skipped")
        void cleanParentSkipsLeaf() {
            final var order = new TopToBottomTraversalOrder();
            order.start(CHUNK_FIRST, CHUNK_LAST, CHUNK_FIRST, CHUNK_LAST);

            drainInternals(order);
            // Clean path 255 covers leaves 1023-1026
            order.nodeReceived(255L, true);

            // 1023-1026 must be skipped
            long leaf = order.getNextLeafPathToSend();
            assertTrue(
                    leaf == PATH_NOT_AVAILABLE_YET || leaf > 1026L,
                    "Leaves 1023-1026 under clean node 255 must be skipped, got " + leaf);
        }

        @Test
        @DisplayName("5.4 — Clean grandparent skips a larger leaf range")
        void cleanGrandparentSkipsLargerRange() {
            final var order = new TopToBottomTraversalOrder();
            order.start(CHUNK_FIRST, CHUNK_LAST, CHUNK_FIRST, CHUNK_LAST);

            drainInternals(order);
            // Path 127 (rank 7) covers leaves 1023-1030 (2^3 = 8 leaves)
            order.nodeReceived(127L, true);

            long leaf = order.getNextLeafPathToSend();
            assertTrue(
                    leaf == PATH_NOT_AVAILABLE_YET || leaf > 1030L,
                    "All 8 leaves under clean node 127 must be skipped, got " + leaf);
        }

        @Test
        @DisplayName("5.5 — PATH_NOT_AVAILABLE_YET resolves after dirty nodeReceived")
        void notAvailableResolvesAfterDirtyResponse() {
            final var order = new TopToBottomTraversalOrder();
            order.start(CHUNK_FIRST, CHUNK_LAST, CHUNK_FIRST, CHUNK_LAST);

            drainInternals(order);

            // First call: no parent info → stall
            assertEquals(PATH_NOT_AVAILABLE_YET, order.getNextLeafPathToSend());

            // Receive dirty response for near-leaf-rank ancestor
            order.nodeReceived(127L, false); // rank 7, someDirtyPaths

            // Now the leaf must be sendable
            long leaf = order.getNextLeafPathToSend();
            assertEquals(CHUNK_FIRST, leaf, "After dirty nodeReceived, leaf must be sendable");
        }

        @Test
        @DisplayName("5.6 — INVALID_PATH returned after lastLeafPath, and is idempotent")
        void invalidPathAfterLastLeaf() {
            final var order = new TopToBottomTraversalOrder();
            order.start(CHUNK_FIRST, CHUNK_LAST, CHUNK_FIRST, CHUNK_LAST);

            driveAllDirty(order);

            // Additional calls must consistently return INVALID_PATH
            assertEquals(INVALID_PATH, order.getNextLeafPathToSend());
            assertEquals(INVALID_PATH, order.getNextLeafPathToSend());
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    // Group 6 — skipCleanPaths() correctness (via leaf behaviour)
    // ═════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Group 6 — skipCleanPaths() correctness")
    class SkipCleanPathsTests {

        @Test
        @DisplayName("6.1 — Clean internal skips exactly its own subtree leaves")
        void cleanInternalSkipsExactSubtree() {
            final var order = new TopToBottomTraversalOrder();
            order.start(CHUNK_FIRST, CHUNK_LAST, CHUNK_FIRST, CHUNK_LAST);

            drainInternals(order);
            // Path 255 (rank 8) covers leaves 1023-1026 exactly
            order.nodeReceived(255L, true);
            // Path 256 (rank 8) covers leaves 1027-1030 — leave unknown

            // Make 1027's ancestor dirty so it is eventually sent (not just skipped or stalled)
            order.nodeReceived(256L, false); // rank 8 → someDirtyPaths

            final long leaf = order.getNextLeafPathToSend();
            assertEquals(1027L, leaf, "First leaf after clean subtree of 255 must be 1027");
        }

        @Test
        @DisplayName("6.2 — Two non-overlapping clean internals each skip only their own subtrees")
        void twoNonOverlappingCleanInternals() {
            final var order = new TopToBottomTraversalOrder();
            order.start(CHUNK_FIRST, CHUNK_LAST, CHUNK_FIRST, CHUNK_LAST);

            drainInternals(order);
            // Path 255 covers leaves 1023-1026; path 257 covers leaves 1031-1034
            order.nodeReceived(255L, true);
            order.nodeReceived(257L, true);
            order.nodeReceived(256L, false); // leaves 1027-1030 have dirty ancestor
            order.nodeReceived(258L, false); // leaves 1035-1038 have dirty ancestor

            List<Long> leaves = new ArrayList<>();
            long leaf;
            // Collect until we've seen something in range 1031+
            int attempts = 0;
            while (attempts++ < 1000) {
                leaf = order.getNextLeafPathToSend();
                if (leaf == INVALID_PATH) {
                    break;
                }
                if (leaf == PATH_NOT_AVAILABLE_YET) {
                    continue;
                }
                leaves.add(leaf);
                if (leaf >= 1035L) {
                    break;
                }
            }

            assertTrue(leaves.contains(1027L), "Leaf 1027 (between clean subtrees) must be sent");
            assertTrue(
                    leaves.stream().noneMatch(p -> p >= 1023L && p <= 1026L),
                    "Leaves 1023-1026 under clean node 255 must never be sent");
            assertTrue(
                    leaves.stream().noneMatch(p -> p >= 1031L && p <= 1034L),
                    "Leaves 1031-1034 under clean node 257 must never be sent");
        }

        @Test
        @DisplayName("6.3 — Entire chunk skipped when initial internals all clean")
        void entireChunkSkippedWhenInitialInternalsClean() {
            final var order = new TopToBottomTraversalOrder();
            order.start(CHUNK_FIRST, CHUNK_LAST, CHUNK_FIRST, CHUNK_LAST);

            // Feed all 16 chunk-1 initial internals (31-46, rank 5) as clean.
            // Each covers 2^5=32 leaves; together they cover all 512 chunk-1 leaves.
            List<Long> initInternals = drainInternals(order);
            for (long p : initInternals) {
                order.nodeReceived(p, true);
            }

            // The leaf call must trigger a chunk transition (PATH_NOT_AVAILABLE_YET),
            // not return any leaf from chunk 1
            final long leaf = order.getNextLeafPathToSend();
            assertTrue(
                    leaf == PATH_NOT_AVAILABLE_YET,
                    "All chunk-1 leaves must be skipped when all initial internals are clean");

            // After transition, chunk-2 internals (47-62) must be available
            final List<Long> chunk2Internals = drainInternals(order);
            assertEquals(16, chunk2Internals.size(), "Chunk-2 must seed 16 fresh internals");
            assertEquals(CHUNK2_INIT_LO, chunk2Internals.get(0));
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    // Group 7 — Chunk transitions
    // ═════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Group 7 — Chunk transitions")
    class ChunkTransitionTests {

        @Test
        @DisplayName("7.1 — cleanPaths cleared after chunk transition")
        void cleanPathsClearedOnChunkTransition() {
            final var order = new TopToBottomTraversalOrder();
            order.start(CHUNK_FIRST, CHUNK_LAST, CHUNK_FIRST, CHUNK_LAST);

            // Cause chunk-1 to complete by feeding all its initial internals clean
            final List<Long> chunk1Internals = drainInternals(order);
            for (long p : chunk1Internals) {
                order.nodeReceived(p, true);
            }

            // Chunk transition: PATH_NOT_AVAILABLE_YET returned
            assertEquals(PATH_NOT_AVAILABLE_YET, order.getNextLeafPathToSend());

            // Drain chunk-2 internals but do NOT feed nodeReceived for any
            drainInternals(order);

            // Leaf 1535 is in chunk 2. Its ancestors (47-62 clean from chunk 1) were cleared.
            // Without a nodeReceived for any chunk-2 ancestor, must return PATH_NOT_AVAILABLE_YET
            final long leaf = order.getNextLeafPathToSend();
            assertEquals(PATH_NOT_AVAILABLE_YET, leaf, "Chunk-1 cleanPaths must not carry over into chunk 2");
        }

        @Test
        @DisplayName("7.2 — PATH_NOT_AVAILABLE_YET returned exactly once at chunk boundary")
        void pathNotAvailableReturnedOnceAtChunkBoundary() {
            final var order = new TopToBottomTraversalOrder();
            order.start(CHUNK_FIRST, CHUNK_LAST, CHUNK_FIRST, CHUNK_LAST);

            final List<Long> chunk1Internals = drainInternals(order);
            for (long p : chunk1Internals) {
                order.nodeReceived(p, true);
            }

            long first = order.getNextLeafPathToSend();
            assertEquals(
                    PATH_NOT_AVAILABLE_YET, first, "First call after clean chunk must return PATH_NOT_AVAILABLE_YET");

            // Internals for chunk 2 are now seeded; after draining+feeding them, leaves flow
            final List<Long> chunk2Internals = drainInternals(order);
            assertNotEquals(0, chunk2Internals.size(), "Chunk-2 internals must be seeded after transition");
        }

        @Test
        @DisplayName("7.3 — Chunk-2 initial internals seeded immediately after chunk-1 transition")
        void chunk2InternalsSeededAfterTransition() {
            final var order = new TopToBottomTraversalOrder();
            order.start(CHUNK_FIRST, CHUNK_LAST, CHUNK_FIRST, CHUNK_LAST);

            final List<Long> chunk1 = drainInternals(order);
            for (long p : chunk1) {
                order.nodeReceived(p, true);
            }
            order.getNextLeafPathToSend(); // consume the PATH_NOT_AVAILABLE_YET

            final List<Long> chunk2 = drainInternals(order);
            assertEquals(16, chunk2.size(), "Chunk-2 must have 16 initial internals");
            assertEquals(CHUNK2_INIT_LO, chunk2.get(0), "Chunk-2 first internal must be path 47");
            assertEquals(
                    CHUNK2_INIT_HI, chunk2.get(chunk2.size() - 1), "Chunk-2 last initial internal must be path 62");
            for (long p : chunk2) {
                assertEquals(5, Path.getRank(p), "Chunk-2 internals must be at rank 5");
            }
        }

        @Test
        @DisplayName("7.4 — Correct chunkLastLeafPath for chunk-2")
        void correctChunkLastLeafPathForChunk2() {
            final var order = new TopToBottomTraversalOrder();
            order.start(CHUNK_FIRST, CHUNK_LAST, CHUNK_FIRST, CHUNK_LAST);

            // Transition to chunk 2
            final List<Long> chunk1 = drainInternals(order);
            for (long p : chunk1) {
                order.nodeReceived(p, true);
            }
            order.getNextLeafPathToSend(); // PATH_NOT_AVAILABLE_YET

            // Feed chunk-2 internals clean → all chunk-2 leaves should be skipped
            final List<Long> chunk2 = drainInternals(order);
            for (long p : chunk2) {
                order.nodeReceived(p, true);
            }

            // The next leaf call should trigger INVALID_PATH (all done), not another chunk
            long leaf = order.getNextLeafPathToSend();
            // Either INVALID_PATH (all done) is returned directly, or via one more
            // PATH_NOT_AVAILABLE_YET that immediately leads to INVALID_PATH
            if (leaf == PATH_NOT_AVAILABLE_YET) {
                leaf = order.getNextLeafPathToSend();
            }
            assertEquals(INVALID_PATH, leaf, "After both chunks are fully clean, algorithm must terminate");
        }

        @Test
        @DisplayName("7.5 — Last chunk transitions to INVALID_PATH, not another chunk")
        void lastChunkTransitionsToInvalidPath() {
            final var order = new TopToBottomTraversalOrder();
            order.start(CHUNK_FIRST, CHUNK_LAST, CHUNK_FIRST, CHUNK_LAST);

            final List<Long> leaves = driveAllClean(order);
            assertTrue(leaves.isEmpty(), "All-clean drive must produce no leaf requests");
            // Algorithm must now be terminated
            assertEquals(INVALID_PATH, order.getNextLeafPathToSend());
            assertEquals(INVALID_PATH, order.getNextInternalPathToSend());
        }

        @Test
        @DisplayName("7.6 — Mixed-rank tree (rank 10-11) completes without error")
        void mixedRankTreeCompletesCorrectly() {
            // firstLeafPath rank=10, lastLeafPath rank=11 — exercises the rank-change branch
            long mixedFirst = 1023L; // rank 10
            long mixedLast = 4094L; // rank 11 (= 2^12 - 2)

            final var order = new TopToBottomTraversalOrder();
            order.start(mixedFirst, mixedLast, mixedFirst, mixedLast);

            // Drive to completion under all-dirty; must not throw and must terminate
            final List<Long> leaves = driveAllDirty(order);

            assertEquals(mixedLast - mixedFirst + 1, leaves.size(), "All leaves in mixed-rank tree must be sent");
            assertEquals(mixedFirst, leaves.get(0));
            assertEquals(mixedLast, leaves.get(leaves.size() - 1));
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    // Group 8 — Fully clean tree
    // ═════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Group 8 — Fully clean tree")
    class FullyCleanTests {

        @Test
        @DisplayName("8.1 — Zero leaves sent when all responses are clean")
        void noLeavesSentWhenAllClean() {
            final var order = new TopToBottomTraversalOrder();
            order.start(CHUNK_FIRST, CHUNK_LAST, CHUNK_FIRST, CHUNK_LAST);

            final List<Long> leaves = driveAllClean(order);
            assertTrue(leaves.isEmpty(), "No leaves must be sent when entire tree is clean");
        }

        @Test
        @DisplayName("8.2 — No new internals enqueued after clean responses")
        void noInternalsEnqueuedAfterCleanResponses() {
            final var order = new TopToBottomTraversalOrder();
            order.start(CHUNK_FIRST, CHUNK_LAST, CHUNK_FIRST, CHUNK_LAST);

            final List<Long> chunk1 = drainInternals(order);
            for (long p : chunk1) {
                order.nodeReceived(p, true);
            }

            // After clean responses, only chunk-2 internals should appear (no extras)
            order.getNextLeafPathToSend(); // chunk transition
            final List<Long> chunk2 = drainInternals(order);

            assertEquals(16, chunk2.size(), "After chunk-1 clean, only chunk-2's 16 seeded internals must appear");
            for (long p : chunk2) order.nodeReceived(p, true);
            assertEquals(
                    INVALID_PATH,
                    order.getNextInternalPathToSend(),
                    "After all clean responses no extra internals must be enqueued");
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    // Group 9 — Fully dirty tree
    // ═════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Group 9 — Fully dirty tree")
    class FullyDirtyTests {

        @Test
        @DisplayName("9.1 — All leaves sent when all responses are dirty")
        void allLeavesSentWhenAllDirty() {
            final var order = new TopToBottomTraversalOrder();
            order.start(CHUNK_FIRST, CHUNK_LAST, CHUNK_FIRST, CHUNK_LAST);

            final List<Long> leaves = driveAllDirty(order);

            long expectedCount = CHUNK_LAST - CHUNK_FIRST + 1; // 1024
            assertEquals(expectedCount, leaves.size(), "All 1024 leaves must be sent in fully-dirty tree");
        }

        @Test
        @DisplayName("9.2 — No leaf is skipped in fully dirty tree")
        void noLeafSkippedWhenAllDirty() {
            final var order = new TopToBottomTraversalOrder();
            order.start(CHUNK_FIRST, CHUNK_LAST, CHUNK_FIRST, CHUNK_LAST);

            final List<Long> leaves = driveAllDirty(order);

            for (long path = CHUNK_FIRST; path <= CHUNK_LAST; path++) {
                assertEquals(
                        path,
                        leaves.get((int) (path - CHUNK_FIRST)),
                        "Leaf " + path + " must be sent in order in fully-dirty tree");
            }
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    // Group 10 — Edge cases
    // ═════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Group 10 — Edge cases")
    class EdgeCaseTests {

        @Test
        @DisplayName("10.1 — Single-leaf tree returns exactly one leaf then INVALID_PATH")
        void singleLeafTree() {
            // Single leaf at path 1 (rank 1): firstLeafRank=1 < 10 → simple mode
            final var order = new TopToBottomTraversalOrder();
            order.start(1L, 1L, 1L, 1L);

            assertEquals(INVALID_PATH, order.getNextInternalPathToSend());
            assertEquals(1L, order.getNextLeafPathToSend());
            assertEquals(INVALID_PATH, order.getNextLeafPathToSend());
        }

        @Test
        @DisplayName("10.2 — Identical learner and teacher ranges with all clean: zero leaves sent")
        void identicalRangesAllClean() {
            final var order = new TopToBottomTraversalOrder();
            order.start(CHUNK_FIRST, CHUNK_LAST, CHUNK_FIRST, CHUNK_LAST);

            List<Long> leaves = driveAllClean(order);
            assertTrue(leaves.isEmpty(), "No leaves sent when teacher and learner trees are identical and all clean");
        }

        @Test
        @DisplayName("10.3 — Learner is empty (old range entirely outside teacher range): all leaves sent immediately")
        void learnerEmptyAllLeavesSentImmediately() {
            // Old range [5000, 10000] is entirely outside teacher range [1023, 2046]
            // → all teacher leaves are after old range, sent without internal queries
            final var order = new TopToBottomTraversalOrder();
            order.start(5000L, 10000L, CHUNK_FIRST, CHUNK_LAST);

            // All leaves must be returned without any nodeReceived calls
            final List<Long> leaves = new ArrayList<>();
            long leaf;
            while ((leaf = order.getNextLeafPathToSend()) != INVALID_PATH) {
                assertNotEquals(PATH_NOT_AVAILABLE_YET, leaf, "No stalling expected when learner is empty");
                leaves.add(leaf);
            }

            assertEquals(
                    CHUNK_LAST - CHUNK_FIRST + 1, leaves.size(), "All 1024 leaves must be sent when learner is empty");
        }

        @Test
        @DisplayName("10.4 — Teacher has fewer leaves than learner: terminates at lastLeafPath")
        void teacherFewerLeavesThanLearner() {
            // Teacher: [767, 1534] (768 leaves; 1534 = 2*767).
            // Learner had: [1023, 2046] (1024 leaves).
            // Teacher has fewer leaves; algorithm must stop at teacher's lastLeafPath=1534.
            final var order = new TopToBottomTraversalOrder();
            order.start(CHUNK_FIRST, CHUNK_LAST, OTHER_FIRST_LEAF, OTHER_LAST_LEAF);

            List<Long> leaves = driveAllDirty(order);

            assertTrue(
                    leaves.stream().allMatch(p -> p <= OTHER_LAST_LEAF),
                    "No leaf beyond teacher's lastLeafPath must be requested");
            assertEquals(OTHER_LAST_LEAF - OTHER_FIRST_LEAF + 1, leaves.size(), "All teacher leaves must be sent");
        }

        @Test
        @DisplayName("10.5 — firstLeafPath == oldFirstLeafPath: chunk root computed correctly")
        void sameFirstLeafPath() {
            // No asymmetry at the start; chunk root must equal path 1
            final var order = new TopToBottomTraversalOrder();
            order.start(CHUNK_FIRST, CHUNK_LAST, CHUNK_FIRST, CHUNK_LAST);

            final List<Long> initInternals = drainInternals(order);
            assertEquals(
                    16, initInternals.size(), "Must seed 16 initial internals when firstLeafPath == oldFirstLeafPath");
            assertEquals(
                    CHUNK1_INIT_LO,
                    initInternals.get(0),
                    "Initial internals must start at path 31 (under chunk-1 root=1)");
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    // Group 11 — Concurrency
    // ═════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Group 11 — Concurrency")
    class ConcurrencyTests {

        @Test
        @DisplayName("11.1 — No duplicate paths from concurrent getNextInternalPathToSend()")
        void noDuplicateInternalsUnderConcurrentDrain() throws Exception {
            final var order = new TopToBottomTraversalOrder();
            order.start(CHUNK_FIRST, CHUNK_LAST, CHUNK_FIRST, CHUNK_LAST);

            // Drain initial internals (31-46) and feed all dirty to populate queue
            // with 128 rank-8 paths (255-382)
            List<Long> initInternals = drainInternals(order);
            for (long p : initInternals) {
                order.nodeReceived(p, false);
            }

            // Now internals queue has ~128 paths; drain concurrently
            final Set<Long> collected = Collections.newSetFromMap(new ConcurrentHashMap<>());
            final List<Long> duplicates = Collections.synchronizedList(new ArrayList<>());

            int threadCount = 4;
            CountDownLatch start = new CountDownLatch(1);
            CountDownLatch done = new CountDownLatch(threadCount);
            ExecutorService pool = Executors.newFixedThreadPool(threadCount);

            for (int t = 0; t < threadCount; t++) {
                pool.submit(() -> {
                    try {
                        start.await();
                        long p;
                        while ((p = order.getNextInternalPathToSend()) != INVALID_PATH) {
                            if (!collected.add(p)) {
                                duplicates.add(p);
                            }
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        done.countDown();
                    }
                });
            }

            start.countDown();
            assertTrue(done.await(10, TimeUnit.SECONDS), "Threads must finish within 10 s");
            pool.shutdown();

            assertTrue(
                    duplicates.isEmpty(),
                    "Duplicate paths returned by concurrent getNextInternalPathToSend(): " + duplicates);
            // All 128 rank-8 paths must be accounted for
            assertEquals(128, collected.size(), "All 128 rank-8 paths must be returned across threads");
        }

        @Test
        @DisplayName("11.2 — Concurrent nodeReceived and getNextLeafPathToSend do not corrupt state")
        void concurrentNodeReceivedAndGetNextLeaf() throws Exception {
            final var order = new TopToBottomTraversalOrder();
            order.start(CHUNK_FIRST, CHUNK_LAST, CHUNK_FIRST, CHUNK_LAST);

            drainInternals(order); // clear initial internals

            // Producer: rapidly sends dirty nodeReceived for near-leaf-rank paths
            // (rank-8 paths 255-382 cover all chunk-1 leaves)
            CountDownLatch producerDone = new CountDownLatch(1);
            Set<Long> sentLeaves = Collections.newSetFromMap(new ConcurrentHashMap<>());
            List<Throwable> errors = Collections.synchronizedList(new ArrayList<>());

            final Thread producer = new Thread(() -> {
                try {
                    for (long p = 255L; p <= 382L; p++) {
                        order.nodeReceived(p, false);
                        Thread.yield();
                    }
                } catch (Throwable t) {
                    errors.add(t);
                } finally {
                    producerDone.countDown();
                }
            });

            final Thread consumer = new Thread(() -> {
                try {
                    int stalls = 0;
                    while (true) {
                        long leaf = order.getNextLeafPathToSend();
                        if (leaf == INVALID_PATH) break;
                        if (leaf == PATH_NOT_AVAILABLE_YET) {
                            if (++stalls > 500_000) {
                                break; // give up after enough stalls
                            }
                            Thread.yield();
                            continue;
                        }
                        stalls = 0;
                        sentLeaves.add(leaf);
                    }
                } catch (Throwable t) {
                    errors.add(t);
                }
            });

            producer.start();
            consumer.start();
            producer.join(10_000);
            consumer.join(10_000);

            assertTrue(errors.isEmpty(), "No exceptions must occur during concurrent access: " + errors);
            // Every sent leaf must be within valid range
            for (long leaf : sentLeaves) {
                assertTrue(leaf >= CHUNK_FIRST && leaf <= CHUNK_LAST, "Sent leaf " + leaf + " is outside valid range");
            }
        }

        @Test
        @DisplayName("11.3 — Concurrent nodeReceived calls produce correct cumulative queue entries")
        void concurrentNodeReceivedProducesCorrectEntries() throws Exception {
            final var order = new TopToBottomTraversalOrder();
            order.start(CHUNK_FIRST, CHUNK_LAST, CHUNK_FIRST, CHUNK_LAST);

            // Drain initial internals first
            List<Long> initInternals = drainInternals(order);

            // Feed the initial internals concurrently as dirty (multiple threads)
            int threadCount = 4;
            final CountDownLatch start = new CountDownLatch(1);
            final CountDownLatch done = new CountDownLatch(threadCount);
            final ExecutorService pool = Executors.newFixedThreadPool(threadCount);

            // Partition the 16 initial paths across threads
            for (int t = 0; t < threadCount; t++) {
                final int thread = t;
                pool.submit(() -> {
                    try {
                        start.await();
                        for (int i = thread; i < initInternals.size(); i += threadCount) {
                            order.nodeReceived(initInternals.get(i), false);
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        done.countDown();
                    }
                });
            }

            start.countDown();
            assertTrue(done.await(10, TimeUnit.SECONDS));
            pool.shutdown();

            // After all 16 dirty responses, 128 rank-8 paths must be in the queue
            final List<Long> result = drainInternals(order);
            assertEquals(
                    128, result.size(), "Concurrent dirty responses must enqueue exactly 128 rank-8 grand-children");
            final Set<Long> resultSet = new HashSet<>(result);
            // Verify no duplicates
            assertEquals(result.size(), resultSet.size(), "No duplicate paths must appear in the queue");
            // All must be at rank 8
            for (long p : result) {
                assertEquals(8, Path.getRank(p), "All queued grand-children must be at rank 8");
            }
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    // Group 12 — Partial leaf ranges
    //
    // All trees here satisfy lastLeafPath = 2 * firstLeafPath.
    // firstLeafPath and lastLeafPath do NOT span a complete rank left-to-right;
    // they start or end somewhere in the middle of a rank.
    //
    // Three reference configurations (all in chunk mode, firstLeafRank = 10):
    //
    //   P1: firstLeaf=1100, lastLeaf=2200 (=2*1100), 1101 leaves.
    //       firstLeaf falls under chunk-1 (root=path 1, covers 1023–1534).
    //       Initial internals: 31–46.  rank-8 ancestor of 1100: path 274
    //       (reached via dirty path 33 → grand-children 271–278).
    //
    //   P2: firstLeaf=1500, lastLeaf=3000 (=2*1500), 1501 leaves.
    //       firstLeaf near the right edge of chunk-1 (only leaves 1500–1534
    //       are visible in that chunk, 35 leaves out of 512).
    //       Initial internals: 31–46.
    //
    //   P3: firstLeaf=1700, lastLeaf=3400 (=2*1700), 1701 leaves.
    //       firstLeaf falls under chunk-2 (root=path 2, covers 1535–2046),
    //       so the algorithm skips chunk-1 entirely.
    //       Initial internals: 47–62.  rank-8 ancestor of 1700: path 424
    //       (reached via dirty path 52 → grand-children 423–430).
    // ═════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Group 12 — Partial leaf ranges (lastLeafPath = 2 * firstLeafPath)")
    class PartialLeafRangeTests {

        private static final long P1_FIRST = 1100L;
        private static final long P1_LAST = 2L * P1_FIRST; // 2200

        private static final long P2_FIRST = 1500L;
        private static final long P2_LAST = 2L * P2_FIRST; // 3000

        private static final long P3_FIRST = 1700L;
        private static final long P3_LAST = 2L * P3_FIRST; // 3400

        @Test
        @DisplayName("12.1 — lastLeafPath == 2 * firstLeafPath is satisfied by all configurations")
        void lastLeafIsTwiceFirstLeaf() {
            assertEquals(2L * P1_FIRST, P1_LAST);
            assertEquals(2L * P2_FIRST, P2_LAST);
            assertEquals(2L * P3_FIRST, P3_LAST);
        }

        @Test
        @DisplayName("12.2 — All leaves in [firstLeafPath, lastLeafPath] sent for P1 (fully-dirty)")
        void allLeavesInP1RangeSentWhenDirty() {
            final var order = new TopToBottomTraversalOrder();
            order.start(P1_FIRST, P1_LAST, P1_FIRST, P1_LAST);

            final List<Long> leaves = driveAllDirty(order);

            assertEquals(P1_LAST - P1_FIRST + 1, leaves.size(), "All 1101 leaves in [1100, 2200] must be sent");
            assertEquals(P1_FIRST, leaves.get(0));
            assertEquals(P1_LAST, leaves.get(leaves.size() - 1));
        }

        @Test
        @DisplayName("12.3 — No leaf below firstLeafPath is ever returned")
        void noLeafBelowFirstLeafPath() {
            final var order = new TopToBottomTraversalOrder();
            order.start(P1_FIRST, P1_LAST, P1_FIRST, P1_LAST);

            final List<Long> leaves = driveAllDirty(order);

            for (long leaf : leaves) {
                assertTrue(leaf >= P1_FIRST, "Leaf " + leaf + " is below firstLeafPath=" + P1_FIRST);
            }
        }

        @Test
        @DisplayName("12.4 — No leaf above lastLeafPath is ever returned")
        void noLeafAboveLastLeafPath() {
            final var order = new TopToBottomTraversalOrder();
            order.start(P1_FIRST, P1_LAST, P1_FIRST, P1_LAST);

            final List<Long> leaves = driveAllDirty(order);

            for (long leaf : leaves) {
                assertTrue(leaf <= P1_LAST, "Leaf " + leaf + " is above lastLeafPath=" + P1_LAST);
            }
        }

        @Test
        @DisplayName("12.5 — Initial internals seeded under chunk-1 root when firstLeafPath is inside chunk-1 (P1)")
        void initialInternalsForFirstLeafInChunk1() {
            // P1: firstLeaf=1100 is under path 1 (chunk-1 covers 1023–1534).
            // Initial internals must be 31–46 (rank-5 descendants of path 1).
            final var order = new TopToBottomTraversalOrder();
            order.start(P1_FIRST, P1_LAST, P1_FIRST, P1_LAST);

            final List<Long> init = drainInternals(order);

            assertEquals(16, init.size(), "Must seed 16 initial internals");
            assertEquals(CHUNK1_INIT_LO, init.get(0), "First initial internal must be 31 (chunk-1, root=path 1)");
            assertEquals(CHUNK1_INIT_HI, init.get(init.size() - 1));
            for (long p : init) {
                assertEquals(5, Path.getRank(p), "All initial internals must be at rank 5");
            }
        }

        @Test
        @DisplayName("12.6 — Initial internals seeded under chunk-2 root when firstLeafPath is inside chunk-2 (P3)")
        void initialInternalsForFirstLeafInChunk2() {
            // P3: firstLeaf=1700 is under path 2 (chunk-2 covers 1535–2046).
            // Chunk-1 (paths 31–46) must be skipped; initial internals must be 47–62.
            final var order = new TopToBottomTraversalOrder();
            order.start(P3_FIRST, P3_LAST, P3_FIRST, P3_LAST);

            final List<Long> init = drainInternals(order);

            assertEquals(16, init.size(), "Must seed 16 initial internals");
            assertEquals(
                    CHUNK2_INIT_LO,
                    init.get(0),
                    "First initial internal must be 47 (chunk-2, root=path 2), not 31 (chunk-1)");
            assertEquals(CHUNK2_INIT_HI, init.get(init.size() - 1));
        }

        @Test
        @DisplayName("12.7 — Only subset of first chunk's leaves processed when firstLeafPath is mid-chunk (P2)")
        void partialFirstChunkLeavesProcessed() {
            // P2: firstLeaf=1500. Chunk-1 covers 1023–1534 but only leaves 1500–1534 (35 leaves)
            // are in scope. After those 35 leaves, algorithm must advance to chunk-2.
            final var order = new TopToBottomTraversalOrder();
            order.start(P2_FIRST, P2_LAST, P2_FIRST, P2_LAST);

            final List<Long> leaves = driveAllDirty(order);
            final Set<Long> leafSet = new HashSet<>(leaves);

            // Chunk-1 visible leaves (1500–1534) must be present
            for (long p = P2_FIRST; p <= 1534L; p++) {
                assertTrue(leafSet.contains(p), "Leaf " + p + " (partial first chunk) must be sent");
            }
            // Leaves below firstLeafPath must never appear
            for (long p = CHUNK_FIRST; p < P2_FIRST; p++) {
                assertTrue(!leafSet.contains(p), "Leaf " + p + " is below firstLeafPath and must not be sent");
            }
            // Chunk-2 must also be processed
            assertTrue(leafSet.contains(1535L), "First leaf of chunk-2 must be sent");
        }

        @Test
        @DisplayName("12.8 — All-clean partial tree sends zero leaves")
        void allCleanPartialTreeSendsZeroLeaves() {
            final var order = new TopToBottomTraversalOrder();
            order.start(P1_FIRST, P1_LAST, P1_FIRST, P1_LAST);

            final List<Long> leaves = driveAllClean(order);

            assertTrue(leaves.isEmpty(), "No leaves must be sent when all responses are clean in a partial-range tree");
        }

        @Test
        @DisplayName("12.9 — All leaves in [P3_FIRST, P3_LAST] sent when dirty (chunk-2-first)")
        void allLeavesInP3RangeSentWhenDirty() {
            final var order = new TopToBottomTraversalOrder();
            order.start(P3_FIRST, P3_LAST, P3_FIRST, P3_LAST);

            final List<Long> leaves = driveAllDirty(order);

            assertEquals(P3_LAST - P3_FIRST + 1, leaves.size(), "All 1701 leaves in [1700, 3400] must be sent");
            for (long leaf : leaves) {
                assertTrue(leaf >= P3_FIRST && leaf <= P3_LAST, "Leaf " + leaf + " is outside [1700, 3400]");
            }
        }

        @Test
        @DisplayName(
                "12.10 — Asymmetric old/teacher ranges: boundary leaves sent immediately, in-range leaves use dirty logic")
        void asymmetricOldAndTeacherRanges() {
            // Teacher: [1100, 2200]. Learner had oldFirst=1300, oldLast=2600 (=2*1300).
            // Teacher leaves 1100–1299 are before old range → sent immediately.
            // Teacher leaves 1300–2200 are within old range → normal clean/dirty logic.
            final var order = new TopToBottomTraversalOrder();
            order.start(1300L, 2600L, P1_FIRST, P1_LAST);

            final List<Long> leaves = driveAllDirty(order);
            final Set<Long> leafSet = new HashSet<>(leaves);

            // Before-old-range leaves (1100–1299) must all be present
            for (long p = P1_FIRST; p < 1300L; p++) {
                assertTrue(leafSet.contains(p), "Leaf " + p + " (before old range) must be sent immediately");
            }
            // All leaves must be within teacher's range
            assertEquals(P1_LAST - P1_FIRST + 1, leaves.size(), "All 1101 teacher leaves must be sent");
        }

        @Test
        @DisplayName("12.11 — First leaf of partial range sent after its rank-8 dirty ancestor is received (P1)")
        void firstLeafOfP1SentAfterDirtyAncestor() {
            // P1 firstLeaf=1100. Ancestry chain: rank-9=549, rank-8=274.
            // Path 274 is a grand-child of dirty initial internal path 33 (→ children 271–278).
            final var order = new TopToBottomTraversalOrder();
            order.start(P1_FIRST, P1_LAST, P1_FIRST, P1_LAST);

            drainInternals(order); // remove initial internals 31–46 from queue
            order.nodeReceived(33L, false); // rank 5 → enqueues rank-8 grand-children 271–278
            drainInternals(order); // drain 271–278
            order.nodeReceived(274L, false); // rank 8 >= 7 → someDirtyPaths

            final long leaf = order.getNextLeafPathToSend();
            assertEquals(P1_FIRST, leaf, "Leaf 1100 must be the first leaf sent after dirty ancestor 274 is received");
        }

        @Test
        @DisplayName("12.12 — First leaf of partial range sent after its rank-8 dirty ancestor is received (P3)")
        void firstLeafOfP3SentAfterDirtyAncestor() {
            // P3 firstLeaf=1700. Ancestry chain: rank-9=849, rank-8=424.
            // Path 424 is a grand-child of dirty initial internal path 52 (→ children 423–430).
            final var order = new TopToBottomTraversalOrder();
            order.start(P3_FIRST, P3_LAST, P3_FIRST, P3_LAST);

            drainInternals(order); // remove initial internals 47–62 from queue
            order.nodeReceived(52L, false); // rank 5 → enqueues rank-8 grand-children 423–430
            drainInternals(order); // drain 423–430
            order.nodeReceived(424L, false); // rank 8 >= 7 → someDirtyPaths

            final long leaf = order.getNextLeafPathToSend();
            assertEquals(P3_FIRST, leaf, "Leaf 1700 must be the first leaf sent after dirty ancestor 424 is received");
        }

        @Test
        @DisplayName("12.13 — Last leaf of partial range is the last leaf sent, followed by INVALID_PATH")
        void lastLeafIsLastSentThenInvalidPath() {
            final var order = new TopToBottomTraversalOrder();
            order.start(P1_FIRST, P1_LAST, P1_FIRST, P1_LAST);

            final List<Long> leaves = driveAllDirty(order);

            assertNotEquals(0, leaves.size());
            assertEquals(P1_LAST, leaves.getLast(), "Last sent leaf must equal lastLeafPath=" + P1_LAST);
            assertEquals(
                    INVALID_PATH,
                    order.getNextLeafPathToSend(),
                    "Algorithm must terminate with INVALID_PATH immediately after lastLeafPath");
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    // Group 13 — Large trees (chunkRootRank = 5, rank-28 leaves)
    //
    //   firstLeafPath = 268435455 (= 2^28 - 1, rank 28)
    //   lastLeafPath  = 536870910 (= 2 * 268435455, rank 28)
    //   chunkRootRank = max(1, 28 - 23) = 5
    //   chunkRootPath (first chunk) = 31 (first path at rank 5)
    //   chunkHeight = 23, skipRanks = 11
    //   Initial internals: getLeftGrandChildPath(31, 11) = 65535
    //                   to getRightGrandChildPath(31, 11) = 67582 (2048 paths at rank 16)
    //   someDirtyPaths threshold: rank >= 28 - 3 = 25
    //   Dirty rank-16 path 65535 → RANK_STEP=3 grand-children at rank 19: 524287–524294
    //   Rank-25 ancestor of firstLeafPath (268435455): 33554431
    //   Clean path 65535 covers leaves 268435455–268439550 (4096 leaves); next = 268439551
    //   Rank-25 ancestor of 268439551: 33554943
    // ═════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Group 13 — Large trees (chunkRootRank = 5, rank-28 leaves)")
    class LargeTreeTests {

        private static final long L13_FIRST = 268_435_455L; // 2^28 - 1, rank 28
        private static final long L13_LAST = 2L * L13_FIRST; // 536870910, rank 28

        // Initial internals: rank-16 paths seeded under chunk-1 root (path 31 at rank 5)
        // chunkRootRank(5) + skipRanks(chunkHeight/2 = 23/2 = 11) = rank 16
        private static final long L13_INIT_LO = 65_535L; // getLeftGrandChildPath(31, 11)
        private static final long L13_INIT_HI = 67_582L; // L13_INIT_LO + 2048 - 1
        private static final int L13_INIT_RANK = 16;

        // Rank-19 grand-children of dirty path 65535 (RANK_STEP = 3): 65535*8+7 = 524287
        private static final long L13_DIRTY_65535_LO = 524_287L;
        private static final long L13_DIRTY_65535_HI = 524_294L;

        // Rank-25 ancestor of firstLeafPath: 3 levels above rank 28 = rank 25
        // 268435455 → 134217727 → 67108863 → 33554431
        private static final long L13_RANK25_ANCESTOR_OF_FIRST = 33_554_431L;

        // First leaf outside the clean subtree of 65535 (covers 268435455–268439550): 268439551
        // Rank-25 ancestor of 268439551: 268439551 → 134219775 → 67109887 → 33554943
        private static final long L13_AFTER_CLEAN_65535 = 268_439_551L;
        private static final long L13_RANK25_ANCESTOR_AFTER = 33_554_943L;

        @Test
        @DisplayName("13.1 — Rank-28 tree is not in simple mode")
        void notInSimpleMode() {
            final var order = new TopToBottomTraversalOrder();
            order.start(L13_FIRST, L13_LAST, L13_FIRST, L13_LAST);

            assertNotEquals(
                    INVALID_PATH,
                    order.getNextInternalPathToSend(),
                    "Rank-28 tree (firstLeafRank=28 >= 10) must not be in simple mode");
        }

        @Test
        @DisplayName("13.2 — Initial internals are 2048 paths at rank 16 in [65535, 67582]")
        void initialInternalsAtRank16() {
            final var order = new TopToBottomTraversalOrder();
            order.start(L13_FIRST, L13_LAST, L13_FIRST, L13_LAST);

            final List<Long> init = drainInternals(order);

            assertEquals(2048, init.size(), "Must seed 2048 initial internals (2^skipRanks = 2^11)");
            assertEquals(L13_INIT_LO, init.get(0), "First initial internal must be 65535");
            assertEquals(L13_INIT_HI, init.get(init.size() - 1), "Last initial internal must be 67582");
            for (long p : init) {
                assertEquals(L13_INIT_RANK, Path.getRank(p), "All initial internals must be at rank " + L13_INIT_RANK);
            }
        }

        @Test
        @DisplayName("13.3 — chunkRootRank is 5: 11-level ancestor of initial internal 65535 is path 31 at rank 5")
        void chunkRootRankIs5() {
            // Initial internals are skipRanks=11 below the chunk root.
            // Going 11 levels up from 65535 (rank 16) must yield path 31 (rank 5).
            final long chunkRoot = Path.getGrandParentPath(L13_INIT_LO, 11);
            assertEquals(31L, chunkRoot, "Chunk root must be path 31 (first path at rank 5)");
            assertEquals(5, Path.getRank(chunkRoot), "Chunk root must be at rank 5");
        }

        @Test
        @DisplayName("13.4 — Dirty rank-16 internal enqueues 8 rank-19 grand-children (RANK_STEP=3)")
        void dirtyRank16InternalEnqueuesRank19GrandChildren() {
            final var order = new TopToBottomTraversalOrder();
            order.start(L13_FIRST, L13_LAST, L13_FIRST, L13_LAST);

            drainInternals(order); // remove initial internals from queue
            order.nodeReceived(L13_INIT_LO, false); // dirty rank-16 path 65535

            final List<Long> next = drainInternals(order);

            assertEquals(8, next.size(), "Dirty rank-16 node must enqueue 8 rank-19 grand-children (2^RANK_STEP)");
            assertEquals(L13_DIRTY_65535_LO, next.get(0), "First rank-19 grand-child of 65535 must be 524287");
            assertEquals(
                    L13_DIRTY_65535_HI, next.get(next.size() - 1), "Last rank-19 grand-child of 65535 must be 524294");
            for (long p : next) {
                assertEquals(19, Path.getRank(p), "Grand-children must be at rank 16+RANK_STEP=19");
            }
        }

        @Test
        @DisplayName("13.5 — Dirty rank-25 node (at someDirtyPaths threshold) enables firstLeafPath")
        void dirtyRank25NodeEnablesLeaf() {
            // chunkLastRank=28, threshold = 28 - RANK_STEP(3) = 25.
            // Rank-25 node goes to someDirtyPaths; leaf within RANK_STEP ancestors is then sent.
            final var order = new TopToBottomTraversalOrder();
            order.start(L13_FIRST, L13_LAST, L13_FIRST, L13_LAST);

            drainInternals(order); // drain initial internals so queue is empty
            order.nodeReceived(L13_RANK25_ANCESTOR_OF_FIRST, false); // rank 25 → someDirtyPaths

            final long leaf = order.getNextLeafPathToSend();
            assertEquals(
                    L13_FIRST, leaf, "firstLeafPath must be returned once its rank-25 ancestor is in someDirtyPaths");
        }

        @Test
        @DisplayName("13.6 — Clean rank-16 internal skips its 4096-leaf subtree; first leaf after is returned")
        void cleanRank16InternalSkipsSubtree() {
            // Clean path 65535 (rank 16) covers leaves 268435455–268439550 (2^12 = 4096 leaves).
            // Next leaf 268439551 must be returned once its rank-25 ancestor is in someDirtyPaths.
            final var order = new TopToBottomTraversalOrder();
            order.start(L13_FIRST, L13_LAST, L13_FIRST, L13_LAST);

            drainInternals(order);
            order.nodeReceived(L13_INIT_LO, true); // clean: adds 65535 to cleanPaths
            order.nodeReceived(L13_RANK25_ANCESTOR_AFTER, false); // rank-25 ancestor of 268439551

            final long leaf = order.getNextLeafPathToSend();
            assertEquals(L13_AFTER_CLEAN_65535, leaf, "First leaf after clean subtree of 65535 must be 268439551");
        }

        @Test
        @DisplayName("13.7 — Leaves before old first leaf path sent immediately without internal processing")
        void leavesBeforeOldRangeSentImmediately() {
            // Teacher range: [268435455, 536870910]. Learner old range: [400000000, 800000000].
            // Leaves 268435455–399999999 are before old first leaf → sent immediately.
            final long oldFirst = 400_000_000L;
            final var order = new TopToBottomTraversalOrder();
            order.start(oldFirst, 2L * oldFirst, L13_FIRST, L13_LAST);

            // getNextInternalPathToSend must return INVALID_PATH while current leaf < oldFirst
            assertEquals(
                    INVALID_PATH,
                    order.getNextInternalPathToSend(),
                    "No internal paths while current leaf is before old range");

            final long leaf = order.getNextLeafPathToSend();
            assertEquals(L13_FIRST, leaf, "First teacher leaf (before old range) must be returned immediately");
        }

        // ── End-to-end test ───────────────────────────────────────────────────
        //
        // Tree: firstLeafPath=100_000_000 (rank 26, mid-rank; leftmost rank-26 = 67108863)
        //       lastLeafPath =200_000_000 (rank 27; = 2 * firstLeafPath)
        //       chunkRootRank = max(1, 27 - 23) = 4
        //
        // All initial internals land at rank chunkRootRank(4) + skipRanks(11) = 15.
        // Every chunk has exactly 2^11 = 2048 initial internals.
        //
        // Chunk sequence (all with chunk root at rank 4):
        //   Rank-26 chunks (chunkHeight=22, skipRanks=11): roots 22–30, 9 chunks
        //     startLeaf advances from 100000000 to 134217727 (rank-26 leaf range upper bound)
        //     Transition 9→10 is the rank-change: chunkRootPath 30+1=31 has rank 5 ≠ 4,
        //     so parent(31)=15 is used and chunkLastRank switches from 26 to 27.
        //   Rank-27 chunks (chunkHeight=23, skipRanks=11): roots 15–22, 8 chunks
        //     Last chunk (root=22) covers [192937983, 201326590]; lastLeafPath=200000000
        //     falls inside it, so the algorithm terminates with INVALID_PATH there.
        //
        // Total: 17 chunks, 16 chunk transitions (each yields one PATH_NOT_AVAILABLE_YET).
        // All-clean scenario: 0 leaves sent.

        @Test
        @DisplayName("13.8 — End-to-end: 17 chunks, rank-26→rank-27 transition, firstLeafPath mid-rank, all-clean")
        void endToEndAllChunksAllClean() {
            final long first = 100_000_000L; // rank 26, not leftmost (leftmost = 67108863)
            final long last = 200_000_000L; // rank 27, = 2 * first
            final var order = new TopToBottomTraversalOrder();
            order.start(first, last, first, last);

            // ── First chunk (root=22, rank-26, initial internals [47103, 49150]) ──────
            final List<Long> firstChunkInternals = drainInternals(order);
            assertEquals(
                    2048,
                    firstChunkInternals.size(),
                    "First chunk must seed 2^skipRanks = 2^11 = 2048 initial internals");
            assertEquals(
                    47103L,
                    firstChunkInternals.get(0),
                    "First initial internal must be 47103 (getLeftGrandChildPath(22, 11))");
            assertEquals(
                    49150L,
                    firstChunkInternals.get(firstChunkInternals.size() - 1),
                    "Last initial internal must be 49150 (getRightGrandChildPath(22, 11))");
            for (long p : firstChunkInternals) {
                assertEquals(
                        15,
                        Path.getRank(p),
                        "All first-chunk initial internals must be at rank chunkRootRank(4)+skipRanks(11)=15");
            }
            // Verify chunk root is at rank 4: 11 levels up from any initial internal
            assertEquals(
                    4,
                    Path.getRank(Path.getGrandParentPath(firstChunkInternals.get(0), 11)),
                    "Chunk root (11 levels above initial internal) must be at rank 4");

            // Report all first-chunk internals as clean
            for (long p : firstChunkInternals) {
                order.nodeReceived(p, true);
            }

            // ── Drive chunks 2–17, counting chunk transitions ─────────────────────────
            // In the all-clean scenario each chunk transition produces exactly one
            // PATH_NOT_AVAILABLE_YET from getNextLeafPathToSend(), then the next
            // chunk's initial internals appear in the queue.
            int chunkTransitions = 0;
            final List<Long> sentLeaves = new ArrayList<>();
            int consecutiveStalls = 0;
            while (true) {
                final long internal = order.getNextInternalPathToSend();
                if (internal != INVALID_PATH) {
                    // Verify every internal throughout all chunks is at rank 15
                    assertEquals(
                            15, Path.getRank(internal), "Every initial internal across all chunks must be at rank 15");
                    order.nodeReceived(internal, true);
                    consecutiveStalls = 0;
                    continue;
                }
                final long leaf = order.getNextLeafPathToSend();
                if (leaf == INVALID_PATH) {
                    break;
                }
                if (leaf == PATH_NOT_AVAILABLE_YET) {
                    chunkTransitions++;
                    assertTrue(++consecutiveStalls <= 100_000, "Algorithm must not stall indefinitely");
                } else {
                    sentLeaves.add(leaf);
                    consecutiveStalls = 0;
                }
            }

            assertEquals(0, sentLeaves.size(), "All-clean scenario must send zero leaves");
            assertEquals(
                    16,
                    chunkTransitions,
                    "Must traverse exactly 17 chunks (9 rank-26 + 8 rank-27) with 16 transitions between them");
        }

        @Test
        @DisplayName("13.9 — End-to-end: first leaf of every chunk is dirty, all other leaves skipped")
        void endToEndFirstLeafDirtyPerChunk() {
            // Same tree as 13.8: first=100000000 (rank 26), last=200000000 (rank 27),
            // chunkRootRank=4, 17 chunks (9 rank-26 + 8 rank-27), 2048 initial internals
            // at rank 15 per chunk.
            //
            // Per-chunk strategy:
            //   a15     = rank-15 ancestor of chunkFirstLeaf (one of the initial internals).
            //             Report ALL initial internals EXCEPT a15 as clean.
            //             Leave a15 unreported so skipCleanPaths does not skip the first leaf.
            //   aThresh = getGrandParentPath(chunkFirstLeaf, RANK_STEP=3).
            //             For rank-26 chunks: rank 23 >= threshold(23) → someDirtyPaths.
            //             For rank-27 chunks: rank 24 >= threshold(24) → someDirtyPaths.
            //             The leaf's RANK_STEP-parent check finds aThresh → hasDirtyParent=true.
            //   After the first leaf is sent, report a15 as clean.  skipCleanPaths then
            //   skips all remaining leaves in the chunk in one call → chunk transition.
            final long first = 100_000_000L;
            final long last = 200_000_000L;
            final var order = new TopToBottomTraversalOrder();
            order.start(first, last, first, last);

            final int INIT_INTERNAL_RANK = 15; // chunkRootRank(4) + skipRanks(11)
            int chunkTransitions = 0;
            long chunkFirstLeaf = first;
            final List<Long> sentLeaves = new ArrayList<>();

            while (true) {
                // ── Drain this chunk's initial internals ──────────────────────────────
                final List<Long> inits = drainInternals(order);
                assertFalse(inits.isEmpty(), "Each chunk must provide initial internals");

                // Derive chunk boundaries from the initial internals
                final int chunkLastRank = Path.getRank(chunkFirstLeaf);
                final long lastInitInternal = inits.get(inits.size() - 1);
                final long chunkLastLeafPath =
                        Path.getRightGrandChildPath(lastInitInternal, chunkLastRank - INIT_INTERNAL_RANK);

                // Rank-15 ancestor of chunkFirstLeaf (= one of the initial internals)
                final long a15 = Path.getGrandParentPath(chunkFirstLeaf, chunkLastRank - INIT_INTERNAL_RANK);
                // Ancestor at less than RANK_STEP levels up, so it's added to someDirtyPaths
                final long aThresh = Path.getGrandParentPath(chunkFirstLeaf, 2);

                assertEquals(INIT_INTERNAL_RANK, Path.getRank(a15), "a15 must be a rank-15 initial internal");
                assertTrue(inits.contains(a15), "a15 must be one of this chunk's initial internals");
                assertTrue(
                        Path.getRank(aThresh) >= chunkLastRank - 3, "aThresh rank must be >= someDirtyPaths threshold");

                // ── Report initial internals: all clean except a15 ────────────────────
                for (long p : inits) {
                    if (p != a15) {
                        order.nodeReceived(p, true);
                    }
                    // a15 is intentionally left unreported: not in cleanPaths, so
                    // skipCleanPaths will not skip the first leaf of this chunk.
                }

                // Inject aThresh directly into someDirtyPaths.  The first leaf's
                // RANK_STEP-parent walk will find aThresh and set hasDirtyParent=true.
                order.nodeReceived(aThresh, false);

                // Send the leaves. Dirty node aThresh covers some dirty nodes
                long expectedLeaf = chunkFirstLeaf;
                while (Path.getGrandParentPath(expectedLeaf, 2) == aThresh) {
                    long leaf = order.getNextLeafPathToSend();
                    assertEquals(
                            expectedLeaf,
                            leaf,
                            "Leaf " + expectedLeaf + " of chunk (startLeaf=" + chunkFirstLeaf + ") must be sent");
                    sentLeaves.add(leaf);
                    expectedLeaf++;
                }

                // The 5th leaf must not be sent yet
                assertEquals(PATH_NOT_AVAILABLE_YET, order.getNextLeafPathToSend());

                // Skip remaining chunk leaves. This is not something that happens in real world,
                // but should work for the test. Adding a15 to cleanPaths lets skipCleanPaths drain
                // the rest of the chunk's leaves in a single getNextLeafPathToSend() call.
                order.nodeReceived(a15, true);

                final long next = order.getNextLeafPathToSend();
                if (next == INVALID_PATH) {
                    // Last chunk: chunkLastLeafPath > lastLeafPath, algorithm terminates.
                    break;
                }
                assertEquals(
                        PATH_NOT_AVAILABLE_YET,
                        next,
                        "After first leaf, remaining clean chunk leaves must cause chunk transition");
                chunkTransitions++;
                chunkFirstLeaf = chunkLastLeafPath + 1;
            }

            // aThresh covers 1 to 4 dirty leaves
            assertTrue(sentLeaves.size() <= 17 * 4, "Too many dirty leaves are sent across all 17 chunks");
            assertTrue(sentLeaves.size() >= 17, "Too few dirty leaves are sent across all 17 chunks");
            assertEquals(16, chunkTransitions, "Must have exactly 16 chunk transitions (17 chunks total)");
            assertEquals(first, sentLeaves.get(0), "First sent leaf must equal firstLeafPath");
            assertEquals(
                    192_937_986L,
                    sentLeaves.getLast(),
                    "Last sent leaf must be fourth leaf of chunk 17 (= getRightGrandChildPath(21, 27-4)+4)");
            // Verify each sent leaf is the chunkFirstLeaf for its chunk (monotonically increasing)
            for (int i = 1; i < sentLeaves.size(); i++) {
                assertTrue(
                        sentLeaves.get(i) > sentLeaves.get(i - 1),
                        "Sent leaves must be strictly increasing (each is first leaf of its chunk)");
            }
        }
    }

    // ── Utility ───────────────────────────────────────────────────────────────

    /** Returns a list containing all longs in [lo, hi] inclusive. */
    private static List<Long> rangeClosed(final long lo, final long hi) {
        final List<Long> list = new ArrayList<>((int) (hi - lo + 1));
        for (long v = lo; v <= hi; v++) {
            list.add(v);
        }
        return list;
    }
}
