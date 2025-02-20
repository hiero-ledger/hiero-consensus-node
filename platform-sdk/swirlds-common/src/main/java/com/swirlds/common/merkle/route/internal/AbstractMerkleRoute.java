// SPDX-License-Identifier: Apache-2.0
package com.swirlds.common.merkle.route.internal;

import com.swirlds.common.merkle.route.MerkleRoute;
import java.util.Iterator;

public abstract class AbstractMerkleRoute implements MerkleRoute {

    /**
     * {@inheritDoc}
     */
    @Override
    public final int compareTo(final MerkleRoute that) {
        final Iterator<Integer> iteratorA = this.iterator();
        final Iterator<Integer> iteratorB = that.iterator();

        while (iteratorA.hasNext() && iteratorB.hasNext()) {
            final int a = iteratorA.next();
            final int b = iteratorB.next();
            if (a < b) {
                return -1;
            } else if (b < a) {
                return 1;
            }
        }

        // No differences found
        return 0;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public final boolean isAncestorOf(final MerkleRoute that) {
        Iterator<Integer> iteratorA = this.iterator();
        Iterator<Integer> iteratorB = that.iterator();

        while (iteratorA.hasNext() && iteratorB.hasNext()) {
            final int a = iteratorA.next();
            final int b = iteratorB.next();

            if (a != b) {
                return false;
            }
        }

        return !iteratorA.hasNext();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public final boolean isDescendantOf(final MerkleRoute that) {
        return that.isAncestorOf(this);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public final String toString() {
        final Iterator<Integer> iterator = iterator();

        StringBuilder sb = new StringBuilder();
        sb.append("[");

        iterator.forEachRemaining((Integer step) -> {
            sb.append(step);
            if (iterator.hasNext()) {
                sb.append(" -> ");
            }
        });

        sb.append("]");
        return sb.toString();
    }
}
