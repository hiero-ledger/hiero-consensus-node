// SPDX-License-Identifier: Apache-2.0
package com.swirlds.merkle.test;

import static com.swirlds.common.test.fixtures.merkle.util.MerkleTestUtils.buildTreeList;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.swirlds.common.merkle.MerkleNode;
import com.swirlds.common.test.fixtures.merkle.dummy.DummyMerkleInternal;
import com.swirlds.common.test.fixtures.merkle.dummy.DummyMerkleLeaf;
import com.swirlds.common.test.fixtures.merkle.dummy.DummyMerkleNode;
import java.util.LinkedList;
import java.util.List;
import org.hiero.base.exceptions.ReferenceCountException;
import org.hiero.base.utility.test.fixtures.tags.TestComponentTags;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@DisplayName("Merkle Reference Counting Tests")
class MerkleReferenceCountingTests {

    /**
     * A newly initialized tree should have a reference count of 1 for each node (except for the root)
     */
    @Test
    @Tag(TestComponentTags.MERKLE)
    @DisplayName("Reference Count Initialization Test")
    void referenceCountInitializationTest() {

        final List<DummyMerkleNode> trees = buildTreeList();

        for (final MerkleNode tree : trees) {
            if (tree != null) {
                tree.forEachNode((node) -> {
                    if (node == tree) {
                        assertEquals(0, node.getReservationCount());
                    } else {
                        assertEquals(1, node.getReservationCount());
                    }
                });
            }
        }
    }

    /**
     * Basic sanity check for deletion.
     */
    @Test
    @Tag(TestComponentTags.MERKLE)
    @DisplayName("Deletion Test")
    void deletionTest() {

        final List<DummyMerkleNode> trees = buildTreeList();

        // After deleting a tree, each node should be be deleted and have a reference count of 0
        for (final MerkleNode tree : trees) {
            if (tree != null) {
                final List<DummyMerkleNode> nodes = new LinkedList<>();
                tree.forEachNode((node) -> nodes.add((DummyMerkleNode) node));

                tree.release();

                for (final DummyMerkleNode node : nodes) {
                    assertTrue(node.isDestroyed());
                    assertEquals(-1, node.getReservationCount());
                }
            }
        }
    }

    /**
     * Verify behavior when a node has multiple references.
     */
    @Test
    @Tag(TestComponentTags.MERKLE)
    @DisplayName("Multiple Reference Test")
    void multipleReferenceTest() {
        final DummyMerkleInternal root1 = new DummyMerkleInternal("root1");
        final DummyMerkleInternal root2 = new DummyMerkleInternal("root2");
        final DummyMerkleInternal root3 = new DummyMerkleInternal("root3");

        final DummyMerkleInternal internal1 = new DummyMerkleInternal("internal1");
        final DummyMerkleInternal internal2 = new DummyMerkleInternal("internal2");

        final DummyMerkleLeaf leaf = new DummyMerkleLeaf("leaf");

        root1.setChild(0, internal1);
        root2.setChild(0, internal2);
        root3.setChild(0, internal2);

        internal1.setChild(0, leaf);
        internal2.setChild(0, leaf);

        assertEquals(1, internal1.getReservationCount());
        assertFalse(internal1.isDestroyed());
        assertEquals(2, internal2.getReservationCount());
        assertFalse(internal2.isDestroyed());
        assertEquals(2, leaf.getReservationCount());
        assertFalse(leaf.isDestroyed());

        root1.release();
        assertEquals(-1, internal1.getReservationCount());
        assertTrue(internal1.isDestroyed());
        assertEquals(2, internal2.getReservationCount());
        assertFalse(internal2.isDestroyed());
        assertEquals(1, leaf.getReservationCount());
        assertFalse(leaf.isDestroyed());

        root2.release();
        assertEquals(1, internal2.getReservationCount());
        assertFalse(internal2.isDestroyed());
        assertEquals(1, leaf.getReservationCount());
        assertFalse(leaf.isDestroyed());

        root3.release();
        assertEquals(-1, internal2.getReservationCount());
        assertTrue(internal2.isDestroyed());
        assertEquals(-1, leaf.getReservationCount());
        assertTrue(leaf.isDestroyed());
    }

    /**
     * Verify behavior when a child node is removed from its parent.
     */
    @Test
    @Tag(TestComponentTags.MERKLE)
    @DisplayName("Child Replacement Test")
    void childReplacementTest() {
        final DummyMerkleInternal root = new DummyMerkleInternal("root");
        final DummyMerkleLeaf leaf1 = new DummyMerkleLeaf("leaf1");

        root.setChild(0, leaf1);

        assertEquals(1, leaf1.getReservationCount());
        assertFalse(leaf1.isDestroyed());

        final DummyMerkleLeaf leaf2 = new DummyMerkleLeaf("leaf2");
        root.setChild(0, leaf2);

        assertEquals(1, leaf2.getReservationCount());
        assertFalse(leaf2.isDestroyed());

        assertEquals(-1, leaf1.getReservationCount());
        assertTrue(leaf1.isDestroyed());
    }

    @Test
    @Tag(TestComponentTags.MERKLE)
    @DisplayName("Exception Throwing Tests")
    void exceptionThrowingTests() {

        DummyMerkleLeaf leaf = new DummyMerkleLeaf("leaf");
        leaf.release();
        assertThrows(ReferenceCountException.class, leaf::reserve, "can't increment count on destroyed leaf");
        assertThrows(ReferenceCountException.class, leaf::release, "can't decrement count on destroyed leaf");
        assertThrows(ReferenceCountException.class, leaf::release, "can't release an already destroyed leaf");

        leaf = new DummyMerkleLeaf("leaf");
        leaf.reserve();
        leaf.release();
        assertThrows(ReferenceCountException.class, leaf::reserve, "can't increment count on destroyed leaf");
        assertThrows(ReferenceCountException.class, leaf::release, "can't decrement count on destroyed leaf");
        assertThrows(ReferenceCountException.class, leaf::release, "can't release an already destroyed leaf");
    }
}
