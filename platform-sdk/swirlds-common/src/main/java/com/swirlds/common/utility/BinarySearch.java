// SPDX-License-Identifier: Apache-2.0
package com.swirlds.common.utility;

import com.swirlds.base.function.CheckedFunction;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.function.LongToIntFunction;

/**
 * Implements a binary search algorithm on a generic data structure.
 */
public final class BinarySearch {

    private BinarySearch() {}

    /**
     * Search for a value in a sorted data structure indexed by a continuous sequence of longs.
     *
     * @param minimumIndex
     * 		the minimum index to search (inclusive)
     * @param maximumIndex
     * 		the maximum index to search (exclusive)
     * @param compareToDesired
     * 		the result of {@link Comparable#compareTo(Object)} of the object at the given index to the desired
     * 		object
     * @return an index with a value that satisfies the comparison function, or if no such index can be found
     * 		the highest index that causes the comparison function to return a negative value
     * 		(i.e. the largest value that does exceed the requested value)
     * @throws java.util.NoSuchElementException
     * 		if no index that causes the comparison function to return a negative value can be found
     * 		(i.e. all of the values in the provided range are too large)
     */
    public static long search(
            final long minimumIndex, final long maximumIndex, @NonNull final LongToIntFunction compareToDesired) {
        Objects.requireNonNull(compareToDesired, "compareToDesired must not be null");
        return throwingSearch(minimumIndex, maximumIndex, compareToDesired::applyAsInt);
    }

    /**
     * Search for a value in a sorted data structure indexed by a continuous sequence of longs.
     *
     * @param minimumIndex
     * 		the minimum index to search (inclusive)
     * @param maximumIndex
     * 		the maximum index to search (exclusive)
     * @param compareToDesired
     * 		the result of {@link Comparable#compareTo(Object)} of the object at the given index to the desired
     * 		object
     * @param <E>
     * 		the type of exception thrown by the function
     * @return an index with a value that satisfies the comparison function, or if no such index can be found
     * 		the highest index that causes the comparison function to return a negative value
     * 		(i.e. the largest value that does exceed the requested value)
     * @throws java.util.NoSuchElementException
     * 		if no index that causes the comparison function to return a negative value can be found
     * 		(i.e. all of the values in the provided range are too large)
     * @throws E
     * 		if compareToDesired throws E
     */
    public static <E extends Exception> long throwingSearch(
            final long minimumIndex,
            final long maximumIndex,
            @NonNull final CheckedFunction<Long, Integer, E> compareToDesired)
            throws E {

        if (minimumIndex >= maximumIndex) {
            throw new IllegalArgumentException("maximum index must be strictly greater than the minimum index");
        }

        Objects.requireNonNull(compareToDesired, "compareToDesired must not be null");

        long leftBoundary = minimumIndex;
        long rightBoundary = maximumIndex - 1;

        if (compareToDesired.apply(leftBoundary) > 0) {
            // There exists no index in the specified range that causes the comparison function to return
            // a negative value.
            throw new NoSuchElementException("Requested value is not in the specified range");
        }

        if (compareToDesired.apply(rightBoundary) < 0) {
            // Special case. All of the values in the specified range cause the comparison
            // function to return a negative value, so just return the right boundary.
            return rightBoundary;
        }

        while (true) {
            final long middleIndex = leftBoundary + ((rightBoundary - leftBoundary) / 2);

            final int comparison = compareToDesired.apply(middleIndex);

            if (comparison == 0) {
                // we've gotten lucky found a perfect match
                return middleIndex;
            } else if (middleIndex == leftBoundary) {
                // The middle index will only equal the left boundary in one of two cases:
                //   1) The right index and the left index are equal
                //   2) The right index is exactly 1 more than the left index
                // In either case we are done with the traversal.

                if (compareToDesired.apply(rightBoundary) == 0) {
                    // A perfect match exists, and it is the right boundary
                    return rightBoundary;
                } else {
                    // The right index is too high, so the left index must
                    // be the highest index that isn't too high.
                    return leftBoundary;
                }
            } else if (comparison < 0) {
                leftBoundary = middleIndex;
            } else {
                rightBoundary = middleIndex;
            }
        }
    }
}
