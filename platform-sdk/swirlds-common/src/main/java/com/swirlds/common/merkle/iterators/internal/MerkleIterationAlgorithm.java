// SPDX-License-Identifier: Apache-2.0
package com.swirlds.common.merkle.iterators.internal;

import com.swirlds.common.merkle.MerkleInternal;
import com.swirlds.common.merkle.MerkleNode;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.function.ObjIntConsumer;

/**
 * Defines algorithm specific behavior for a merkle iterator.
 */
public interface MerkleIterationAlgorithm {

    /**
     * Push a node into the stack/queue.
     */
    void push(@NonNull MerkleNode node);

    /**
     * Remove and return the next item in the stack/queue.
     */
    @NonNull
    MerkleNode pop();

    /**
     * Return the next item in the stack/queue but do not remove it.
     */
    @NonNull
    MerkleNode peek();

    /**
     * Get the number of elements in the stack/queue.
     */
    int size();

    /**
     * Call pushNode on all of a merkle node's children.
     *
     * @param parent
     * 		the parent with children to push
     * @param pushNode
     * 		a method to be used to push children. First argument is the parent, second argument is the child index.
     */
    void pushChildren(@NonNull final MerkleInternal parent, @NonNull final ObjIntConsumer<MerkleInternal> pushNode);
}
