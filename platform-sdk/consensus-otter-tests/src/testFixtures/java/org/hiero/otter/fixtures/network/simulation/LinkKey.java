// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.network.simulation;

import edu.umd.cs.findbugs.annotations.NonNull;
import org.hiero.consensus.model.node.NodeId;
import org.hiero.otter.fixtures.internal.network.ConnectionKey;

/**
 * Identifies the link between two nodes as a whole, covering the connection in each direction. A link has no direction:
 * {@code new LinkKey(a, b)} equals {@code new LinkKey(b, a)}.
 *
 * <p>The two nodes are kept in a fixed order internally so that equality, hashing and sorting do not depend on the order
 * they were given in. That order carries no meaning, so callers should not read anything into which node is
 * {@link #first()} and which is {@link #second()}.
 *
 * @param first  one node of the link
 * @param second the other node of the link
 */
record LinkKey(@NonNull NodeId first, @NonNull NodeId second) implements Comparable<LinkKey> {

    LinkKey {
        if (first.compareTo(second) > 0) {
            final NodeId swapped = first;
            first = second;
            second = swapped;
        }
    }

    /**
     * Creates the key of the link a connection is one direction of.
     *
     * @param connection the connection between two nodes
     * @return the key of the link between those nodes
     */
    @NonNull
    static LinkKey of(@NonNull final ConnectionKey connection) {
        return new LinkKey(connection.sender(), connection.receiver());
    }

    /**
     * @return the connection from {@link #first()} to {@link #second()}
     */
    @NonNull
    ConnectionKey firstToSecond() {
        return new ConnectionKey(first, second);
    }

    /**
     * @return the connection from {@link #second()} to {@link #first()}
     */
    @NonNull
    ConnectionKey secondToFirst() {
        return new ConnectionKey(second, first);
    }

    @Override
    public int compareTo(@NonNull final LinkKey other) {
        final int byFirst = first.compareTo(other.first);
        return byFirst != 0 ? byFirst : second.compareTo(other.second);
    }
}
