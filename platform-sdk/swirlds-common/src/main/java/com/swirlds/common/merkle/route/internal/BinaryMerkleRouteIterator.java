// SPDX-License-Identifier: Apache-2.0
package com.swirlds.common.merkle.route.internal;

import static com.swirlds.common.merkle.route.internal.BinaryMerkleRoute.getBitAtIndex;
import static com.swirlds.common.merkle.route.internal.BinaryMerkleRoute.getNumberOfStepsInInt;

import com.swirlds.common.merkle.exceptions.MerkleRouteException;
import java.util.Iterator;
import java.util.NoSuchElementException;

/**
 * This iterator walks over the steps in a binary merkle route.
 */
public class BinaryMerkleRouteIterator implements Iterator<Integer> {

    /**
     * The route that is being iterated over.
     */
    private int[] routeData;

    /**
     * The index of the integer in the route that will be read next.
     */
    private int index;

    /**
     * The index of the bit within the current integer that will be read next (if next step is binary).
     */
    private int bitIndex;

    /**
     * The total number of steps in the current integer.
     */
    private int stepsInInt;

    /**
     * The integer data that will be read from next.
     */
    private int nextData;

    /**
     * The next value to be returned by the iterator (if not null).
     */
    private Integer next;

    /**
     * Create a new iterator.
     *
     * @param routeData
     * 		a route to iterate
     */
    public BinaryMerkleRouteIterator(int[] routeData) {
        reset(routeData);
    }

    protected BinaryMerkleRouteIterator() {}

    /**
     * Reset the iterator with a new route. Useful for recycling this object.
     *
     * @param route
     * 		The new route to iterate.
     */
    public void reset(int[] route) {
        this.routeData = route;
        index = 0;
        next = null;
        prepareNextInt();
    }

    /**
     * Count the number of steps in the next integer and advance the index.
     */
    private void prepareNextInt() {
        if (routeData == null) {
            return;
        }
        if (routeData.length == 0) {
            return;
        }
        nextData = routeData[index];
        index++;
        if (nextData == 0) {
            throw new MerkleRouteException("Routes should not contain 0s.");
        } else if (nextData > 0) {
            stepsInInt = 1;
        } else {
            stepsInInt = getNumberOfStepsInInt(nextData);
            bitIndex = 0;
        }
    }

    private void findNext() {
        if (next != null) {
            return;
        }
        if (routeData == null) {
            return;
        }

        if (stepsInInt == 0) {
            if (index >= routeData.length) {
                return;
            }
            prepareNextInt();
        }

        if (nextData > 0) {
            next = nextData;
        } else {
            next = getBitAtIndex(nextData, bitIndex + 1);
            bitIndex++;
        }
        stepsInInt--;
    }

    @Override
    public boolean hasNext() {
        findNext();
        return next != null;
    }

    @Override
    public Integer next() {
        findNext();
        if (next == null) {
            throw new NoSuchElementException();
        }
        Integer ret = next;
        next = null;
        return ret;
    }
}
