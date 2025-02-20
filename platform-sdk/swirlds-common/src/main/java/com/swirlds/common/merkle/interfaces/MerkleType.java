// SPDX-License-Identifier: Apache-2.0
package com.swirlds.common.merkle.interfaces;

import com.swirlds.common.merkle.MerkleInternal;
import com.swirlds.common.merkle.MerkleLeaf;
import com.swirlds.common.merkle.MerkleNode;

/**
 * Describes the type of a merkle node.
 */
public interface MerkleType {

    /**
     * Check if this node is a leaf node. As fast or faster than using instanceof.
     *
     * @return true if this is a leaf node in a merkle tree.
     */
    boolean isLeaf();

    /**
     * Check if this node is a {@link MerkleInternal} node.
     *
     * @return true if this is an internal node
     */
    default boolean isInternal() {
        return !isLeaf();
    }

    /**
     * Blindly cast this merkle node into a leaf node, will fail if node is not actually a leaf node.
     */
    default MerkleLeaf asLeaf() {
        return cast();
    }

    /**
     * Blindly cast this merkle node into an internal node, will fail if node is not actually an internal node.
     */
    default MerkleInternal asInternal() {
        return cast();
    }

    /**
     * Blindly cast this merkle node into the given type, will fail if node is not actually that type.
     *
     * @param <T>
     * 		this node will be cast into this type
     */
    @SuppressWarnings("unchecked")
    default <T extends MerkleNode> T cast() {
        return (T) this;
    }
}
