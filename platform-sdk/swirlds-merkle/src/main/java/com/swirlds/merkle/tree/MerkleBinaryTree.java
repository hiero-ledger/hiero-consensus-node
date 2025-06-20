// SPDX-License-Identifier: Apache-2.0
package com.swirlds.merkle.tree;

import static com.swirlds.common.merkle.copy.MerkleCopy.adoptChildren;
import static com.swirlds.common.merkle.copy.MerkleCopy.copyTreeToLocation;
import static com.swirlds.common.merkle.copy.MerklePathReplacement.getGrandparentInPath;
import static com.swirlds.common.merkle.copy.MerklePathReplacement.getParentInPath;
import static com.swirlds.common.merkle.copy.MerklePathReplacement.replacePath;
import static com.swirlds.common.merkle.utility.MerkleUtils.findChildPositionInParent;

import com.swirlds.common.merkle.MerkleInternal;
import com.swirlds.common.merkle.MerkleNode;
import com.swirlds.common.merkle.exceptions.IllegalChildIndexException;
import com.swirlds.common.merkle.impl.PartialBinaryMerkleInternal;
import com.swirlds.common.merkle.iterators.MerkleIterator;
import com.swirlds.common.merkle.utility.MerkleLong;
import com.swirlds.merkle.tree.internal.BitUtil;
import java.util.Iterator;
import java.util.function.Consumer;
import java.util.function.Predicate;
import org.hiero.base.crypto.SerializableHashable;

/**
 * A merkle data structure that implements a balanced binary tree. Utilizes
 * a copy-on-write algorithm for O(1) fast copies and O(log n) updates.
 *
 * @param <T>
 * 		Type for leaves
 */
