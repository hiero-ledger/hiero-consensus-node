// SPDX-License-Identifier: Apache-2.0
package com.swirlds.common.merkle.route;

import com.swirlds.common.merkle.MerkleNode;
import java.util.Iterator;
import java.util.LinkedList;

/**
 * Iterate over a route in a merkle tree in reverse order (from leaf to root).
 */
public class ReverseMerkleRouteIterator implements Iterator<MerkleNode> {

    private final Iterator<MerkleNode> iterator;

    public ReverseMerkleRouteIterator(final MerkleNode root, final MerkleRoute routeData) {
        LinkedList<MerkleNode> nodes = new LinkedList<>();

        MerkleRouteIterator it = new MerkleRouteIterator(root, routeData);
        while (it.hasNext()) {
            nodes.addFirst(it.next());
        }

        iterator = nodes.iterator();
    }

    @Override
    public boolean hasNext() {
        return iterator.hasNext();
    }

    @Override
    public MerkleNode next() {
        return iterator.next();
    }
}
