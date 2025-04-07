// SPDX-License-Identifier: Apache-2.0
package com.swirlds.common.merkle.route.internal;

import com.swirlds.common.merkle.route.MerkleRoute;
import java.io.IOException;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;
import org.hiero.consensus.model.io.streams.SerializableDataInputStream;
import org.hiero.consensus.model.io.streams.SerializableDataOutputStream;

/**
 * An encoding algorithm for merkle routes. Stores routes in uncompressed format.
 *
 * Requires more memory but is very fast.
 */
public class UncompressedMerkleRoute extends AbstractMerkleRoute {

    private static final long CLASS_ID = 0x231d418aaf8ee967L;

    private static final class ClassVersion {
        public static final int ORIGINAL = 1;
    }

    private static final int[] emptyData = new int[0];

    private int[] data;

    public UncompressedMerkleRoute() {
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
    private UncompressedMerkleRoute(final UncompressedMerkleRoute baseRoute, final int step) {
        data = Arrays.copyOf(baseRoute.data, baseRoute.data.length + 1);
        data[baseRoute.data.length] = step;
    }

    /**
     * Copy a route and extend it with steps.
     *
     * @param baseRoute
     * 		the route to copy
     * @param steps
     * 		the new steps
     */
    private UncompressedMerkleRoute(final UncompressedMerkleRoute baseRoute, final List<Integer> steps) {
        data = new int[baseRoute.data.length + steps.size()];
        System.arraycopy(baseRoute.data, 0, data, 0, baseRoute.data.length);

        int index = baseRoute.data.length;
        for (final Integer step : steps) {
            if (step == null) {
                throw new NullPointerException("null steps are not allowed");
            }
            data[index] = step;
            index++;
        }
    }

    /**
     * Copy a route and extend it with steps.
     *
     * @param baseRoute
     * 		the route to copy
     * @param steps
     * 		the new steps
     */
    private UncompressedMerkleRoute(final UncompressedMerkleRoute baseRoute, final int[] steps) {
        data = new int[baseRoute.data.length + steps.length];
        System.arraycopy(baseRoute.data, 0, data, 0, baseRoute.data.length);

        int index = baseRoute.data.length;
        for (final int step : steps) {
            data[index] = step;
            index++;
        }
    }

    /**
     * Copy the route but pop off the last element.
     *
     * @param child
     * 		the child route (the new route is the "parent" of the child)
     */
    private UncompressedMerkleRoute(final UncompressedMerkleRoute child) {
        if (child.data.length == 0) {
            throw new NoSuchElementException("Cannot get parent of root");
        }

        data = Arrays.copyOf(child.data, child.data.length - 1);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Iterator<Integer> iterator() {
        return new UncompressedMerkleRouteIterator(data);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public int size() {
        return data.length;
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
    public MerkleRoute extendRoute(final int step) {
        return new UncompressedMerkleRoute(this, step);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public MerkleRoute extendRoute(final List<Integer> steps) {
        if (steps == null || steps.isEmpty()) {
            return this;
        }
        return new UncompressedMerkleRoute(this, steps);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public MerkleRoute extendRoute(final int... steps) {
        if (steps == null || steps.length == 0) {
            return this;
        }
        return new UncompressedMerkleRoute(this, steps);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public MerkleRoute getParent() {
        return new UncompressedMerkleRoute(this);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public int getStep(final int index) {
        final int normalizedIndex = index >= 0 ? index : (data.length + index);
        return data[normalizedIndex];
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
        final UncompressedMerkleRoute that = (UncompressedMerkleRoute) o;
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
