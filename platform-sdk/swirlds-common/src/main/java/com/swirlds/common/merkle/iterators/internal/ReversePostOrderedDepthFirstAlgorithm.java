// SPDX-License-Identifier: Apache-2.0
package com.swirlds.common.merkle.iterators.internal;

import com.swirlds.common.merkle.MerkleInternal;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.function.ObjIntConsumer;

/**
 * Iteration algorithm for
 * {@link com.swirlds.common.merkle.iterators.MerkleIterationOrder#REVERSE_POST_ORDERED_DEPTH_FIRST
 * REVERSE_POST_ORDERED_DEPTH_FIRST}.
 */
public class ReversePostOrderedDepthFirstAlgorithm extends PostOrderedDepthFirstAlgorithm {

    /**
     * {@inheritDoc}
     */
    @Override
    public void pushChildren(
            @NonNull final MerkleInternal parent, @NonNull final ObjIntConsumer<MerkleInternal> pushNode) {
        final int childCount = parent.getNumberOfChildren();
        for (int childIndex = 0; childIndex < childCount; childIndex++) {
            pushNode.accept(parent, childIndex);
        }
    }
}
