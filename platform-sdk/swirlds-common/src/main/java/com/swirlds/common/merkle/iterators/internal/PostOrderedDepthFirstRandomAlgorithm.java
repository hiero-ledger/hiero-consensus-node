// SPDX-License-Identifier: Apache-2.0
package com.swirlds.common.merkle.iterators.internal;

import com.swirlds.common.merkle.MerkleInternal;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.function.ObjIntConsumer;

/**
 * Iteration algorithm for
 * {@link com.swirlds.common.merkle.iterators.MerkleIterationOrder#POST_ORDERED_DEPTH_FIRST_RANDOM
 * POST_ORDERED_DEPTH_FIRST_RANDOM}.
 */
public class PostOrderedDepthFirstRandomAlgorithm extends PostOrderedDepthFirstAlgorithm {

    private final Random random = new Random();

    /**
     * Add children to the stack in a random order. {@inheritDoc}
     */
    @Override
    public void pushChildren(
            @NonNull final MerkleInternal parent, @NonNull final ObjIntConsumer<MerkleInternal> pushNode) {
        final int childCount = parent.getNumberOfChildren();
        final List<Integer> iterationOrder = new ArrayList<>(childCount);
        for (int childIndex = 0; childIndex < childCount; childIndex++) {
            iterationOrder.add(childIndex);
        }

        Collections.shuffle(iterationOrder, random);
        for (final int childIndex : iterationOrder) {
            pushNode.accept(parent, childIndex);
        }
    }
}
