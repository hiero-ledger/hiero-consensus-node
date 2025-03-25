// SPDX-License-Identifier: Apache-2.0
package com.swirlds.common.merkle.route.internal;

import com.swirlds.common.merkle.exceptions.MerkleRouteException;
import com.swirlds.common.merkle.route.MerkleRoute;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;
import org.hiero.consensus.model.io.streams.SerializableDataInputStream;
import org.hiero.consensus.model.io.streams.SerializableDataOutputStream;

/**
 * A collection of methods for manipulating binary merkle routes.
 *
 * Data format:
 *
 * Routes are encoded as an array of integers.
 *
 * - If an integer in the array is non-zero positive then it represents
 * a step in the route that is greater or equal to 2.
 * - If an integer in the array is negative then it contains one or
 * more binary steps (i.e. 0 or 1).
 * - If an integer in the array is 0 then it contains no data and is not permitted in a route.
 *
 * The binary format of a negative integer is as follows:
 *
 * 1XXXXXX...10000
 * | |       |  |
 * | |       |  |-- 0 or more 0s (does not encode any steps)
 * | |       |
 * | |       |-- A single 1 (does not encode a step)
 * | |
 * | |-- A sequence of 0s or 1s representing binary steps
 * |
 * |-- A leading 1 (does not encode a step)
 */
public class BinaryMerkleRoute extends AbstractMerkleRoute {

    private static final long CLASS_ID = 0xa424ff16af1380feL;

    private static final class ClassVersion {
        public static final int ORIGINAL = 1;
    }

    /**
     * The number of binary steps that can be stored in each integer.
     */
    private static final int CAPACITY_PER_INT = Integer.SIZE - 2;

    // all 0s with a 1 in the lowest order bit: 000...0001
    private static final int LOWEST_ORDER_BIT_MASK = 1;

    // all 1s: 111...111
    private static final int ALL_ONES_MASK = -1;

    // a 1 in the highest order bit followed by 0s: 1000...000
    private static final int NEW_BINARY_DATA_INT = 1 << (Integer.SIZE - 1);

    private static final int[] emptyData = new int[0];

    private int[] data;

    public BinaryMerkleRoute() {
        data = emptyData;
    }

    /**
     * Copy a route and extend it with a step.
     *
     * @param baseRoute
     * 		the route to copy
     * @param step
     * 		the new step
     */
    private BinaryMerkleRoute(final BinaryMerkleRoute baseRoute, final int step) {
        data = copyAndExpandIfNeeded(baseRoute.data, step);
        addStepToRouteData(data, data.length - 1, step);
    }

    /**
     * Copy a route and extend it with steps.
     *
     * @param baseRoute
     * 		the route to copy
     * @param steps
     * 		the new steps
     */
    private BinaryMerkleRoute(final BinaryMerkleRoute baseRoute, final List<Integer> steps) {
        final int expansion = getExpansionRequiredToHoldSteps(baseRoute.data, steps);
        data = Arrays.copyOf(baseRoute.data, baseRoute.data.length + expansion);
        final int index = baseRoute.data.length == 0 ? 0 : (baseRoute.data.length - 1);
        addStepsToRouteData(data, index, steps.iterator());
    }

