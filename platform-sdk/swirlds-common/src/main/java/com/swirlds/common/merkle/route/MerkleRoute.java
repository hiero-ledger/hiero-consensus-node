// SPDX-License-Identifier: Apache-2.0
package com.swirlds.common.merkle.route;

import java.util.Iterator;
import java.util.List;
import org.hiero.consensus.model.io.SelfSerializable;

/**
 * <p>
 * A MerkleRoute describes a path through a merkle tree.
 * Each "step" in the route describes the child index to traverse next within the tree.
 * </p>
 *
 * <p>
 * MerkleRoute objects are immutable after creation (with the exception of deserialization).
 * No operation should be capable of modifying an existing route.
 * </p>
 *
 * <p>
 * Although merkle routes are capable of being serialized, it is strongly advised that routes are not included
 * in the serialization of the state. The binary format of merkle routes is subject to change at a future date,
 * and the inclusion of a route in the state could lead to complications during migration.
 * </p>
 *
 * Implementations of MerkleRoute are expected to override equals() and hashCode().
 */
public interface MerkleRoute extends Comparable<MerkleRoute>, Iterable<Integer>, SelfSerializable {

    /**
     * The maximum length that a route is permitted to be.
     */
    int MAX_ROUTE_LENGTH = 1024;

    /**
     * @return an iterator that walks over the steps in the route
     */
    @Override
    Iterator<Integer> iterator();

    /**
     * <p>
     * Get the number of steps in the route. A node at the root of a tree will have a route of size 0.
     * </p>
     *
     * <p>
     * This operation may be more expensive than O(1) for some route implementations (for example, some implementations
     * that are compressed may compute the size as needed so the size doesn't need to be stored in memory).
     * </p>
     */
    int size();

    /**
     * Check if this route is the empty route.
     *
     * @return true if this route has 0 steps
     */
    boolean isEmpty();

    /**
     * Create a new route that shares all steps of this route but with an additional step at the end.
     *
     * @param step
     * 		the step to add to the new route
     * @return a new route
     */
    MerkleRoute extendRoute(final int step);

    /**
     * Create a new route that shares all steps of this route but with additional steps at the end.
     *
     * @param steps
     * 		the steps to add to the new route
     * @return a new route
     */
    MerkleRoute extendRoute(final List<Integer> steps);

    /**
     * Create a new route that shares all steps of this route but with additional steps at the end.
     *
     * @param steps
     * 		teh steps to add to the new route
     * @return a new route
     */
    MerkleRoute extendRoute(final int... steps);

    /**
     * Get the parent merkle route. This is not efficient for some implementations,
     * so be cautious when using this method in performance-sensitive code.
     *
     * @return the parent merkle route
     * @throws java.util.NoSuchElementException
     * 		if this route is empty
     */
    MerkleRoute getParent();

    /**
     * <p>
     * Compare this route to another. Will return -1 if this route is "to the left" of the other route, 1 if this route
     * is "to the right" of the other route, and 0 if this route is an ancestor, descendant, or the same as the other
     * route.
     * </p>
     *
     * <p>
     * To determine if a route is to the left or the right of another route, find a route that is the last common
     * ancestor of both (i.e. the longest route that matches the beginnings of both routes). On the last common ancestor
     * find the two children that are ancestors of each of the routes. Route A is to the left of route B if the ancestor
     * of route A is to the left of the ancestor of route B on the last common ancestor.
     * </p>
     */
    @Override
    int compareTo(final MerkleRoute that);

    /**
     * Returns true if a given route is an ancestor of this route. A route is considered to be an ancestor to itself.
     *
     * @param that
     * 		a route to compare to
     * @return true if the given route is an ancestor of this route
     */
    boolean isAncestorOf(final MerkleRoute that);

    /**
     * Returns true if a given route is a descendant of this route. A route is considered to be a descendant to itself.
     *
     * @param that
     * 		a route to compare to
     * @return true if the given route is a descendant of this route
     */
    boolean isDescendantOf(final MerkleRoute that);

    /**
     * <p>
     * Get the step at a particular index.
     * </p>
     *
     * <p>
     * May be O(n) where n is the length of the route for some implementations.
     * </p>
     *
     * @param index
     * 		the index of the step to fetch. Negative values index from the end of the list, with -1 referencing
     * 		the last step, -2 referencing the second to last step, and so on.
     * @return the requested step
     * @throws IndexOutOfBoundsException
     * 		if the requested index is invalid
     */
    int getStep(final int index);
}
