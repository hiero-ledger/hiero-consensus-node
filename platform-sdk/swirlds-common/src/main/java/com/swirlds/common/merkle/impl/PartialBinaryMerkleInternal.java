// SPDX-License-Identifier: Apache-2.0
package com.swirlds.common.merkle.impl;

import com.swirlds.common.merkle.MerkleInternal;
import com.swirlds.common.merkle.MerkleNode;
import com.swirlds.common.merkle.exceptions.IllegalChildIndexException;
import com.swirlds.common.merkle.impl.internal.AbstractMerkleInternal;

/**
 * This class implements boilerplate functionality for a binary {@link MerkleInternal}
 * (i.e. an internal node with 2 or fewer children). Classes that implement {@link MerkleInternal}
 * are not required to extend a class such as this or {@link PartialNaryMerkleInternal},
 * but absent a reason it is recommended to do so in order to avoid re-implementation of this code.
 */
public non-sealed class PartialBinaryMerkleInternal extends AbstractMerkleInternal implements PartialMerkleInternal {

    private MerkleNode left;
    private MerkleNode right;

    private static final int MIN_BINARY_CHILD_COUNT = 0;
    private static final int BINARY_CHILD_COUNT = 2;

    private static class ChildIndices {
        public static final int LEFT = 0;
        public static final int RIGHT = 1;
    }

    public PartialBinaryMerkleInternal() {}

    /**
     * Copy constructor.
     */
    @SuppressWarnings("CopyConstructorMissesField")
    protected PartialBinaryMerkleInternal(final PartialBinaryMerkleInternal other) {
        super(other);
    }

    /**
     * {@inheritDoc}
     *
     * In the binary case this is always BINARY_CHILD_COUNT (2).
     */
    @Override
    public int getNumberOfChildren() {
        // binary tree, we always have two children, even if null
        return BINARY_CHILD_COUNT;
    }

    /**
     * {@inheritDoc}
     *
     * In the binary case this is always BINARY_CHILD_COUNT (2).
     */
    @Override
    public int getMinimumChildCount() {
        return MIN_BINARY_CHILD_COUNT;
    }

    /**
     * {@inheritDoc}
     *
     * In the binary case this is always BINARY_CHILD_COUNT (2).
     *
     * @return always BINARY_CHILD_COUNT (2), even if the children are null
     */
    @Override
    public int getMaximumChildCount() {
        return BINARY_CHILD_COUNT;
    }

    /**
     * {@inheritDoc}
     *
     * If the index is either LEFT or RIGHT, then return the correct child
     * otherwise return an IllegalChildIndexException.
     *
     * @param index
     * 		The position to look for the child.
     * @param <T>
     * 		the type of the child
     * @return the child node is returned
     */
    @SuppressWarnings("unchecked")
    @Override
    public <T extends MerkleNode> T getChild(final int index) {
        if (index == ChildIndices.LEFT) {
            return (T) left;
        } else if (index == ChildIndices.RIGHT) {
            return (T) right;
        } else {
            throw new IllegalChildIndexException(ChildIndices.LEFT, ChildIndices.RIGHT, index);
        }
    }

    /**
     * {@inheritDoc}
     *
     * Select either the {@link ChildIndices#LEFT} (0) or {@link ChildIndices#RIGHT} (1) using an index number.
     * This will throw an error if a different value is used.*
     *
     * @param index
     * 		which child position is going to be updated
     * @param child
     */
    @Override
    protected void setChildInternal(final int index, final MerkleNode child) {
        if (index == ChildIndices.LEFT) {
            left = child;
        } else if (index == ChildIndices.RIGHT) {
            right = child;
        } else { // bad index
            throw new IllegalChildIndexException(ChildIndices.LEFT, ChildIndices.RIGHT, index);
        }
    }

    /**
     * Set the left child.
     *
     * @param left
     * 		a merkle node that will become this node's left child
     * @param <T>
     * 		the type of the child
     */
    public <T extends MerkleNode> void setLeft(final T left) {
        setChild(ChildIndices.LEFT, left);
    }

    /**
     * Get the left child.
     *
     * @param <T>
     * 		the type of the left child
     * @return the merkle node in the left child position, or null if no such node is present
     */
    @SuppressWarnings("unchecked")
    public <T extends MerkleNode> T getLeft() {
        return (T) left;
    }

    /**
     * Set the right child.
     *
     * @param right
     * 		a merkle node that will become this node's right child
     * @param <T>
     * 		the type of the child
     */
    public <T extends MerkleNode> void setRight(final T right) {
        setChild(ChildIndices.RIGHT, right);
    }

    @SuppressWarnings("unchecked")
    public <T extends MerkleNode> T getRight() {
        return (T) right;
    }

    /**
     * {@inheritDoc}
     *
     * In the N-Ary case, this will increase the number of children to the value provided.  In this binary
     * case, there are always two children, so this is a NOP.
     *
     * @param index
     * 		unused
     */
    @Override
    protected void allocateSpaceForChild(final int index) {
        // in the binary case, these children are members of the object and don't need to be allocated or resized
    }

    /**
     * {@inheritDoc}
     *
     * In the N-Ary case, this verifies that the index is between 0 and the number of actual children
     * (including null children) of the node.  However, in the binary case, there are always two:
     * ChildIndices.LEFT (0) and ChildIndices.RIGHT (1), and the subsequent call is to getChild(index), above,
     * which also tests the legality of the index.  As a result, there is no need to perform an extra
     * bounds test here.  This is effectively a NOP.
     *
     * @param index
     */
    @Override
    protected void checkChildIndexIsValid(final int index) {
        // the index is actually being tested in getChild(),
        // which will throw if not ChildIndices.LEFT or ChildIndices.RIGHT
    }
}
