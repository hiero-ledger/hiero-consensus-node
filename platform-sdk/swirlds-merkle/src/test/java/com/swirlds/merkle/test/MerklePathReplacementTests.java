// SPDX-License-Identifier: Apache-2.0
package com.swirlds.merkle.test;

import static com.swirlds.common.merkle.copy.MerklePathReplacement.replacePath;
import static com.swirlds.common.test.fixtures.merkle.util.MerkleTestUtils.areTreesEqual;
import static com.swirlds.common.test.fixtures.merkle.util.MerkleTestUtils.buildLessSimpleTreeExtended;
import static com.swirlds.common.test.fixtures.merkle.util.MerkleTestUtils.buildSmallTreeList;
import static com.swirlds.common.test.fixtures.merkle.util.MerkleTestUtils.getNodeInTree;
import static com.swirlds.common.test.fixtures.merkle.util.MerkleTestUtils.haveAnyNodesBeenReleased;
import static com.swirlds.common.test.fixtures.merkle.util.MerkleTestUtils.isFullyInitialized;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.swirlds.common.merkle.MerkleInternal;
import com.swirlds.common.merkle.MerkleNode;
import com.swirlds.common.merkle.route.MerkleRoute;
import com.swirlds.common.merkle.route.MerkleRouteIterator;
import com.swirlds.common.test.fixtures.merkle.TestMerkleCryptoFactory;
import com.swirlds.common.test.fixtures.merkle.dummy.DummyMerkleNode;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import org.hiero.base.constructable.ConstructableRegistry;
import org.hiero.base.constructable.ConstructableRegistryException;
import org.hiero.base.utility.test.fixtures.tags.TestComponentTags;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@DisplayName("Merkle Path Replacement Tests")
class MerklePathReplacementTests {

    @BeforeAll
    public static void setUp() throws ConstructableRegistryException {
        final ConstructableRegistry registry = ConstructableRegistry.getInstance();
        registry.registerConstructables("com.swirlds.*");
        registry.registerConstructables("org.hiero.consensus");
    }

    /**
     * Iterate the tree. Ensure that each node not at specified depth (with the root excluded) has has a reference
     * count of 2. For all other depths, throw an exception if an unexpected reference count is detected.
     *
     * This allows for path replacement to test what happens when the first node with a reference count greater than
     * 1 is in different positions.
     *
     * Returns a list of nodes that have had a reference count artificially increased.
     */
    private List<MerkleNode> setArtificialReferences(final MerkleNode root, final int depth) {
        final List<MerkleNode> nodes = new LinkedList<>();

        if (root == null) {
            return nodes;
        }

        root.forEachNode((final MerkleNode node) -> {
            if (node == null) {
                return;
            }
            final int nodeDepth = node.getRoute().size();
            if (nodeDepth == 0) {
                assertEquals(0, node.getReservationCount(), "root of a tree should have a reference count of 0");
            } else {
                assertEquals(1, node.getReservationCount(), "non-root nodes should have a reference count of 1");
                if (nodeDepth == depth) {
                    node.reserve();
                    nodes.add(node);
                }
            }
        });
        return nodes;
    }

    /**
     * Iterate over a path and return the last node.
     */
    private MerkleNode getTarget(final MerkleNode treeRoot, final MerkleRoute routeToReplace) {
        final MerkleRouteIterator iterator = new MerkleRouteIterator(treeRoot, routeToReplace);
        return iterator.getLast();
    }

    /**
     * Hash the tree, but make sure the path down to and including the root of the path to replace has a null hash.
     */
    private void hashTreeForReplacement(final MerkleNode treeRoot, final MerkleNode pathRoot) {
        TestMerkleCryptoFactory.getInstance().digestTreeSync(treeRoot);

        final MerkleRoute pathRootRoute = pathRoot.getRoute();
        final Iterator<MerkleNode> iterator = new MerkleRouteIterator(treeRoot, pathRootRoute);
        iterator.forEachRemaining((final MerkleNode node) -> {
            if (node != null) {
                node.invalidateHash();
            }
        });
    }

    /**
     * Get the original path before replacement. Returns a list of all nodes between the path root and the last node
     * in the given route.
     */
    private List<MerkleNode> getOriginalPath(
            final MerkleNode treeRoot, final MerkleNode pathRoot, final MerkleRoute routeToReplace) {

        final int pathRootDepth = pathRoot.getRoute().size();
        final Iterator<MerkleNode> iterator = new MerkleRouteIterator(treeRoot, routeToReplace);
        for (int i = 0; i < pathRootDepth; i++) {
            iterator.next();
        }

        final List<MerkleNode> path = new ArrayList<>();
        iterator.forEachRemaining(path::add);

        return path;
    }

    /**
     * Get the depth of the first node that should be replaced within the path. If no node should replaced then return
     * the maximum size for an integer.
     */
    private int getDepthOfFirstReplacedNode(final List<MerkleNode> originalPath) {
        // First node in path is never copied, start at index 1
        for (int index = 1; index < originalPath.size(); index++) {
            if (originalPath.get(index).getReservationCount() > 1) {
                return index + originalPath.get(0).getRoute().size();
            }
        }

        return Integer.MAX_VALUE;
    }

