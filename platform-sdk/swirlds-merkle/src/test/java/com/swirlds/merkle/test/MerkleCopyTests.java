// SPDX-License-Identifier: Apache-2.0
package com.swirlds.merkle.test;

import static com.swirlds.common.merkle.copy.MerkleCopy.adoptChildren;
import static com.swirlds.common.merkle.copy.MerkleCopy.copyTreeToLocation;
import static com.swirlds.common.merkle.utility.MerkleUtils.findChildPositionInParent;
import static com.swirlds.common.test.fixtures.merkle.util.MerkleTestUtils.areTreesEqual;
import static com.swirlds.common.test.fixtures.merkle.util.MerkleTestUtils.buildLessSimpleTree;
import static com.swirlds.common.test.fixtures.merkle.util.MerkleTestUtils.buildLessSimpleTreeExtended;
import static com.swirlds.common.test.fixtures.merkle.util.MerkleTestUtils.haveAnyNodesBeenReleased;
import static com.swirlds.common.test.fixtures.merkle.util.MerkleTestUtils.isFullyInitialized;
import static com.swirlds.common.test.fixtures.merkle.util.MerkleTestUtils.isTreeMutable;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.swirlds.common.merkle.MerkleInternal;
import com.swirlds.common.merkle.MerkleNode;
import com.swirlds.common.merkle.route.MerkleRoute;
import com.swirlds.common.test.fixtures.merkle.dummy.DummyBinaryMerkleInternal;
import com.swirlds.common.test.fixtures.merkle.dummy.DummyMerkleInternal;
import com.swirlds.common.test.fixtures.merkle.dummy.DummyMerkleLeaf;
import com.swirlds.common.test.fixtures.merkle.dummy.DummyMerkleNode;
import java.util.HashSet;
import java.util.Set;
import org.hiero.base.constructable.ConstructableRegistry;
import org.hiero.base.constructable.ConstructableRegistryException;
import org.hiero.base.utility.test.fixtures.tags.TestComponentTags;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@DisplayName("Merkle Copy Tests")
class MerkleCopyTests {

    @BeforeAll
    public static void setUp() throws ConstructableRegistryException {
        final ConstructableRegistry registry = ConstructableRegistry.getInstance();
        registry.registerConstructables("com.swirlds.*");
        registry.registerConstructables("org.hiero");
    }

    /**
     * Run a series of tests on adopting the children of the given parent.
     */
    private void testAdoption(final MerkleInternal parent) {
        // For this test, each child should originally have exactly 1 reference and should not be released
        for (int childIndex = 0; childIndex < parent.getNumberOfChildren(); childIndex++) {
            final MerkleNode child = parent.getChild(childIndex);
            if (child != null) {
                assertEquals(1, child.getReservationCount(), "incorrect ref count");
                assertFalse(child.isDestroyed(), "child is destroyed");
            }
        }

        final DummyMerkleInternal newParent = new DummyMerkleInternal();

        adoptChildren(parent, newParent);

        // children should now each have a reference count of 2, should be same instances in both parents
        for (int childIndex = 0; childIndex < parent.getNumberOfChildren(); childIndex++) {
            final MerkleNode child = parent.getChild(childIndex);
            final MerkleNode child2 = newParent.getChild(childIndex);
            assertSame(child, child2, "children should be same objects");
            if (child != null) {
                assertEquals(2, child.getReservationCount(), "incorrect ref count");
                assertFalse(child.isDestroyed(), "child is destroyed");
            }
        }

        newParent.release();

        // Children should have 1 reference again
        for (int childIndex = 0; childIndex < parent.getNumberOfChildren(); childIndex++) {
            final MerkleNode child = parent.getChild(childIndex);
            if (child != null) {
                assertEquals(1, child.getReservationCount(), "incorrect ref count");
                assertFalse(child.isDestroyed(), "child is destroyed");
            }
        }
    }

