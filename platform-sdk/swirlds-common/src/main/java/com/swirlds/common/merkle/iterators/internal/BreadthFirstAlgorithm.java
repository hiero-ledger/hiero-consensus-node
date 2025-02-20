// SPDX-License-Identifier: Apache-2.0
package com.swirlds.common.merkle.iterators.internal;

import com.swirlds.common.merkle.MerkleInternal;
import com.swirlds.common.merkle.MerkleNode;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.LinkedList;
import java.util.Objects;
import java.util.Queue;
import java.util.function.ObjIntConsumer;

/**
 * Iteration algorithm for
 * {@link com.swirlds.common.merkle.iterators.MerkleIterationOrder#BREADTH_FIRST BREADTH_FIRST}.
 */
public class BreadthFirstAlgorithm implements MerkleIterationAlgorithm {

    private final Queue<MerkleNode> queue = new LinkedList<>();

    /**
     * {@inheritDoc}
     */
    @Override
    public void push(@NonNull final MerkleNode node) {
        Objects.requireNonNull(node);
        queue.add(node);
    }

    /**
     * {@inheritDoc}
     */
    @NonNull
    @Override
    public MerkleNode pop() {
        return queue.remove();
    }

    /**
     * {@inheritDoc}
     */
    @NonNull
    @Override
    public MerkleNode peek() {
        return queue.peek();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public int size() {
        return queue.size();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void pushChildren(
            @NonNull final MerkleInternal parent, @NonNull final ObjIntConsumer<MerkleInternal> pushNode) {
        for (int childIndex = 0; childIndex < parent.getNumberOfChildren(); childIndex++) {
            pushNode.accept(parent, childIndex);
        }
    }
}