    /**
     * Copy a route and extend it with steps.
     *
     * @param baseRoute
     * 		the route to copy
     * @param steps
     * 		the new steps
     */
    private BinaryMerkleRoute(final BinaryMerkleRoute baseRoute, final int[] steps) {
        final int expansion = getExpansionRequiredToHoldSteps(baseRoute.data, steps);
        data = Arrays.copyOf(baseRoute.data, baseRoute.data.length + expansion);
        final Iterable<Integer> it = () -> Arrays.stream(steps).iterator();
        final int index = baseRoute.data.length == 0 ? 0 : (baseRoute.data.length - 1);
        addStepsToRouteData(data, index, it.iterator());
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public int size() {
        int length = 0;

        for (final int datum : data) {
            if (datum > 0) {
                length++;
            } else if (datum < 0) {
                length += getNumberOfStepsInInt(datum);
            }
        }

        return length;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean isEmpty() {
        return data.length == 0;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Iterator<Integer> iterator() {
        return new BinaryMerkleRouteIterator(this.data);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public MerkleRoute extendRoute(final int step) {
        return new BinaryMerkleRoute(this, step);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public MerkleRoute extendRoute(final List<Integer> steps) {
        if (steps == null || steps.isEmpty()) {
            return this;
        }
        return new BinaryMerkleRoute(this, steps);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public MerkleRoute extendRoute(final int... steps) {
        if (steps == null || steps.length == 0) {
            return this;
        }
        return new BinaryMerkleRoute(this, steps);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public MerkleRoute getParent() {
        if (data.length == 0) {
            throw new NoSuchElementException("Cannot get parent of root");
        }

        final List<Integer> steps = new ArrayList<>();
        iterator().forEachRemaining(steps::add);
        steps.remove(steps.size() - 1);

        return new BinaryMerkleRoute().extendRoute(steps);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public int getStep(final int index) {
        final int normalizedIndex = index >= 0 ? index : (size() + index);
        if (normalizedIndex < 0) {
            throw new IndexOutOfBoundsException("index " + index + " is out of bounds");
        }

        final Iterator<Integer> iterator = iterator();

        int ret = -1;
        for (int i = 0; i <= normalizedIndex; i++) {
            if (!iterator.hasNext()) {
                throw new IndexOutOfBoundsException("index " + index + " is out of bounds");
            }
            ret = iterator.next();
        }

        return ret;
    }

    /**
     * Get the bit at a particular index within an integer.
     *
     * @param data
     * 		an int value
     * @param index
     * 		the index of the bit in concern
     * @return the bit at the given index within the given integer
     */
    public static int getBitAtIndex(final int data, final int index) {
        if (index < 0 || index >= Integer.SIZE) {
            throw new IndexOutOfBoundsException();
        }

        final int mask = LOWEST_ORDER_BIT_MASK << (Integer.SIZE - index - 1);

        return (mask & data) == 0 ? 0 : 1;
    }

    /**
     * Get a new int by setting the bit at a particular index within an integer to be a particular value
     *
     * @param data
     * 		an int value
     * @param index
     * 		the index of the bit in concern
     * @param value
     * 		new value of the bit
     * @return a new int value after setting
     */
    private static int setBitAtIndex(final int data, final int index, final int value) {
        if (index < 0 || index >= Integer.SIZE) {
            throw new IndexOutOfBoundsException();
        }

        int mask = LOWEST_ORDER_BIT_MASK << (Integer.SIZE - index - 1);

        if (value == 0) {
            mask = ALL_ONES_MASK ^ mask;
            return data & mask;
        } else if (value == 1) {
            return data | mask;
        } else {
            throw new IllegalArgumentException("A bit may only hold a 0 or a 1");
        }
    }

    /**
     * Deep copy route data, expanding the copy if necessary to add the specified step.
     * More efficient than first copying and then expanding.
     *
     * @param routeData
     * 		the route data to be deep copied
     * @param step
     * 		the binary step that the resulting route must have the capacity to store
     * @return a deep copy of the route with the capacity for the step
     */
    private static int[] copyAndExpandIfNeeded(final int[] routeData, final int step) {
        if (hasCapacityForStep(routeData, step)) {
            return Arrays.copyOf(routeData, routeData.length);
        } else {
            return Arrays.copyOf(routeData, routeData.length + 1);
        }
    }

    /**
     * Count the number of integers that need to be added to the array in order to hold all of the steps.
     *
     * @param routeData
     * 		the route that will need to hold the steps
     * @param steps
     * 		the steps that will be added
     * @return the number of integers that need to be added to the array to hold the required list of steps
     */
    private static int getExpansionRequiredToHoldSteps(final int[] routeData, final int[] steps) {
        final Iterable<Integer> it = () -> Arrays.stream(steps).iterator();
        return getExpansionRequiredToHoldSteps(routeData, it.iterator());
    }

    /**
     * Count the number of integers that need to be added to the array in order to hold all of the steps.
     *
     * @param routeData
     * 		the route that will need to hold the steps
     * @param steps
     * 		the steps that will be added
     * @return the number of integers that need to be added to the array to hold the required list of steps
     */
    private static int getExpansionRequiredToHoldSteps(final int[] routeData, final List<Integer> steps) {
        return getExpansionRequiredToHoldSteps(routeData, steps.iterator());
    }

    /**
     * Count the number of integers that need to be added to the array in order to hold all of the steps.
     *
     * @param routeData
     * 		the route that will need to hold the steps
     * @param steps
     * 		an iterator for the steps that will be added
     * @return the number of integers that need to be added to the array to hold the required list of steps
     */
    private static int getExpansionRequiredToHoldSteps(final int[] routeData, final Iterator<Integer> steps) {
        int expansionCount = 0;
        int availableBinaryCapacity;

        if (routeData.length == 0) {
            availableBinaryCapacity = 0;
        } else {
            availableBinaryCapacity = getRemainingCapacityInInt(routeData[routeData.length - 1]);
        }

        while (steps.hasNext()) {
            final int step = steps.next();

            if (availableBinaryCapacity == 0 || (step > 1 && availableBinaryCapacity < CAPACITY_PER_INT)) {
                expansionCount++;
                availableBinaryCapacity = CAPACITY_PER_INT;
            }

            if (step <= 1) {
                availableBinaryCapacity--;
            } else {
                availableBinaryCapacity = 0;
            }
        }

        return expansionCount;
    }

    /**
     * Determine the remaining capacity for steps in an integer.
     *
     * @param intData
     * 		an integer
     * @return remaining capacity for steps in an integer
     */
    private static int getRemainingCapacityInInt(int intData) {
        if (intData == 0 || intData == NEW_BINARY_DATA_INT) {
            // The first bit is reserved for the negative sign, the last bit is used to show the number of steps
            return CAPACITY_PER_INT;
        }
        int capacity = 0;
        final int mask = LOWEST_ORDER_BIT_MASK;
        while ((intData & mask) == 0) {
            capacity++;
            intData = intData >> 1;
        }
        return capacity;
    }

    /**
     * Check if route data has capacity for a given step. More efficient than {@link #getRemainingCapacityInInt(int)}.
     *
     * @param routeData
     * 		the route data to be checked
     * @param step
     * 		the step to add to the route.
     */
    private static boolean hasCapacityForStep(final int[] routeData, final int step) {
        if (routeData.length == 0) {
            return false;
        }

        int lastIntInData = routeData[routeData.length - 1];

        if (step > 1) {
            return lastIntInData == 0;
        }

        if (lastIntInData > 0) {
            return false;
        } else {
            return (lastIntInData & LOWEST_ORDER_BIT_MASK) == 0;
        }
    }

    /**
     * Add a step which is a 0 or a 1. Assumes that the binary route has capacity for the step.
     *
     * @param routeData
     * 		the route data to add a step to. Will be modified by this function.
     * @param index
     * 		the index within the route array where the step will be placed
     * @param step
     * 		the binary step to add to the route data.
     */
    private static void addBinaryStepToRouteData(final int[] routeData, final int index, final int step) {
        int datum = routeData[index];

        // Ensure left flag is properly set
        if (datum == 0) {
            datum = NEW_BINARY_DATA_INT;
        }

        final int bitIndex = CAPACITY_PER_INT - getRemainingCapacityInInt(datum) + 1;

        // Set the bit
        datum = setBitAtIndex(datum, bitIndex, step);

        // Set the right-most flag bit
        datum = setBitAtIndex(datum, bitIndex + 1, 1);

        routeData[index] = datum;
    }

    /**
     * Add a step which is greater than 1. Assumes that the binary route has capacity for the step.
     *
     * @param routeData
     * 		the route data to add a step to. Will be modified by this function.
     * @param index
     * 		the index within the route array where the step will be placed
     * @param step
     * 		the n-ary step to add to the route data.
     */
    private static void addNaryStepToRouteData(final int[] routeData, final int index, final int step) {
        routeData[index] = step;
    }

    /**
     * Add a step to a binary route. Assumes that the binary route has capacity for the step.
     *
     * @param route
     * 		the route to add a step to. Will be modified by this function.
     * @param index
     * 		the index within the route array where the step will be placed
     * @param step
     * 		the step to add to the route.
     */
    private static void addStepToRouteData(final int[] route, final int index, final int step) {
        if (step < 0) {
            throw new MerkleRouteException("Binary route steps can not be negative.");
        } else if (step < 2) {
            addBinaryStepToRouteData(route, index, step);
        } else {
            addNaryStepToRouteData(route, index, step);
        }
    }

    /**
     * Add steps to a binary route. Assumes that the binary route has capacity for the steps.
     *
     * @param route
     * 		the route to add a step to. Will be modified by this function.
     * @param initialIndex
     * 		the index within the route array where the first step will be placed
     * @param steps
     * 		an iterator for the steps that will be added
     */
    private static void addStepsToRouteData(final int[] route, final int initialIndex, final Iterator<Integer> steps) {
        int availableBinaryCapacity = getRemainingCapacityInInt(route[initialIndex]);
        int index = initialIndex;

        while (steps.hasNext()) {
            final int step = steps.next();

            if (availableBinaryCapacity == 0 || (step > 1 && availableBinaryCapacity < CAPACITY_PER_INT)) {
                index++;
                availableBinaryCapacity = CAPACITY_PER_INT;
            }

            if (step <= 1) {
                addBinaryStepToRouteData(route, index, step);
                availableBinaryCapacity--;
            } else {
                addNaryStepToRouteData(route, index, step);
                availableBinaryCapacity = 0;
            }
        }
    }

    /**
     * Calculate the number of steps contained within an integer.
     *
     * @param data
     * 		an integer
     * @return the number of steps contained within the integer
     */
    public static int getNumberOfStepsInInt(final int data) {
        return CAPACITY_PER_INT - getRemainingCapacityInInt(data);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean equals(final Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        final BinaryMerkleRoute that = (BinaryMerkleRoute) o;
        return Arrays.equals(data, that.data);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public int hashCode() {
        return Arrays.hashCode(data);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public long getClassId() {
        return CLASS_ID;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void serialize(final SerializableDataOutputStream out) throws IOException {
        out.writeIntArray(data);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void deserialize(final SerializableDataInputStream in, final int version) throws IOException {
        data = in.readIntArray(MerkleRoute.MAX_ROUTE_LENGTH);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public int getVersion() {
        return ClassVersion.ORIGINAL;
    }
}