    @Test
    @Tag(TestComponentTags.MERKLE)
    @DisplayName("Adopt Children Test")
    void adoptChildrenTest() {
        final DummyMerkleInternal parent = new DummyMerkleInternal();

        // Node has no children
        testAdoption(parent);

        // Node has a null child
        parent.setChild(0, null);
        testAdoption(parent);

        // Node has a bunch of children
        for (int i = 0; i < 50; i++) {
            parent.setChild(i + 1, new DummyMerkleLeaf(Integer.toString(i)));
        }
        testAdoption(parent);
    }

    @Test
    @Tag(TestComponentTags.MERKLE)
    @DisplayName("Find Child Index In Parent Test")
    void findChildIndexInParentTest() {
        final DummyMerkleInternal parent = new DummyMerkleInternal();
        for (int childIndex = 0; childIndex < 50; childIndex++) {

            if (childIndex == 7) {
                // Throw a null value into the child list
                parent.setChild(childIndex, null);
            } else {
                parent.setChild(childIndex, new DummyMerkleLeaf(Integer.toString(childIndex)));
            }

            for (int i = 0; i <= childIndex; i++) {
                final MerkleNode child = parent.getChild(i);
                assertEquals(i, findChildPositionInParent(parent, child), "child index does not match");
            }
        }
    }

    /**
     * Copy a subtree from one location to another perform a battery of sanity checks.
     *
     * @param root
     * 		the root of the tree
     * @param treeToMove
     * 		the subtree that is being moved
     * @param newParent
     * 		the new parent of the tree to move
     * @param newIndex
     * 		the index within the new parent where the copy of the subtree will go
     * @param oldParent
     * 		the old parent of the subtree
     * @param oldIndex
     * 		the old index of the subtree within its parent
     * @param replacement
     * 		the node that will replace the subtree in the old location
     */
    private void testTreeCopy(
            final MerkleNode root,
            final MerkleNode treeToMove,
            final MerkleInternal newParent,
            final int newIndex,
            final MerkleInternal oldParent,
            final int oldIndex,
            final MerkleNode replacement) {

        // No node in the original tree should have been released
        assertFalse(haveAnyNodesBeenReleased(root), "node in original tree has been released");

        // The original tree should have been fully initialized
        assertTrue(isFullyInitialized((DummyMerkleNode) root), "original tree is not initialized");

        // The original tree's leaves should all be mutable.
        assertTrue(isTreeMutable(root), "original tree is not mutable");

        // Gather the route objects in the original tree
        final Set<MerkleRoute> originalRoutes = new HashSet<>();
        root.forEachNode((final MerkleNode node) -> {
            if (node != null) {
                originalRoutes.add(node.getRoute());
            }
        });

        // Make the copy
        final MerkleNode copiedTree = copyTreeToLocation(newParent, newIndex, treeToMove);

        // Replace the old version
        if (!oldParent.isImmutable() && !oldParent.isDestroyed()) {
            oldParent.setChild(oldIndex, replacement);
        }

        if (treeToMove == null) {
            assertNull(copiedTree, "copied tree should also be null");
        } else {
            // Make sure all copied nodes are equivalent but not the same

            assertTrue(areTreesEqual(treeToMove, copiedTree), "trees are not equal");

            treeToMove.forEachNode((final MerkleNode original) -> {
                copiedTree.forEachNode((final MerkleNode copy) -> {
                    assertNotSame(original, copy, "no nodes should be shared between original and copy");
                });
            });

            // No node in the updated tree should have been released
            assertFalse(haveAnyNodesBeenReleased(root), "no released nodes should be in the resulting tree");

            // The new tree should have been fully initialized
            assertTrue(isFullyInitialized((DummyMerkleNode) root), "resulting tree is not initialized");

            // The new tree should be fully mutable
            assertTrue(isTreeMutable(root), "resulting tree is not mutable");
        }

        // Double check that route to each node is correct
        root.forEachNode((final MerkleNode node) -> {
            if (node != null) {
                assertSame(root.getNodeAtRoute(node.getRoute()), node, "node has incorrect route");
            }
        });

        // If the same route is the original tree and the new tree, those route objects should be the same object.
        // This check ensures that we are always recycling routes.
        final Set<MerkleRoute> resultingRoutes = new HashSet<>();
        root.forEachNode((final MerkleNode node) -> {
            if (node != null) {
                resultingRoutes.add(node.getRoute());
            }
        });
        for (final MerkleRoute originalRoute : originalRoutes) {
            for (final MerkleRoute resultingRoute : resultingRoutes) {
                if (originalRoute.equals(resultingRoute)) {
                    assertSame(originalRoute, resultingRoute, "route was recreated when it should have been recycled");
                }
            }
        }
    }