    /**
     * Return a list of the target's children.
     */
    private List<MerkleNode> getTargetChildren(final MerkleNode target) {
        final List<MerkleNode> children = new LinkedList<>();
        if (target.isLeaf()) {
            return children;
        }
        final MerkleInternal targetInternal = target.cast();
        for (int childIndex = 0; childIndex < targetInternal.getNumberOfChildren(); childIndex++) {
            children.add(targetInternal.getChild(childIndex));
        }
        return children;
    }

    /**
     * Double check that the returned route matches the actual route in the tree.
     */
    private void validatePathIntegrity(
            final MerkleNode treeRoot,
            final MerkleNode pathRoot,
            final MerkleRoute routeToReplace,
            final MerkleNode[] path) {

        final int depthOfPathRoot = pathRoot.getRoute().size();
        assertEquals(routeToReplace.size() - depthOfPathRoot + 1, path.length, "path length is wrong");

        // Skip the nodes between the tree root and the path root
        final Iterator<MerkleNode> iterator = new MerkleRouteIterator(treeRoot, routeToReplace);
        for (int i = 0; i < depthOfPathRoot; i++) {
            iterator.next();
        }

        for (final MerkleNode pathNode : path) {
            assertTrue(iterator.hasNext(), "path length doesn't match actual path");
            assertSame(pathNode, iterator.next(), "reported path doesn't match actual path");
        }
    }

    /**
     * Ensure that node replacement happened like we expected.
     */
    private void checkReplacedNodes(
            final List<MerkleNode> originalPath,
            final MerkleNode[] path,
            final int levelsToSkip,
            final int firstExpectedNodeToBeCopied) {

        assertEquals(path.length, originalPath.size(), "path length does not match");

        final int depthOfPathRoot = path[0].getRoute().size();

        for (int index = 0; index < path.length; index++) {
            final int depth = index + depthOfPathRoot;
            if (depth >= firstExpectedNodeToBeCopied && index < path.length - levelsToSkip) {
                assertNotSame(path[index], originalPath.get(index), "node should have been replaced");
            } else {
                assertSame(path[index], originalPath.get(index), "node should not have been replaced");
            }
        }
    }

    /**
     * If the target has any children, it is expected that they do not change. Verify that fact.
     */
    private void checkTargetChildren(
            final MerkleNode target, final MerkleNode[] path, final List<MerkleNode> originalTargetChildren) {

        if (!target.isLeaf()) {
            final MerkleInternal newTarget = path[path.length - 1].cast();
            assertEquals(
                    newTarget.getNumberOfChildren(),
                    originalTargetChildren.size(),
                    "number of children held by target has changed");
            for (int childIndex = 0; childIndex < newTarget.getNumberOfChildren(); childIndex++) {
                assertSame(
                        newTarget.getChild(childIndex),
                        originalTargetChildren.get(childIndex),
                        "child of target has been modified");
            }
        }
    }

    /**
     * After all artificial references have been released, all nodes should have a reference count of 1, except
     * for the root which will have a reference count of 0.
     */
    private void validateReferenceCounts(final MerkleNode treeRoot) {
        treeRoot.forEachNode((final MerkleNode node) -> {
            if (node == null) {
                return;
            }
            final int nodeDepth = node.getRoute().size();
            if (nodeDepth == 0) {
                assertEquals(0, node.getReservationCount(), "root should have a reference count of 0");
            } else {
                assertEquals(1, node.getReservationCount(), "node should have a reference count of 1");
            }
        });
    }

