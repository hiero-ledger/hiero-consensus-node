// SPDX-License-Identifier: Apache-2.0
package com.swirlds.virtualmap.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.swirlds.common.merkle.route.MerkleRoute;
import com.swirlds.common.merkle.route.MerkleRouteFactory;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.stream.Stream;
import org.hiero.base.utility.test.fixtures.tags.TestComponentTags;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class PathTests {

    // Args are Path, Rank, Index
    private static Stream<Arguments> providePathRankIndexArgs() {
        final List<Arguments> args = new ArrayList<>(65);

        // Rank 0
        args.add(Arguments.of(0L, 0, 0L));

        // Rank 1
        args.add(Arguments.of(1L, 1, 0L));
        args.add(Arguments.of(2L, 1, 1L));

        // Rank 2-63
        for (long rank = 2, first = 3, last = first * 2;
                rank < Path.MAX_RANK_VALUE;
                rank++, first = last + 1, last = first * 2) {
            final long midPath = first + ((last - first) / 2);
            args.add(Arguments.of(first, (int) rank, 0L));
            args.add(Arguments.of(midPath, (int) rank, midPath - first));
            args.add(Arguments.of(last, (int) rank, last - first));
        }

        // Invalid path cases
        args.add(Arguments.of(-1L, -1, -1L));

        return args.stream();
    }

    @ParameterizedTest
    @MethodSource("providePathRankIndexArgs")
    @Tag(TestComponentTags.VMAP)
    @DisplayName("Rank of valid paths")
    void testRanks(final long path, final int rank, final long index) {
        assertEquals(rank, Path.getRank(path), "unexpected value from getRank().");
    }

    @ParameterizedTest
    @MethodSource("providePathRankIndexArgs")
    @Tag(TestComponentTags.VMAP)
    @DisplayName("Index within rank")
    void testIndexInRank(final long path, final int rank, final long index) {
        assertEquals(index, Path.getIndexInRank(path), "unexpected value from getIndexInRank().");
    }

    @ParameterizedTest
    @MethodSource("providePathRankIndexArgs")
    @Tag(TestComponentTags.VMAP)
    @DisplayName("Compute path given a rank and index")
    void testPathForRankAndIndex(final long path, final int rank, final long index) {
        assertEquals(path, Path.getPathForRankAndIndex(rank, index), "unexpected value from getPathForRankAndIndex().");
    }

    @ParameterizedTest
    @MethodSource("providePathRankIndexArgs")
    @Tag(TestComponentTags.VMAP)
    @DisplayName("Check isLeft")
    void testIsLeft(final long path, final int rank, final long index) {
        if (path != -1) {
            assertEquals(index == 0, Path.isLeft(path), "unexpected value from isLeft().");
        }
    }

    @ParameterizedTest
    @MethodSource("providePathRankIndexArgs")
    @Tag(TestComponentTags.VMAP)
    @DisplayName("Check isFarRight")
    void testIsFarRight(final long path, final int rank, final long index) {
        if (path != -1) {
            assertEquals(Path.getIndexInRank(path + 1) == 0, Path.isFarRight(path), Long.toBinaryString(path));
        }
    }

    @Test
    @Tag(TestComponentTags.VMAP)
    @DisplayName("Check parent path")
    void testGetParentPath() {
        // This isn't easy to test with the parameterized test data, so I'll do some tests manually
        assertEquals(
                Path.INVALID_PATH,
                Path.getParentPath(Path.INVALID_PATH),
                "unexpected value from getParentPath(INVALID_PATH)");
        assertEquals(
                Path.INVALID_PATH,
                Path.getParentPath(Path.ROOT_PATH),
                "unexpected value from getParentPath(ROOT_PATH)");

        // Rank 1
        assertEquals(Path.ROOT_PATH, Path.getParentPath(1), "unexpected value from getParentPath(internal path 1)");
        assertEquals(Path.ROOT_PATH, Path.getParentPath(2), "unexpected value from getParentPath(internal path 2)");

        // Rank 2
        assertEquals(1, Path.getParentPath(3), "unexpected value from getParentPath(internal path 3)");
        assertEquals(1, Path.getParentPath(4), "unexpected value from getParentPath(internal path 4)");
        assertEquals(2, Path.getParentPath(5), "unexpected value from getParentPath(internal path 5)");
        assertEquals(2, Path.getParentPath(6), "unexpected value from getParentPath(internal path 6)");

        // Rank 3
        assertEquals(3, Path.getParentPath(7), "unexpected value from getParentPath(internal path 7)");
        assertEquals(3, Path.getParentPath(8), "unexpected value from getParentPath(internal path 8)");
        assertEquals(4, Path.getParentPath(9), "unexpected value from getParentPath(internal path 9)");
        assertEquals(4, Path.getParentPath(10), "unexpected value from getParentPath(internal path 10)");
        assertEquals(5, Path.getParentPath(11), "unexpected value from getParentPath(internal path 11)");
        assertEquals(5, Path.getParentPath(12), "unexpected value from getParentPath(internal path 12)");
        assertEquals(6, Path.getParentPath(13), "unexpected value from getParentPath(internal path 13)");
        assertEquals(6, Path.getParentPath(14), "unexpected value from getParentPath(internal path 14)");
    }

    private long dumbGetGrandParentPath(final long path, final int levels) {
        long result = path;
        for (int i = 0; i < levels; i++) {
            result = Path.getParentPath(result);
            if (result == Path.INVALID_PATH) {
                return Path.INVALID_PATH;
            }
        }
        return result;
    }

    @Test
    @Tag(TestComponentTags.VMAP)
    @DisplayName("Check grand parent path")
    void testGetGrandParentPath() {
        for (long path = 0; path < 2048; path++) {
            for (int levels = 0; levels < 5; levels++) {
                assertEquals(
                        dumbGetGrandParentPath(path, levels),
                        Path.getGrandParentPath(path, levels),
                        "Wrong grand parent path path=" + path + " levels=" + levels);
            }
        }
    }

    @Test
    @Tag(TestComponentTags.VMAP)
    @DisplayName("Check left child path computation")
    void testGetLeftChild() {
        // This isn't easy to test with the parameterized test data, so I'll do some tests manually
        assertEquals(
                Path.INVALID_PATH,
                Path.getLeftChildPath(Path.INVALID_PATH),
                "unexpected value from getLeftChildPath(INVALID_PATH)");

        // Rank 0
        assertEquals(
                Path.FIRST_LEFT_PATH,
                Path.getLeftChildPath(Path.ROOT_PATH),
                "unexpected value from getLeftChildPath(ROOT_PATH)");

        // Rank 1
        assertEquals(3, Path.getLeftChildPath(1), "unexpected value from getLeftChildPath(internal path 1)");
        assertEquals(5, Path.getLeftChildPath(2), "unexpected value from getLeftChildPath(internal path 2)");

        // Rank 2
        assertEquals(7, Path.getLeftChildPath(3), "unexpected value from getLeftChildPath(internal path 3)");
        assertEquals(9, Path.getLeftChildPath(4), "unexpected value from getLeftChildPath(internal path 4)");
        assertEquals(11, Path.getLeftChildPath(5), "unexpected value from getLeftChildPath(internal path 5)");
        assertEquals(13, Path.getLeftChildPath(6), "unexpected value from getLeftChildPath(internal path 6)");
    }

    private long dumbGetLeftGrandChild(final long path, final int levels) {
        long result = path;
        for (int i = 0; i < levels; i++) {
            result = Path.getLeftChildPath(result);
        }
        return result;
    }

    @Test
    @Tag(TestComponentTags.VMAP)
    @DisplayName("Check left grand child path computation")
    void testGetLeftGrandChild() {
        for (long path = 0; path < 2048; path++) {
            for (int levels = 0; levels < 5; levels++) {
                assertEquals(
                        dumbGetLeftGrandChild(path, levels),
                        Path.getLeftGrandChildPath(path, levels),
                        "Wrong left grand chilf path path=" + path + " levels=" + levels);
            }
        }
    }

    @Test
    @Tag(TestComponentTags.VMAP)
    @DisplayName("Check right child path computation")
    void testGetRightChild() {
        // Rank 0
        assertEquals(2, Path.getRightChildPath(Path.ROOT_PATH), "unexpected value from getRightChildPath(ROOT_PATH)");

        // Rank 1
        assertEquals(4, Path.getRightChildPath(1), "unexpected value from getRightChildPath(internal path 1)");
        assertEquals(6, Path.getRightChildPath(2), "unexpected value from getRightChildPath(internal path 2)");

        // Rank 2
        assertEquals(8, Path.getRightChildPath(3), "unexpected value from getRightChildPath(internal path 3)");
        assertEquals(10, Path.getRightChildPath(4), "unexpected value from getRightChildPath(internal path 4)");
        assertEquals(12, Path.getRightChildPath(5), "unexpected value from getRightChildPath(internal path 5)");
        assertEquals(14, Path.getRightChildPath(6), "unexpected value from getRightChildPath(internal path 6)");
    }

    @Test
    @Tag(TestComponentTags.VMAP)
    @DisplayName("Check grand child computation")
    void testGetGrandChild() {
        // Root grand children
        assertEquals(3, Path.getLeftGrandChildPath(0, 2), "Left grand child (0, 2) is not 3");
        assertEquals(6, Path.getRightGrandChildPath(0, 2), "Right grand child (0, 2) is not 6");
        assertEquals(7, Path.getLeftGrandChildPath(0, 3), "Left grand child (0, 3) is not 7");
        assertEquals(14, Path.getRightGrandChildPath(0, 3), "Right grand child (0, 3) is not 14");

        // Path 4 grand children
        assertEquals(19, Path.getLeftGrandChildPath(4, 2), "Left grand child (0, 2) is not 19");
        assertEquals(22, Path.getRightGrandChildPath(4, 2), "Right grand child (0, 2) is not 22");
        assertEquals(39, Path.getLeftGrandChildPath(4, 3), "Left grand child (0, 3) is not 39");
        assertEquals(46, Path.getRightGrandChildPath(4, 3), "Right grand child (0, 3) is not 46");

        // Check distance between right and left grand child paths
        for (int i = 0; i < 8; i++) {
            final long parent = 7 + i; // rank 3
            final long leftPath = Path.getLeftGrandChildPath(parent, i);
            final long rightPath = Path.getRightGrandChildPath(parent, i);
            assertEquals((1 << i) - 1, rightPath - leftPath);
        }
    }

    @Test
    @Tag(TestComponentTags.VMAP)
    @DisplayName("Check isInSubTree")
    void testCheckIsInSubTree() {
        final Random r = new Random(1234509876);

        // Root sub-tree: any path should be in path 0 sub-tree
        for (int i = 0; i < 100; i++) {
            final long path = r.nextLong(1L << 40);
            assertTrue(Path.isInSubTree(0, path), "Any path should be in the root sub-tree");
        }

        // A few random checks
        assertTrue(Path.isInSubTree(1, 15));
        assertTrue(Path.isInSubTree(1, 19));
        assertTrue(Path.isInSubTree(1, 10));
        assertTrue(Path.isInSubTree(1, 22));
        assertFalse(Path.isInSubTree(1, 11));
        assertFalse(Path.isInSubTree(1, 14));
        assertFalse(Path.isInSubTree(1, 23));
        assertFalse(Path.isInSubTree(1, 27));
        assertFalse(Path.isInSubTree(1, 2));

        assertTrue(Path.isInSubTree(4, 19));
        assertTrue(Path.isInSubTree(4, 22));
        assertTrue(Path.isInSubTree(4, 46));
        assertFalse(Path.isInSubTree(4, 3));
        assertFalse(Path.isInSubTree(4, 5));
        assertFalse(Path.isInSubTree(4, 8));
        assertFalse(Path.isInSubTree(4, 18));
        assertFalse(Path.isInSubTree(4, 11));
        assertFalse(Path.isInSubTree(4, 14));

        // Check using grand child paths
        for (int parent = 7; parent <= 14; parent++) {
            final int height = r.nextInt(5) + 2;
            final long leftGrandChild = Path.getLeftGrandChildPath(parent, height);
            final long rightGrandChild = Path.getRightGrandChildPath(parent, height);
            assertFalse(Path.isInSubTree(parent, leftGrandChild - 1));
            for (long p = leftGrandChild; p <= rightGrandChild; p++) {
                assertTrue(Path.isInSubTree(parent, p));
            }
            assertFalse(Path.isInSubTree(parent, rightGrandChild + 1));
        }
    }

    // Args are Path, and List<Long> { Rank, Index }
    private static Stream<Arguments> providePathWalkingArgs() {
        return Stream.of(
                Arguments.of(7L, List.of(1L, 3L)),
                Arguments.of(8L, List.of(1L, 3L)),
                Arguments.of(9L, List.of(1L, 4L)),
                Arguments.of(10L, List.of(1L, 4L)),
                Arguments.of(11L, List.of(2L, 5L)),
                Arguments.of(12L, List.of(2L, 5L)),
                Arguments.of(13L, List.of(2L, 6L)),
                Arguments.of(14L, List.of(2L, 6L)),
                Arguments.of(15L, List.of(1L, 3L, 7L)),
                Arguments.of(16L, List.of(1L, 3L, 7L)),
                Arguments.of(17L, List.of(1L, 3L, 8L)),
                Arguments.of(18L, List.of(1L, 3L, 8L)),
                Arguments.of(19L, List.of(1L, 4L, 9L)),
                Arguments.of(20L, List.of(1L, 4L, 9L)),
                Arguments.of(21L, List.of(1L, 4L, 10L)),
                Arguments.of(22L, List.of(1L, 4L, 10L)),
                Arguments.of(23L, List.of(2L, 5L, 11L)),
                Arguments.of(24L, List.of(2L, 5L, 11L)),
                Arguments.of(25L, List.of(2L, 5L, 12L)),
                Arguments.of(26L, List.of(2L, 5L, 12L)),
                Arguments.of(27L, List.of(2L, 6L, 13L)),
                Arguments.of(28L, List.of(2L, 6L, 13L)),
                Arguments.of(29L, List.of(2L, 6L, 14L)),
                Arguments.of(30L, List.of(2L, 6L, 14L)));
    }

    @ParameterizedTest
    @MethodSource("providePathWalkingArgs")
    @Tag(TestComponentTags.VMAP)
    @DisplayName("Check walking down paths")
    void testGetNextStep(final long terminalPath, final List<Long> expectedPaths) {
        long path = 0;
        for (Long expected : expectedPaths) {
            assertEquals(expected, Path.getNextStep(path, terminalPath), "unexpected value from getNextStep()");
            path = expected;
        }
    }

    @Test
    @Tag(TestComponentTags.VMAP)
    @DisplayName("Check right child path computation")
    void testGetSiblingPath() {
        // Rank 0
        assertEquals(
                Path.INVALID_PATH,
                Path.getSiblingPath(Path.ROOT_PATH),
                "unexpected value from getRightChildPath(ROOT_PATH)");

        // Rank 1
        assertEquals(2, Path.getSiblingPath(1), "unexpected value from getRightChildPath(internal node 1)");
        assertEquals(1, Path.getSiblingPath(2), "unexpected value from getRightChildPath(internal node 2)");

        // Rank 2
        assertEquals(4, Path.getSiblingPath(3), "unexpected value from getRightChildPath(internal node 3)");
        assertEquals(3, Path.getSiblingPath(4), "unexpected value from getRightChildPath(internal node 4)");
        assertEquals(6, Path.getSiblingPath(5), "unexpected value from getRightChildPath(internal node 5)");
        assertEquals(5, Path.getSiblingPath(6), "unexpected value from getRightChildPath(internal node 6)");
    }

    /**
     * This is a basic test to validate that MerkleRoutes for Virtual Nodes are computed as expected.
     * Once the test module is added to the swirlds-test module, we should recreate this test with
     * MerkleBinaryTree, by adding one million elements to the MerkleBinaryTree, and the same elements
     * added to a {@link com.swirlds.virtualmap.VirtualMap}. Then, we iterate over each node and their
     * MerkleRoutes should be the same.
     */
    @Test
    @Tag(TestComponentTags.VMAP)
    void getRouteStepsFromRoot() {
        final List<Integer> emptyRoute = Path.getRouteStepsFromRoot(0);
        assertTrue(emptyRoute.isEmpty(), "No route from root to root");

        MerkleRoute route = MerkleRouteFactory.buildRoute(Path.getRouteStepsFromRoot(1));
        assertEquals(MerkleRouteFactory.buildRoute(0), route, "Only left child");

        route = MerkleRouteFactory.buildRoute(Path.getRouteStepsFromRoot(2));
        assertEquals(MerkleRouteFactory.buildRoute(1), route, "Only right child");

        route = MerkleRouteFactory.buildRoute(Path.getRouteStepsFromRoot(3));
        assertEquals(MerkleRouteFactory.buildRoute(0, 0), route, "Left-left child");

        route = MerkleRouteFactory.buildRoute(Path.getRouteStepsFromRoot(4));
        assertEquals(MerkleRouteFactory.buildRoute(0, 1), route, "Left-Right child");

        route = MerkleRouteFactory.buildRoute(Path.getRouteStepsFromRoot(5));
        assertEquals(MerkleRouteFactory.buildRoute(1, 0), route, "Right-Left child");

        route = MerkleRouteFactory.buildRoute(Path.getRouteStepsFromRoot(6));
        assertEquals(MerkleRouteFactory.buildRoute(1, 1), route, "Right-right child");

        route = MerkleRouteFactory.buildRoute(Path.getRouteStepsFromRoot(7));
        assertEquals(MerkleRouteFactory.buildRoute(0, 0, 0), route, "Left-left-Left child");

        route = MerkleRouteFactory.buildRoute(Path.getRouteStepsFromRoot(8));
        assertEquals(MerkleRouteFactory.buildRoute(0, 0, 1), route, "Left-left-right child");
    }
}