    /**
     * Copy a subtree from one location its parent to another within the same parent.
     */
    @Test
    @Tag(TestComponentTags.MERKLE)
    @DisplayName("Move Tree To New Position In Parent Test")
    void moveTreeToNewPositionInParentTest() {
        // Note: node names are based on the names defined in buildLessSimpleTree()

        final DummyMerkleInternal root = buildLessSimpleTree();
        final DummyMerkleInternal i0 = root.getChild(1);
        final DummyMerkleLeaf replacement = new DummyMerkleLeaf("replacement");

        testTreeCopy(root, i0, root, 3, root, 1, replacement);
    }

    /**
     * Copy a subtree to a new parent.
     */
    @Test
    @Tag(TestComponentTags.MERKLE)
    @DisplayName("Move Tree To New Parent Test")
    void moveTreeToNewParentTest() {
        // Note: node names are based on the names defined in buildLessSimpleTree()

        final DummyMerkleInternal root = buildLessSimpleTree();
        final DummyMerkleInternal i0 = root.getChild(1);
        final DummyMerkleInternal i1 = root.getChild(2);
        final DummyMerkleLeaf replacement = new DummyMerkleLeaf("replacement");

        testTreeCopy(root, i0, i1, 2, root, 1, replacement);
    }

    /**
     * Copy a subtree to a location already containing a node
     */
    @Test
    @Tag(TestComponentTags.MERKLE)
    @DisplayName("Move Replaces Node Test")
    void moveReplacesNodeTest() {
        // Note: node names are based on the names defined in buildLessSimpleTree()

        final DummyMerkleInternal root = buildLessSimpleTree();
        final DummyMerkleInternal i0 = root.getChild(1);
        final DummyMerkleInternal i1 = root.getChild(2);
        final DummyMerkleLeaf replacement = new DummyMerkleLeaf("replacement");

        testTreeCopy(root, i0, i1, 0, root, 1, replacement);
    }

    /**
     * Copy a subtree to a location already containing a node
     */
    @Test
    @Tag(TestComponentTags.MERKLE)
    @DisplayName("Move Replaces Null Node Test")
    void moveReplacesNullNodeTest() {
        // Note: node names are based on the names defined in buildLessSimpleTree()

        final DummyMerkleInternal root = buildLessSimpleTree();
        final DummyMerkleInternal i0 = root.getChild(1);
        final DummyMerkleInternal i1 = root.getChild(2);
        final DummyMerkleLeaf replacement = new DummyMerkleLeaf("replacement");

        testTreeCopy(root, i0, i1, 1, root, 1, replacement);
    }

    /**
     * Copy a node to a location that overrides an ancestor.
     */
    @Test
    @Tag(TestComponentTags.MERKLE)
    @DisplayName("Move Replaces Ancestor Test")
    void moveReplacesAncestorTest() {
        // Note: node names are based on the names defined in buildLessSimpleTreeExtended()

        final DummyMerkleInternal root = buildLessSimpleTreeExtended();
        final DummyMerkleInternal i1 = root.getChild(2);
        final DummyMerkleInternal i2 = i1.getChild(1);
        final DummyMerkleInternal i3 = i2.getChild(0);
        final DummyMerkleLeaf replacement = new DummyMerkleLeaf("replacement");

        testTreeCopy(root, i3, root, 2, i2, 0, replacement);
    }

