// SPDX-License-Identifier: Apache-2.0
package com.swirlds.merkle.test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.swirlds.common.merkle.MerkleNode;
import com.swirlds.common.test.fixtures.merkle.dummy.DummyMerkleNode;
import com.swirlds.common.test.fixtures.merkle.util.MerkleTestUtils;
import java.util.List;
import org.hiero.base.utility.test.fixtures.tags.TestComponentTags;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Tests that verify the testing framework for merkle tests.
 */
@DisplayName("Merkle Meta Tests")
public class MerkleMetaTests {

    /**
     * A sanity check on test logic that compares two merkle trees for similarity.
     */
    @Test
    @Tag(TestComponentTags.MERKLE)
    @DisplayName("Test Merkle Comparison")
    public void testMerkleComparison() {

        final List<DummyMerkleNode> listI = MerkleTestUtils.buildTreeList();
        final List<DummyMerkleNode> listJ = MerkleTestUtils.buildTreeList();

        for (int i = 0; i < listI.size(); i++) {
            for (int j = 0; j < listJ.size(); j++) {
                final DummyMerkleNode nodeI = listI.get(i);
                final DummyMerkleNode nodeJ = listJ.get(j);
                if (i == j) {
                    assertTrue(MerkleTestUtils.areTreesEqual(nodeI, nodeJ), "trees should be equal");
                } else {
                    assertFalse(MerkleTestUtils.areTreesEqual(nodeI, nodeJ));
                }
            }
        }
    }

    /**
     * Verify that the measureTreeDepth function returns sane values.
     */
    @Test
    @Tag(TestComponentTags.MERKLE)
    @DisplayName("Test Merkle Depth")
    public void testMerkleDepth() {
        MerkleNode tree = MerkleTestUtils.buildSizeZeroTree();
        assertEquals(0, MerkleTestUtils.measureTreeDepth(tree), "incorrect depth");

        tree = MerkleTestUtils.buildSizeOneTree();
        assertEquals(1, MerkleTestUtils.measureTreeDepth(tree), "incorrect depth");

        tree = MerkleTestUtils.buildSimpleTree();
        assertEquals(2, MerkleTestUtils.measureTreeDepth(tree), "incorrect depth");

        tree = MerkleTestUtils.buildLessSimpleTree();
        assertEquals(3, MerkleTestUtils.measureTreeDepth(tree), "incorrect depth");
    }

    /**
     * Verify that measureNumberOfLeafNodes returns sane values.
     */
    @Test
    @Tag(TestComponentTags.MERKLE)
    @DisplayName("Test Leaf Node Count")
    public void testLeafNodeCount() {
        MerkleNode tree = MerkleTestUtils.buildSizeZeroTree();
        assertEquals(0, MerkleTestUtils.measureNumberOfLeafNodes(tree), "incorrect count");

        tree = MerkleTestUtils.buildSizeOneTree();
        assertEquals(1, MerkleTestUtils.measureNumberOfLeafNodes(tree), "incorrect count");

        tree = MerkleTestUtils.buildSimpleTree();
        assertEquals(1, MerkleTestUtils.measureNumberOfLeafNodes(tree), "incorrect count");

        tree = MerkleTestUtils.buildLessSimpleTree();
        assertEquals(5, MerkleTestUtils.measureNumberOfLeafNodes(tree), "incorrect count");
    }

    /**
     * Verify that measureNumberOfNodes returns sane values.
     */
    @Test
    @Tag(TestComponentTags.MERKLE)
    @DisplayName("Test Node Count")
    public void testNodeCount() {
        MerkleNode tree = MerkleTestUtils.buildSizeZeroTree();
        assertEquals(0, MerkleTestUtils.measureNumberOfNodes(tree), "incorrect count");

        tree = MerkleTestUtils.buildSizeOneTree();
        assertEquals(1, MerkleTestUtils.measureNumberOfNodes(tree), "incorrect count");

        tree = MerkleTestUtils.buildSimpleTree();
        assertEquals(2, MerkleTestUtils.measureNumberOfNodes(tree), "incorrect count");

        tree = MerkleTestUtils.buildLessSimpleTree();
        assertEquals(8, MerkleTestUtils.measureNumberOfNodes(tree), "incorrect count");
    }

    /**
     * Verify that measureAverageLeafDepth returns sane values.
     */
    @Test
    @Tag(TestComponentTags.MERKLE)
    @DisplayName("Test Average Leaf Depth")
    public void testAverageLeafDepth() {
        MerkleNode tree = MerkleTestUtils.buildSizeZeroTree();
        assertTrue(
                Math.abs(MerkleTestUtils.measureAverageLeafDepth(tree) - 0) < 0.001,
                "depth should be within threshold");

        tree = MerkleTestUtils.buildSizeOneTree();
        assertTrue(
                Math.abs(MerkleTestUtils.measureAverageLeafDepth(tree) - 1) < 0.001,
                "expected value to be within bounds");

        tree = MerkleTestUtils.buildSimpleTree();
        assertTrue(
                Math.abs(MerkleTestUtils.measureAverageLeafDepth(tree) - 2) < 0.001,
                "expected value to be within bounds");

        tree = MerkleTestUtils.buildLessSimpleTree();
        assertTrue(
                Math.abs(MerkleTestUtils.measureAverageLeafDepth(tree) - 2.75) < 0.001,
                "expected value to be within bounds");
    }

    /**
     * Verify that measureAverageLeafSize returns sane values.
     */
    @Test
    @Tag(TestComponentTags.MERKLE)
    @DisplayName("Test Average Leaf Size")
    public void testAverageLeafSize() {
        final DummyMerkleNode tree = MerkleTestUtils.buildLessSimpleTree();
        assertTrue(
                Math.abs(MerkleTestUtils.measureAverageLeafSize(tree) - 1.0) < 0.001,
                "average leaf size should be within threshold");
    }
}
