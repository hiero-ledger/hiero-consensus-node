// SPDX-License-Identifier: Apache-2.0
package com.swirlds.common.merkle.route.internal;

import java.util.Iterator;
import java.util.NoSuchElementException;

/**
 * Iterates over steps in a route encoded using {@link UncompressedMerkleRoute}.
 */
public class UncompressedMerkleRouteIterator implements Iterator<Integer> {

    private final int[] routeData;
    private int nextIndex;

    public UncompressedMerkleRouteIterator(final int[] routeData) {
        this.routeData = routeData;
        nextIndex = 0;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean hasNext() {
        return nextIndex < routeData.length;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Integer next() {
        if (nextIndex > routeData.length) {
            throw new NoSuchElementException();
        }
        final int step = routeData[nextIndex];
        nextIndex++;
        return step;
    }
}