    /**
     * Helper method for testing a path replacement.
     *
     * @param treeRoot
     * 		the root of the tree
     * @param treeRootCopy
     * 		should be an identical (but distinct) tree as tree root.
     * 		Used to ensure that the final product is the same tree.
     * @param pathRootRoute
     * 		the route of the first node in the path to be replaced
     * @param routeToReplace
     * 		the route that is being replaced
     * @param levelsToSkip
     * 		the number of nodes at the end to not replace
     * @param artificialReferenceDepth
     * 		the depth at which to take artificial references to nodes
     */
    private void testPathReplacement(
            final MerkleNode treeRoot,
            final MerkleNode treeRootCopy,
            final MerkleRoute pathRootRoute,
            final MerkleRoute routeToReplace,
            final int levelsToSkip,
            final int artificialReferenceDepth) {

        final MerkleNode pathRoot = treeRoot.getNodeAtRoute(pathRootRoute);

        hashTreeForReplacement(treeRoot, pathRoot);

        // Get the last node at the end of the path to be replaced
        final MerkleNode target = getTarget(treeRoot, routeToReplace);

        // No node in the original tree should have been destroyed
        assertFalse(haveAnyNodesBeenReleased(treeRoot), "no nodes in the initial tree should have been destroyed");

        // The original tree should have been fully initialized
        assertTrue(isFullyInitialized((DummyMerkleNode) treeRoot), "original tree is not initialized");

        // Increase the reference counts at a certain depth. This will change the depth where replacement starts.
        final List<MerkleNode> artificiallyReferencedNodes =
                setArtificialReferences(treeRoot, artificialReferenceDepth);

        // Get the original nodes along the path that we are going to be replacing.
        final List<MerkleNode> originalPath = getOriginalPath(treeRoot, pathRoot, routeToReplace);

        // Record the index after which all non-skipped nodes are expected to be copied.
        int firstExpectedNodeToBeCopied = getDepthOfFirstReplacedNode(originalPath);

        // Capture the original children of the target. These children are expected to be left untouched.
        final List<MerkleNode> originalTargetChildren = getTargetChildren(target);

        // Do the path replacement.
        final MerkleNode[] path = replacePath(pathRoot, target.getRoute(), levelsToSkip);

        // The number of nodes returned in the path should exactly match the length of the route
        assertEquals(
                routeToReplace.size() - pathRootRoute.size() + 1,
                path.length,
                "path length should exceed route length by exactly 1");

        // Nodes in the path should all have a null hash
        for (int i = 0; i < path.length; i++) {
            if (i < path.length - levelsToSkip) {
                assertNull(path[i].getHash(), "unskipped nodes in the path should have a null hash");
            }
        }

        // Sanity check on the nodes returned in the path. Walk tree to verify that they match.
        validatePathIntegrity(treeRoot, pathRoot, routeToReplace, path);

        // Ensure that all nodes that should have been replaced actually have been replaced,
        // and that nodes that should have been left alone have not been replaced.
        checkReplacedNodes(originalPath, path, levelsToSkip, firstExpectedNodeToBeCopied);

        // If the target has children, ensure that they were not modified.
        checkTargetChildren(target, path, originalTargetChildren);

        // The final result should be equal to the copy matching the original
        areTreesEqual(treeRoot, treeRootCopy);

        // Release artificial references.
        for (MerkleNode node : artificiallyReferencedNodes) {
            node.release();
        }

        // No node in the new tree should have been released.
        assertFalse(haveAnyNodesBeenReleased(treeRoot), "no nodes in the resulting tree should have been released");

        // Each node should have a reference count of 1, except for the root which will have a reference count of 0.
        validateReferenceCounts(treeRoot);

        // The new tree should have been fully initialized
        assertTrue(isFullyInitialized((DummyMerkleNode) treeRoot), "resulting tree is not initialized");
    }

    private void testReplacementsWithConfiguration(final int artificialReferenceDepth, final int levelsToSkip) {
        final List<DummyMerkleNode> treeList = buildSmallTreeList();
        final List<DummyMerkleNode> treeListCopy = buildSmallTreeList();

        for (int index = 0; index < treeList.size(); index++) {
            final MerkleNode tree = treeList.get(index);
            final MerkleNode treeCopy = treeListCopy.get(index);

            if (tree == null) {
                continue;
            }

            // Test a path replacement to each node in the tree, from each node in the tree.
            treeCopy.forEachNode((final MerkleNode pathStart) -> {
                treeCopy.forEachNode((final MerkleNode pathDestination) -> {
                    if (!pathStart.getRoute().isAncestorOf(pathDestination.getRoute())) {
                        return;
                    }

                    testPathReplacement(
                            tree,
                            treeCopy,
                            pathStart.getRoute(),
                            pathDestination.getRoute(),
                            levelsToSkip,
                            artificialReferenceDepth);
                });
            });
        }
    }

    /**
     * Test path replacements under a wide variety of conditions.
     */
    @Test
    @Tag(TestComponentTags.MERKLE)
    @DisplayName("Test Path Replacement")
    void testPathReplacement() {
        final int depthLimit = 5;

        for (int artificialReferenceDepth = 0; artificialReferenceDepth < depthLimit; artificialReferenceDepth++) {
            for (int levelsToSkip = 0; levelsToSkip < depthLimit; levelsToSkip++) {
                testReplacementsWithConfiguration(artificialReferenceDepth, levelsToSkip);
            }
        }
    }

    /**
     * Test for a bug that once existed in path replacement where already hashed nodes could have the hash
     * invalidated by a path replacement operation in a different tree.
     */
    @Test
    @Tag(TestComponentTags.MERKLE)
    @DisplayName("Nodes In Other Trees Are Not Invalidated Test")
    void nodesInOtherTreesAreNotInvalidatedTest() {

        // Note: variable names are based on the names in the diagram for buildLessSimpleTreeExtended()

        // This tree has been fully hashed
        final MerkleNode root = buildLessSimpleTreeExtended();
        TestMerkleCryptoFactory.getInstance().digestTreeSync(root);

        final MerkleNode root2 = buildLessSimpleTreeExtended();

        // To simulate a fast copy, suppose leaf F is shared between the copies
        final MerkleNode sharedF = getNodeInTree(root, 2, 1, 0, 0);
        final MerkleInternal i3 = getNodeInTree(root2, 2, 1, 0).cast();
        i3.setChild(0, sharedF);

        assertEquals(2, sharedF.getReservationCount(), "expected reference count to be 2");

        // Replace the path down to F, skipping F
        replacePath(root2, sharedF.getRoute(), 1);

        assertNotNull(sharedF.getHash(), "hash of shared leaf should not be invalidated");
    }
}
