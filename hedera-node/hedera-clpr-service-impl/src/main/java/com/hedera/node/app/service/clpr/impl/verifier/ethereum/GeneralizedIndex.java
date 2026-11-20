// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.clpr.impl.verifier.ethereum;

/**
 * An SSZ generalized index: the position of a node in a binary Merkle tree, numbered from the root as 1, with the
 * children of node {@code n} at {@code 2n} and {@code 2n + 1}. The consensus specs give branch positions in this form,
 * so constants keep the spec value and derive the depth and the leaf index from it.
 *
 * @param gindex the generalized index; at least 1 (the root)
 */
record GeneralizedIndex(int gindex) {

    GeneralizedIndex {
        if (gindex < 1) {
            throw new IllegalArgumentException("generalized index must be at least 1, got " + gindex);
        }
    }

    /** The depth of the node below the root, i.e. the number of siblings in its branch: {@code floor(log2(gindex))}. */
    int depth() {
        return Integer.SIZE - 1 - Integer.numberOfLeadingZeros(gindex);
    }

    /** The node's position among the {@code 2^depth} nodes at its depth: {@code gindex - 2^depth}. */
    int index() {
        return gindex - (1 << depth());
    }
}
