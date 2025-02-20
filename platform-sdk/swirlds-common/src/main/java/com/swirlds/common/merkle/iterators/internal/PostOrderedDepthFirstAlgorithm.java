// SPDX-License-Identifier: Apache-2.0
package com.swirlds.common.merkle.iterators.internal;

import com.swirlds.common.merkle.MerkleInternal;
import com.swirlds.common.merkle.MerkleNode;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.ObjIntConsumer;

/**
 * Iteration algorithm for
 * {@link com.swirlds.common.merkle.iterators.MerkleIterationOrder#POST_ORDERED_DEPTH_FIRST POST_ORDER_DEPTH_FIRST}.
 */
public class PostOrderedDepthFirstAlgorithm implements MerkleIterationAlgorithm {

    /**
     * The initial capacity for the array implementing the stack. It's much better to choose something a bit larger than
     * to pay the penalty of resizing the array list.
     */
    private static final int INITIAL_STACK_CAPACITY = 1024;

    /**
     * This list is used to implement a stack.
     */
    private final List<MerkleNode> stack = new ArrayList<>(INITIAL_STACK_CAPACITY);

    /**
     * {@inheritDoc}
     */
    @Override
    public void push(@NonNull final MerkleNode node) {
        Objects.requireNonNull(node);
        stack.add(node);
    }

    /**
     * {@inheritDoc}
     */
    @NonNull
    @Override
    public MerkleNode pop() {
        return stack.remove(stack.size() - 1);
    }

    /**
     * {@inheritDoc}
     */
    @NonNull
    @Override
    public MerkleNode peek() {
        return stack.get(stack.size() - 1);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public int size() {
        return stack.size();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void pushChildren(
            @NonNull final MerkleInternal parent, @NonNull final ObjIntConsumer<MerkleInternal> pushNode) {
        for (int childIndex = parent.getNumberOfChildren() - 1; childIndex >= 0; childIndex--) {
            pushNode.accept(parent, childIndex);
        }
    }
}
