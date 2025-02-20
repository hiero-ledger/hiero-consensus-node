// SPDX-License-Identifier: Apache-2.0
package com.swirlds.virtualmap.internal;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * A utility class with static methods for working with paths in the virtual tree. A path in the virtual tree
 * is efficiently represented as a single long. This works because internal nodes on the virtual tree always
 * have the same number of children, and that number is always a power of two. In the current implementation,
 * the virtual tree is strictly binary. Encoding the path as a long is critical to the overall performance
 * of the system.
 */
public final class Path {

    /**
     * A special constant that represents the Path of a root node. It isn't necessary
     * for this constant to be used, but it makes the code a little more readable.
     */
    public static final long ROOT_PATH = 0L;

    /**
     * A special constant that represents the first left child of the root not. It isn't
     * necessary, but helps clarify the code a little.
     */
    public static final long FIRST_LEFT_PATH = 1L;

    /**
     * Represents an invalid path. For example, the parent of a root node is an invalid path.
     * Operations on invalid paths return invalid paths.
     */
    public static final long INVALID_PATH = -1L; // All 1's!

    /**
     * The maximum "rank" in the tree. The "rank" is the level. A rank of 0 is for the root,
     * the first rank (rank 1) contains the left and right children of root, and so on. Since
     * the path is a long, and all longs are signed the max rank is 62. With some effort we may
     * be able to support negative longs, but this number of ranks is already so incredibly large
     * and beyond our scope, it should be OK.
     */
    public static final int MAX_RANK_VALUE = 62;

    /**
     * The max number of leading zeros that can be in a path. Defines an otherwise magic number.
     */
    private static final int MAX_NUMBER_OF_LEADING_ZEROS = 64;

    /**
     * The leading bit (before being shifted left (rank - 1) times) used by the getIndexInRank() method.
     */
    private static final long LEADING_BIT_FOR_INDEX_IN_RANK = 2L;

    /**
     * Disable creation of Path instances
     */
    private Path() {}

    // Gets the rank part of the pathId

    /**
     * Given some path, get the rank of that path. For example, if the path is 0, then the rank is 0.
     * If the path is 1 or 2, then the rank is 1, and so forth.
     *
     * @param path
     * 		The path
     * @return
     * 		The rank of the path. If the path is {@link #INVALID_PATH}, then the rank will be -1.
     */
    public static int getRank(long path) {
        if (path == 0) {
            return 0;
        }

        return (63 - Long.numberOfLeadingZeros(path + 1));
    }

    /**
     * Gets the zero-based index of the Path within its rank, where {@code 0} is the
     * left-most node at that rank, and the number increases going towards the right.
     *
     * @return A non-negative index of the node with this path within its rank.
     */
    public static long getIndexInRank(final long path) {
        if (path == INVALID_PATH) {
            return INVALID_PATH;
        }

        if (path == ROOT_PATH) {
            return 0;
        }

        final int rank = getRank(path);
        return (path - (LEADING_BIT_FOR_INDEX_IN_RANK << (rank - 1)) + 1);
    }

    /**
     * Gets the Path representing a node located at the given rank and the given index
     * within that rank. Throws an IllegalArgumentException if the index is greater than
     * the max possible for that rank.
     *
     * @param rank
     * 		A non-negative rank for the node
     * @param index
     * 		The non-negative index into the rank at which to find the node
     * @return A Path representing the path to the node found at the given rank and index. Never returns null.
     */
    public static long getPathForRankAndIndex(final int rank, final long index) {
        if (rank == -1) {
            return INVALID_PATH;
        }

        assert rank >= 0 : "Rank must be strictly non-negative";
        assert index >= 0 : "Index must be strictly non-negative";

        if (rank == 0 && index == 0) {
            return ROOT_PATH;
        }

        final long maxForRank = (1L << rank);
        if (index > (maxForRank - 1)) {
            throw new IllegalArgumentException("The index [" + index
                    + "] is too large for the number of items at this rank. maxForRank =" + maxForRank);
        }

        return index + (2L << (rank - 1)) - 1;
    }

    /**
     * Gets whether this Path represents a left-side node.
     *
     * @return Whether this Path is a left-side node.
     */
    public static boolean isLeft(long path) {
        assert path != INVALID_PATH;
        return path == 0 || (path & 0x1) == 1;
    }

    public static boolean isRight(long path) {
        assert path != INVALID_PATH;
        return (path & 0x1) == 0;
    }

    public static boolean isFarRight(long path) {
        // It turns out, all 0's followed by all 1's followed by a single 0 is always the far right node.
        // Given a valid far right like 0b0000000_00111110, -1L << (64 - numLeadingZeros) will produce a complimentary
        // mask for the high leading 0's, such as 0b11111111_11000000. Xor on the path with 0x1 will end up
        // flipping the low bit, so it becomes 0b00000000_00111111. By or'ing the two together, we should
        // get all 1's, which is represented as -1L.
        assert path != INVALID_PATH;
        final int numLeadingZeros = Long.numberOfLeadingZeros(path);
        return path == 0 || ((path ^ 0x1) | (-1L << (MAX_NUMBER_OF_LEADING_ZEROS - numLeadingZeros))) == -1L;
    }