public class MerkleBinaryTree<T extends MerkleNode> extends PartialBinaryMerkleInternal
        implements Iterable<T>, MerkleInternal {

    public static final long CLASS_ID = 0x84bec080aa5cfeecL;

    private static final int SIMPLE_TREE_SIZE = 2;

    private static class ClassVersion {
        public static final int ORIGINAL = 1;
    }

    /**
     * Keeps track of the right most leaf in order to replace a deleted
     * leaf with it. Once the replacement is done, a new right most leaf
     * must be assigned.
     */
    private T rightMostLeaf;

    private static class ChildIndices {
        /**
         * Number of leaves in the tree.
         */
        public static final int SIZE = 0;
        /**
         * Root of the tree. For every mutation, a new root is set.
         */
        public static final int ROOT = 1;

        public static final int CHILD_COUNT = 2;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean childHasExpectedType(final int index, final long childClassId) {
        switch (index) {
            case ChildIndices.SIZE:
                return childClassId == MerkleLong.CLASS_ID;
            case ChildIndices.ROOT:
                return childClassId == MerkleTreeInternalNode.CLASS_ID;
            default:
                throw new IllegalChildIndexException(getMinimumChildCount(), getMaximumChildCount(), index);
        }
    }

    private MerkleLong getSize() {
        return getChild(ChildIndices.SIZE);
    }

    public void setSize(MerkleLong size) {
        throwIfImmutable();
        setChild(ChildIndices.SIZE, size);
    }

    public MerkleTreeInternalNode getRoot() {
        return getChild(ChildIndices.ROOT);
    }

    private void setRoot(final MerkleTreeInternalNode root) {
        setChild(ChildIndices.ROOT, root);

        this.invalidateHash();
    }

    /**
     * Creates an instance of {@link MerkleBinaryTree}
     * {@link SerializableHashable}
     */
    public MerkleBinaryTree() {
        this(new MerkleLong(0), new MerkleTreeInternalNode());
    }

    private MerkleBinaryTree(final MerkleLong size, final MerkleTreeInternalNode root) {
        super();
        setSize(size);
        setRoot(root);
    }

    protected MerkleBinaryTree(final MerkleBinaryTree<T> otherMerkleTree) {
        super(otherMerkleTree);
        this.setSize(otherMerkleTree.getSize().copy());

        // Create a new instance of the root, use references to all data below root
        this.setRoot(new MerkleTreeInternalNode());
        adoptChildren(otherMerkleTree.getRoot(), getRoot());

        this.rightMostLeaf = otherMerkleTree.rightMostLeaf;
        otherMerkleTree.rightMostLeaf = null;

        setImmutable(false);
        otherMerkleTree.setImmutable(true);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public MerkleBinaryTree<T> copy() {
        throwIfImmutable();
        throwIfDestroyed();
        return new MerkleBinaryTree<>(this);
    }

    /**
     * Clears the tree in O(1).
     */
    public void clear() {
        throwIfImmutable();
        setSize(new MerkleLong(0));
        this.setRoot(new MerkleTreeInternalNode());
    }

    /**
     * Deletes a leaf from the tree.
     *
     * @param leafToDelete
     * 		Leaf to be removed from the tree
     * @param updateCache
     * 		a function that is used to register changes that may need to be tracked by a cache in the outer scope
     */
    public void delete(final T leafToDelete, final Consumer<T> updateCache) {
        throwIfImmutable();
        if (leafToDelete == null) {
            throw new IllegalArgumentException("Delete does not support null leaves");
        }

        if (getSize().getValue() <= SIMPLE_TREE_SIZE) {
            this.deleteLeafFromBasicTree(leafToDelete, updateCache);
        } else {
            this.deleteLeaf(leafToDelete, updateCache);
        }

        getSize().decrement();
        this.setRightMostLeaf();
    }

    /**
     * Delete the right most leaf.
     *
     * @param updateCache
     * 		a function that is used to register changes that may need to be tracked by a cache in the outer scope
     */
    private void deleteRightMostLeaf(final Consumer<T> updateCache) {

        // Replace a path from the root down to the grandparent of the rightmost leaf.
        // Don't bother replacing parent here, sibling will replace parent.
        final MerkleNode[] path = replacePath(getRoot(), rightMostLeaf.getRoute(), 2);

        final MerkleTreeInternalNode parentOfRightMostLeaf = getParentInPath(path);
        final T siblingOfRightMostLeaf = parentOfRightMostLeaf.getLeft().cast();
        final MerkleTreeInternalNode grandparentOfRightMostLeaf = getGrandparentInPath(path);
        final int indexOfParentInGrandparent =
                findChildPositionInParent(grandparentOfRightMostLeaf, parentOfRightMostLeaf);

        // Move the sibling up to replace its parent
        final T copy =
                copyTreeToLocation(grandparentOfRightMostLeaf, indexOfParentInGrandparent, siblingOfRightMostLeaf);

        updateCache.accept(copy);
    }

    /**
     * Delete the sibling of the right most leaf.
     *
     * @param path
     * 		a path of nodes that have been replaced down to the parent of the right most leaf
     * @param parentOfRightMostLeaf
     * 		the parent of the right most leaf, pre-computed by the caller
     * @param updateCache
     * 		a function that is used to register changes that may need to be tracked by a cache in the outer scope
     */
    private void deleteRightMostLeafSibling(
            final MerkleNode[] path, final MerkleNode parentOfRightMostLeaf, final Consumer<T> updateCache) {

        // Note: path has been replaced by caller

        final MerkleTreeInternalNode grandparentOfRightMostLeaf = getGrandparentInPath(path);

        final int indexOfParentInGrandparent =
                findChildPositionInParent(grandparentOfRightMostLeaf, parentOfRightMostLeaf);

        // Move the right most leaf up to replace its parent.
        final T copy = copyTreeToLocation(grandparentOfRightMostLeaf, indexOfParentInGrandparent, rightMostLeaf);
        updateCache.accept(copy);
    }

    /**
     * Delete a leaf that is neither the right most leaf nor its sibling.
     */
    private void deleteMiddleLeaf(
            final MerkleTreeInternalNode parentOfNodeToDelete, final T leafToDelete, final Consumer<T> updateCache) {

        final int indexOfChildToDelete = findChildPositionInParent(parentOfNodeToDelete, leafToDelete);

        // Move the right most leaf to replace the leaf being deleted
        final T copy = copyTreeToLocation(parentOfNodeToDelete, indexOfChildToDelete, rightMostLeaf);
        updateCache.accept(copy);

        // Move the sibling of the rightmost leaf to replace its parent.
        // Since we already have copied the rightmost leaf, it is safe to simply delete the original tree.
        deleteRightMostLeaf(updateCache);
    }

    /**
     * Delete a leaf that is not the right most leaf.
     */
    private void deleteNonRightMostLeaf(final T leafToDelete, final Consumer<T> updateCache) {

        // Replace a path from the root down to the parent of the leaf to delete.
        final MerkleNode[] path = replacePath(getRoot(), leafToDelete.getRoute(), 1);
        final MerkleTreeInternalNode parentOfNodeToDelete = getParentInPath(path);

        if (parentOfNodeToDelete.getRight() == rightMostLeaf) {
            // We are deleting the sibling of the right most leaf.
            deleteRightMostLeafSibling(path, parentOfNodeToDelete, updateCache);
        } else {
            deleteMiddleLeaf(parentOfNodeToDelete, leafToDelete, updateCache);
        }
    }

    /**
     * Deletes a leaf.
     *
     * @param leafToDelete
     * 		leaf to delete from the tree
     * @param updateCache
     * 		a function that is used to register changes that may need to be tracked by a cache in the outer scope
     */
    private void deleteLeaf(final T leafToDelete, final Consumer<T> updateCache) {
        if (rightMostLeaf == leafToDelete) {
            // Leaf deleted is right most leaf.
            deleteRightMostLeaf(updateCache);
        } else {
            // Leaf deleted is not the right most leaf.
            deleteNonRightMostLeaf(leafToDelete, updateCache);
        }
    }

    /**
     * Deletes a leaf from a tree with 1 or 2 leaves.
     *
     * @param leafToDelete
     * 		Leaf to delete from the tree
     * @param updateCache
     * 		a function that is used to register changes that may need to be tracked by a cache in the outer scope
     */
    private void deleteLeafFromBasicTree(final T leafToDelete, final Consumer<T> updateCache) {

        if (getSize().getValue() < 1) {
            throw new IllegalStateException("The tree is empty. No leaf to delete");
        } else if (getSize().getValue() == 1) {
            getRoot().setLeft(null);
            rightMostLeaf = null;
        } else {
            if (rightMostLeaf.equals(leafToDelete)) {
                // we are deleting the rightmost leaf
                getRoot().setRight(null);
                rightMostLeaf = getRoot().getLeft().cast();
            } else {
                // we are deleting the sibling of the rightmost leaf
                rightMostLeaf = copyTreeToLocation(getRoot(), 0, rightMostLeaf);
                updateCache.accept(rightMostLeaf);
                getRoot().setRight(null);
            }
        }
    }

    /**
     * After a deletion is performed, the right most leaf changed, and must be set again.
     */
    private void setRightMostLeaf() {
        if (getSize().getValue() <= SIMPLE_TREE_SIZE) {
            final MerkleTreeInternalNode root = this.getRoot();
            rightMostLeaf = root.getRight() != null ? root.getRight() : root.getLeft();
        } else {
            final long leftMostOneBit = BitUtil.findLeftMostBit(getSize().getValue() - 1);
            final MerkleTreeInternalNode node =
                    findFirstLeafAtCompleteSubtree(leftMostOneBit, getSize().getValue() - 1, this.getRoot());
            rightMostLeaf = node.getRight().cast();
        }
    }

    /**
     * Replaces an old leaf with a new leaf.
     *
     * @param oldLeaf
     * 		Old leaf to replace
     * @param newLeaf
     * 		New leaf replacing
     */
    public void update(final T oldLeaf, final T newLeaf) {
        throwIfImmutable();

        if (oldLeaf == null || newLeaf == null) {
            throw new IllegalArgumentException("Update does not support null leaves");
        } else if (getSize().getValue() < 1) {
            throw new IllegalStateException("The tree is empty. No leaf to replace");
        } else {
            // Replace a path down to the parent of the old leaf. Don't bother replacing the old leaf itself.
            final MerkleNode[] path = replacePath(getRoot(), oldLeaf.getRoute(), 1);

            final MerkleTreeInternalNode parent = getParentInPath(path);
            final int indexOfChildInParent = findChildPositionInParent(parent, oldLeaf);

            parent.setChild(indexOfChildInParent, newLeaf, oldLeaf.getRoute(), false);

            if (rightMostLeaf == oldLeaf) {
                rightMostLeaf = newLeaf;
            }
        }
    }

    /**
     * Insert a leaf into a tree that currently has no leaves.
     *
     * @param leaf
     * 		the leaf to insert
     */
    private void insertIntoEmptyTree(final T leaf) {
        getRoot().setLeft(leaf);
        rightMostLeaf = leaf;
    }

    /**
     * Insert a leaf into a tree that currently has only one leaf
     *
     * @param leaf
     * 		the leaf to insert
     */
    private void insertIntoTreeWithOneLeaf(final T leaf) {
        getRoot().setRight(leaf);
        rightMostLeaf = leaf;
    }

    /**
     * Inserts a leaf into the tree.
     *
     * @param entry
     * 		New entry to be inserted into the tree
     * @param updateCache
     * 		a function that is used to register changes that may need to be tracked by a cache in the outer scope
     */
    public void insert(final T entry, final Consumer<T> updateCache) {
        throwIfImmutable();

        if (getSize().getValue() == 0) {
            this.insertIntoEmptyTree(entry);
        } else if (getSize().getValue() == 1) {
            this.insertIntoTreeWithOneLeaf(entry);
        } else {

            final T nodeToPush = findFirstLeafAtCompleteSubtree(
                    BitUtil.findLeftMostBit(getSize().getValue()), getSize().getValue(), getRoot());

            // Take an artificial reference to the node to push down to prevent it from being destroyed too soon.
            nodeToPush.reserve();

            // Replace path down to the parent of the node to push down.
            final MerkleNode[] path = replacePath(getRoot(), nodeToPush.getRoute(), 1);

            final MerkleTreeInternalNode parentOfNodeToPush = getParentInPath(path);
            final int childIndexOfNodeToPush = findChildPositionInParent(parentOfNodeToPush, nodeToPush);

            // Add a new internal node
            final MerkleTreeInternalNode newParent = new MerkleTreeInternalNode();
            parentOfNodeToPush.setChild(childIndexOfNodeToPush, newParent);

            // Copy the pushed-down node
            T copy = copyTreeToLocation(newParent, 0, nodeToPush);
            updateCache.accept(copy);

            // Release the artificial reference now that the copy has been made
            nodeToPush.release();

            newParent.setRight(entry);
            rightMostLeaf = entry;
        }

        getSize().increment();
    }

    /**
     * Walks node by node from the specified node to the first leaf in the last
     * complete layer
     *
     * @param leftMostOneBit
     * 		Left most bit
     * @param size
     * 		Number of leaves
     * @param internalNode
     * 		Initial node to start path
     * @return First leaf at complete subtree
     */
    @SuppressWarnings("unchecked")
    private static <R> R findFirstLeafAtCompleteSubtree(
            long leftMostOneBit, final long size, final MerkleTreeInternalNode internalNode) {
        MerkleNode node = internalNode;
        for (leftMostOneBit >>= 1; leftMostOneBit > 0; leftMostOneBit >>= 1) {
            node = (size & leftMostOneBit) == 0
                    ? ((MerkleTreeInternalNode) node).getLeft()
                    : ((MerkleTreeInternalNode) node).getRight();
        }

        return node.cast();
    }

    /**
     * Finds a leaf by iterating over the leaves.
     * This takes O(n), where n is the number of leaves
     * and worst case requires traversing the whole tree.
     *
     * @param condition
     * 		Condition to find the Leaf
     * @return The leaf if found, null otherwise
     */
    public T findValue(final Predicate<T> condition) {
        if (condition == null) {
            throw new IllegalArgumentException("Null predicates are not allowed");
        }

        final Iterator<T> iterator = this.iterator();
        while (iterator.hasNext()) {
            final T leaf = iterator.next();
            if (condition.test(leaf)) {
                return leaf;
            }
        }

        return null;
    }

    /**
     * Returns the right most leaf of the tree.
     * This method is available in order to test correctness
     * of mutating methods.
     *
     * @return Right most leaf
     */
    public T getRightMostLeaf() {
        return rightMostLeaf;
    }

    /**
     * Whenever an entry in this tree is copied by an external source the new copy must
     * be registered. This registration is required to maintain internal data structures.
     *
     * @param original
     * 		the original entry
     * @param copy
     * 		the copy of the original entry
     */
    public void registerCopy(final T original, final T copy) {
        if (rightMostLeaf == original) {
            rightMostLeaf = copy;
        }
    }

    /**
     * Returns the number of leaves in the tree.
     *
     * <p>This operation takes O(1) time</p>
     *
     * @return the number of leaves in the tree
     */
    public long size() {
        return getSize().getValue();
    }

    public void setSize(final long size) {
        throwIfImmutable();
        setSize(new MerkleLong(size));
    }

    /**
     * Returns {@code true} if this tree contains no leaves.
     *
     * <p>This operation takes O(1) time</p>
     *
     * @return {@code true} if this tree contains no leaves
     */
    public boolean isEmpty() {
        return getSize().getValue() == 0;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String toString() {
        return String.format("[size: %d]", getSize().getValue());
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public MerkleIterator<T> iterator() {
        return new MerkleIterator<T>(getRoot())
                .setFilter(MerkleBinaryTree::filter)
                .setDescendantFilter(MerkleBinaryTree::descendantFilter);
    }

    /**
     * This method is used by the iterator to ignore all of the internal nodes in the tree.
     *
     * @param node
     * 		the node the iterator might want to return
     * @return if the node should be returned by the iterator
     */
    private static boolean filter(final MerkleNode node) {
        return node.getClassId() != MerkleTreeInternalNode.CLASS_ID;
    }

    /**
     * This method is used by the iterator to ignore all nodes that may sit below entries if the entries
     * are internal nodes
     *
     * @param node
     * 		the node the iterator might want to return the descendants of
     * @return if the node's descendants can be returned
     */
    private static boolean descendantFilter(final MerkleInternal node) {
        return node.getClassId() == MerkleTreeInternalNode.CLASS_ID;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public long getClassId() {
        return CLASS_ID;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public int getVersion() {
        return ClassVersion.ORIGINAL;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void rebuild() {
        this.setRightMostLeaf();
    }
}