    /**
     * Make sure that the move semantics behave nicely with leaves.
     */
    @Test
    @Tag(TestComponentTags.MERKLE)
    @DisplayName("Move Leaf Test")
    void moveLeafTest() {
        // Note: node names are based on the names defined in buildLessSimpleTree()

        final DummyMerkleInternal root = buildLessSimpleTree();
        final DummyMerkleLeaf A = root.getChild(0);
        final DummyMerkleInternal i1 = root.getChild(2);
        final DummyMerkleLeaf replacement = new DummyMerkleLeaf("replacement");

        testTreeCopy(root, A, i1, 0, root, 0, replacement);
    }

    /**
     * Make sure that the move semantics behave nicely with null values.
     */
    @Test
    @Tag(TestComponentTags.MERKLE)
    @DisplayName("Move Leaf Test")
    void moveNullLeafTest() {
        // Note: node names are based on the names defined in buildLessSimpleTree()

        final DummyMerkleInternal root = buildLessSimpleTree();
        final DummyMerkleInternal i1 = root.getChild(2);
        final DummyMerkleLeaf replacement = new DummyMerkleLeaf("replacement");

        testTreeCopy(root, null, root, 0, i1, 1, replacement);
    }

    /**
     * Make sure trees with null descendants can be properly moved.
     */
    @Test
    @Tag(TestComponentTags.MERKLE)
    @DisplayName("Move Tree With Null Descendant")
    void moveTreeWithNullDescendantTest() {
        // Note: node names are based on the names defined in buildLessSimpleTree()

        final DummyMerkleInternal root = buildLessSimpleTree();
        final DummyMerkleInternal i1 = root.getChild(2);
        final DummyMerkleLeaf replacement = new DummyMerkleLeaf("replacement");

        testTreeCopy(root, i1, root, 0, root, 2, replacement);
    }

    /**
     * Make sure there is no problem moving a tree with multiple references to nodes.
     */
    @Test
    @Tag(TestComponentTags.MERKLE)
    @DisplayName("Move Tree With References")
    void moveTreeWithReferences() {
        // Note: node names are based on the names defined in buildLessSimpleTree()

        final DummyMerkleInternal root = buildLessSimpleTree();
        final DummyMerkleInternal i1 = root.getChild(2);
        i1.reserve();
        final DummyMerkleLeaf D = i1.getChild(0);
        D.reserve();
        final DummyMerkleLeaf replacement = new DummyMerkleLeaf("replacement");

        testTreeCopy(root, i1, root, 0, root, 2, replacement);
    }

    /**
     * Move a large(ish) subtree
     */
    @Test
    @Tag(TestComponentTags.MERKLE)
    @DisplayName("Move Large Subtree Test")
    void moveLargeSubtreeTest() {
        // Note: node names are based on the names defined in buildLessSimpleTreeExtended()

        final DummyMerkleInternal root = buildLessSimpleTreeExtended();
        final DummyMerkleInternal i1 = root.getChild(2);
        final DummyMerkleLeaf replacement = new DummyMerkleLeaf("replacement");

        testTreeCopy(root, i1, root, 2, root, 1, replacement);
    }

    /**
     * This test checks for the presence of a bug that used to exist. This bug was
     * provoked when a node was copied via copyTreeToLocation() to a location with fewer children.
     */
    @Test
    @Tag(TestComponentTags.MERKLE)
    @DisplayName("Copy Binary To Location With Different Child Counts")
    void copyBinaryToLocationWithDifferentChildCounts() {
        final MerkleInternal root = new DummyMerkleInternal("root");

        final MerkleInternal childToBeReplaced = new DummyBinaryMerkleInternal();
        root.setChild(0, childToBeReplaced);
        childToBeReplaced.setChild(0, new DummyMerkleLeaf("A"));
        childToBeReplaced.setChild(1, new DummyMerkleLeaf("B"));

        final MerkleInternal childToBeCopied = new DummyMerkleInternal("child to be copied");
        root.setChild(1, childToBeCopied);
        childToBeCopied.setChild(0, new DummyMerkleInternal("X"));
        childToBeCopied.setChild(1, new DummyMerkleInternal("Y"));
        childToBeCopied.setChild(2, new DummyMerkleInternal("Z"));

        // Previously this would crash
        copyTreeToLocation(root, 0, childToBeCopied);
    }
}