    /**
     * Gets the path of a node that would be the parent of the node represented by this path.
     *
     * @return The Path of the parent. {@link #INVALID_PATH} if this Path is already the root (root nodes
     * 		do not have a parent).
     */
    public static long getParentPath(long path) {
        return (path - 1) >> 1;
    }

    /**
     * Gets the path of a node that is {@code levels} levels up from the given path. If the given
     * path is less than {@code levels} from the root, {@link #INVALID_PATH} is returned.
     */
    public static long getGrandParentPath(final long path, final int levels) {
        final int rank = getRank(path);
        if (rank < levels) {
            return INVALID_PATH;
        }
        return (path - (1L << levels) + 1) >> levels;
    }

    /**
     * Gets the path of a child of the given {@code parentPath} at index {@code childIndex}.
     *
     * @param parentPath
     * 		The parent path. Must be valid.
     * @param childIndex
     * 		The child index. Must be 0 or 1.
     * @return
     * 		The path of the child.
     */
    public static long getChildPath(final long parentPath, final int childIndex) {
        assert childIndex == 0 || childIndex == 1 : "Only binary trees are supported";
        return childIndex == 0 ? getLeftChildPath(parentPath) : getRightChildPath(parentPath);
    }

    /**
     * Gets the path of the left-child.
     *
     * @return The path of the left child. This is never null.
     */
    public static long getLeftChildPath(long path) {
        return (path << 1) + 1;
    }

    /**
     * Gets the path of the left-most child node, which is {@code levels} levels below the given path.
     */
    public static long getLeftGrandChildPath(final long path, final int levels) {
        return ((path + 1) << levels) - 1;
    }

    /**
     * Gets the part of the right-child.
     *
     * @return The path of the right child. This is never null.
     */
    public static long getRightChildPath(long path) {
        return (path << 1) + 2;
    }

    public static long getRightGrandChildPath(final long path, final int levels) {
        return (path << levels) + (2L << levels) - 2;
    }

    public static boolean isInSubTree(final long parentPath, final long pathToCheck) {
        if (parentPath == pathToCheck) {
            return true;
        }
        final int parentPathRank = getRank(parentPath);
        final int pathToCheckRank = getRank(pathToCheck);
        if (pathToCheckRank <= parentPathRank) {
            return false;
        }
        final long pathToCheckParent = getGrandParentPath(pathToCheck, pathToCheckRank - parentPathRank);
        return parentPath == pathToCheckParent;
    }

    /**
     * Computes the {@link com.swirlds.common.merkle.route.MerkleRoute} by iterating over
     * every node from the root of the Virtual Tree up to the {@code targetPath}, and
     * determining if the path is left or right (0 or 1).
     *
     * @param targetPath
     * 			path for which we want its route
     * @return list of integers representing the {@link com.swirlds.common.merkle.route.MerkleRoute} of this path.
     */
    public static List<Integer> getRouteStepsFromRoot(long targetPath) {
        final List<Integer> routes = new ArrayList<>();
        while (targetPath > 0) {
            routes.add(isLeft(targetPath) ? 0 : 1);
            targetPath = getParentPath(targetPath);
        }

        Collections.reverse(routes);
        return routes;
    }

    /**
     * Gets the next step along the path from thisPath to targetPath. For example,
     * if thisPath = 0 and targetPath = 3, then this method will return 1.
     *
     * @param thisPath
     * 		The start path. Must not be INVALID_PATH.
     * @param targetPath
     * 		The path we're trying to get to. Must not be INVALID_PATH and must be &gt; thisPath.
     * @return The path representing the next node from thisPath towards targetPath.
     */
    public static long getNextStep(final long thisPath, final long targetPath) {
        assert thisPath < targetPath;
        assert thisPath != INVALID_PATH;
        assert targetPath != INVALID_PATH;

        // This is fairly complex because we use incrementing numbers for our paths, rather
        // than using 0's and 1's to indicate "left or right". Given some "thisPath" starting
        // point, I need to answer the question, is the "targetPath" in the left or right sub-tree
        // formed by using "thisPath" as the root. That will tell me whether to traverse down the
        // left or right child.

        // Figure out the relative "distance" from thisPath to targetPath
        final int thisRank = getRank(thisPath);
        final int targetRank = getRank(targetPath);
        final int relativeRank = targetRank - thisRank;

        // Figure out the left-most node of the subtree. I can figure this out by taking the
        // bit pattern of the "thisPath" node and shifting it left by the distance (relative rank),
        // and fill in the right of the bit pattern with 1's instead of 0's
        final long leftMostPath = (thisPath << relativeRank) | (~(-1L << relativeRank));

        // Figure out the mid-point, that is, the number of leaves on the left of the sub-tree
        final int midPoint = 1 << (relativeRank - 1);

        // If the targetPath is within the left side of the sub-tree, then return the left child path,
        // otherwise return the right child path.
        return (targetPath - leftMostPath < midPoint) ? getLeftChildPath(thisPath) : getRightChildPath(thisPath);
    }

    public static long getSiblingPath(long path) {
        if (path == ROOT_PATH) {
            return INVALID_PATH;
        }

        return isLeft(path) ? (path + 1) : (path - 1);
    }
}
