// SPDX-License-Identifier: Apache-2.0
package com.swirlds.common.merkle.iterators.internal;

import com.swirlds.common.merkle.MerkleInternal;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.function.ObjIntConsumer;

/**
 * Iteration algorithm for
 * {@link com.swirlds.common.merkle.iterators.MerkleIterationOrder#PRE_ORDERED_DEPTH_FIRST PRE_ORDERED_DEPTH_FIRST}.
 */
public class PreOrderedDepthFirstAlgorithm extends PostOrderedDepthFirstAlgorithm {

    /**
     * {@inheritDoc}
     */
    @Override
    public void pushChildren(
            @NonNull final MerkleInternal parent, @NonNull final ObjIntConsumer<MerkleInternal> pushNode) {
        // Swap the order of the parent and child to switch to pre-ordered
        pop();
        super.pushChildren(parent, pushNode);
        push(parent);
    }
}
