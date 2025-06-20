// SPDX-License-Identifier: Apache-2.0
package com.swirlds.merkle.test;

import static com.swirlds.common.merkle.iterators.MerkleIterationOrder.BREADTH_FIRST;
import static com.swirlds.common.merkle.iterators.MerkleIterationOrder.POST_ORDERED_DEPTH_FIRST;
import static com.swirlds.common.merkle.iterators.MerkleIterationOrder.PRE_ORDERED_DEPTH_FIRST;
import static com.swirlds.common.merkle.iterators.MerkleIterationOrder.REVERSE_POST_ORDERED_DEPTH_FIRST;
import static com.swirlds.common.merkle.route.MerkleRouteFactory.buildRoute;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.swirlds.common.merkle.MerkleNode;
import com.swirlds.common.merkle.iterators.MerkleIterator;
import com.swirlds.common.merkle.route.MerkleRoute;
import com.swirlds.common.test.fixtures.merkle.dummy.DummyMerkleInternal;
import com.swirlds.common.test.fixtures.merkle.dummy.DummyMerkleLeaf;
import com.swirlds.common.test.fixtures.merkle.dummy.DummyMerkleNode;
import com.swirlds.common.test.fixtures.merkle.util.MerkleTestUtils;
import java.io.IOException;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.NoSuchElementException;
import org.hiero.base.utility.test.fixtures.tags.TestComponentTags;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@DisplayName("Merkle Iteration Tests")
class MerkleIterationTests {

    /**
     * Utility class for asserting iteration order.
     */
    private static class ExpectedNode {
        private final String value;
        private final boolean internal;
        private final MerkleRoute route;

        public ExpectedNode(final boolean internal, final String value) {
            this(internal, value, null);
        }

        public ExpectedNode(final boolean internal, final String value, final MerkleRoute route) {

            if (value == null && route == null) {
                throw new AssertionError("if the value is null then the route should be provided");
            }

            this.internal = internal;
            this.value = value;
            this.route = route;
        }

        public void assertNodeMatches(final MerkleNode node, final MerkleRoute expectedRoute) {
            if (internal) {
                assertTrue(node instanceof DummyMerkleInternal, "expected internal node");
            } else {
                assertTrue(node == null || node instanceof DummyMerkleLeaf, "expected leaf node");
            }
            final String nodeValue = node == null ? null : ((DummyMerkleNode) node).getValue();
            assertEquals(value, nodeValue, "node should have expected value");

            if (node == null) {
                assertEquals(expectedRoute, route, "route of null node does not match");
            } else {
                assertEquals(expectedRoute, node.getRoute(), "route of non-null node does not match");
            }
        }
    }

    /**
     * Make sure that in iterator returns a sequence in the expected order.
     */
    void assertIterationOrder(final MerkleIterator<?> it, final List<ExpectedNode> expectedNodes) {
        int count = 0;
        for (final ExpectedNode expectedNode : expectedNodes) {
            assertTrue(it.hasNext(), "iterator should not be finished");
            MerkleNode next = it.next();
            expectedNode.assertNodeMatches(next, it.getRoute());
            count++;
        }
        assertEquals(expectedNodes.size(), count, "count should match expected count");
        assertFalse(it.hasNext(), "iterator should be depleted");
        assertThrows(NoSuchElementException.class, it::next, "iterator should throw proper exception");
    }

    /**
     * A tree with no nodes is a valid root. Verify that iterators do not choke on an empty root.
     */
    @Test
    @Tag(TestComponentTags.MERKLE)
    @DisplayName("Iterate Empty Tree Depth-First Post-Ordered")
    void iterateEmptyTreeDepthFirstPostOrdered() {
        final MerkleNode root = MerkleTestUtils.buildSizeZeroTree();

        final List<ExpectedNode> sequence = new LinkedList<>();
        assertIterationOrder(new MerkleIterator<>(root).setOrder(POST_ORDERED_DEPTH_FIRST), sequence);
    }

    /**
     * A tree with no nodes is a valid root. Verify that iterators do not choke on an empty root.
     */
    @Test
    @Tag(TestComponentTags.MERKLE)
    @DisplayName("Iterate Empty Tree Reverse Depth-First Post-Ordered")
    void iterateEmptyTreeReverseDepthFirstPostOrdered() {
        final MerkleNode root = MerkleTestUtils.buildSizeZeroTree();

        final List<ExpectedNode> sequence = new LinkedList<>();
        assertIterationOrder(new MerkleIterator<>(root).setOrder(REVERSE_POST_ORDERED_DEPTH_FIRST), sequence);
    }

    /**
     * A tree with no nodes is a valid root. Verify that iterators do not choke on an empty root.
     */
    @Test
    @Tag(TestComponentTags.MERKLE)
    @DisplayName("Iterate Empty Tree Depth-First Pre-Ordered")
    void iterateEmptyTreeDepthFirstPreOrdered() {
        final MerkleNode root = MerkleTestUtils.buildSizeZeroTree();

        final List<ExpectedNode> sequence = new LinkedList<>();
        assertIterationOrder(new MerkleIterator<>(root).setOrder(PRE_ORDERED_DEPTH_FIRST), sequence);
    }

    /**
     * A tree with no nodes is a valid root. Verify that iterators do not choke on an empty root.
     */
    @Test
    @Tag(TestComponentTags.MERKLE)
    @DisplayName("Iterate Empty Tree Breadth First")
    void iterateEmptyTreeBreadthFirst() {
        final MerkleNode root = MerkleTestUtils.buildSizeZeroTree();

        final List<ExpectedNode> sequence = new LinkedList<>();
        assertIterationOrder(new MerkleIterator<>(root).setOrder(BREADTH_FIRST), sequence);
    }

    /**
     * Ensure that a MerkleIterator can handle a tree with only a single node.
     */
    @Test
    @Tag(TestComponentTags.MERKLE)
    @DisplayName("Iterate Single Node Tree Depth-First Post-Ordered")
    void iterateSingleNodeTreeDepthFirstPostOrdered() {
        final MerkleNode root = MerkleTestUtils.buildSizeOneTree();

        final List<ExpectedNode> sequence = new LinkedList<>();
        sequence.add(new ExpectedNode(false, "A"));

        assertIterationOrder(root.treeIterator(), sequence);
    }

    /**
     * Ensure that a MerkleIterator can handle a tree with only a single node.
     */
    @Test
    @Tag(TestComponentTags.MERKLE)
    @DisplayName("Iterate Single Node Tree Reverse Depth-First Post-Ordered")
    void iterateSingleNodeTreeReverseDepthFirstPostOrdered() {
        final MerkleNode root = MerkleTestUtils.buildSizeOneTree();

        final List<ExpectedNode> sequence = new LinkedList<>();
        sequence.add(new ExpectedNode(false, "A"));

        assertIterationOrder(root.treeIterator().setOrder(REVERSE_POST_ORDERED_DEPTH_FIRST), sequence);
    }

    /**
     * Ensure that a MerkleIterator can handle a tree with only a single node.
     */
    @Test
    @Tag(TestComponentTags.MERKLE)
    @DisplayName("Iterate Single Node Tree Depth-First Pre-Ordered")
    void iterateSingleNodeTreeDepthFirstPreOrdered() {
        final MerkleNode root = MerkleTestUtils.buildSizeOneTree();

        final List<ExpectedNode> sequence = new LinkedList<>();
        sequence.add(new ExpectedNode(false, "A"));

        assertIterationOrder(root.treeIterator().setOrder(PRE_ORDERED_DEPTH_FIRST), sequence);
    }

    @Test
    @Tag(TestComponentTags.MERKLE)
    @DisplayName("Iterate Single Node Tree Breadth First")
    void iterateSingleNodeTreeBreadthFirst() {
        final MerkleNode root = MerkleTestUtils.buildSizeOneTree();

        final List<ExpectedNode> sequence = new LinkedList<>();
        sequence.add(new ExpectedNode(false, "A"));

        assertIterationOrder(root.treeIterator().setOrder(BREADTH_FIRST), sequence);
    }

    @Test
    @Tag(TestComponentTags.MERKLE)
    @DisplayName("Iterate Simple Tree Depth-First Post-Ordered")
    void iterateSimpleTreeDepthFirstPostOrdered() {
        final MerkleNode root = MerkleTestUtils.buildSimpleTree();

        final List<ExpectedNode> sequence = new LinkedList<>();
        sequence.add(new ExpectedNode(false, "A"));
        sequence.add(new ExpectedNode(true, "root"));
        assertIterationOrder(root.treeIterator(), sequence);
    }

    @Test
    @Tag(TestComponentTags.MERKLE)
    @DisplayName("Iterate Simple Tree Reverse Depth-First Post-Ordered")
    void iterateSimpleTreeReverseDepthFirstPostOrdered() {
        final MerkleNode root = MerkleTestUtils.buildSimpleTree();

        final List<ExpectedNode> sequence = new LinkedList<>();
        sequence.add(new ExpectedNode(false, "A"));
        sequence.add(new ExpectedNode(true, "root"));
        assertIterationOrder(root.treeIterator().setOrder(REVERSE_POST_ORDERED_DEPTH_FIRST), sequence);
    }

    @Test
    @Tag(TestComponentTags.MERKLE)
    @DisplayName("Iterate Simple Tree Depth-First Pre-Ordered")
    void iterateSimpleTreeDepthFirstPreOrdered() {
        final MerkleNode root = MerkleTestUtils.buildSimpleTree();

        final List<ExpectedNode> sequence = new LinkedList<>();
        sequence.add(new ExpectedNode(true, "root"));
        sequence.add(new ExpectedNode(false, "A"));
        assertIterationOrder(root.treeIterator().setOrder(PRE_ORDERED_DEPTH_FIRST), sequence);
    }

    @Test
    @Tag(TestComponentTags.MERKLE)
    @DisplayName("Iterate Simple Tree Breadth First")
    void iterateSimpleTreeBreadthFirst() {
        final MerkleNode root = MerkleTestUtils.buildSimpleTree();

        final List<ExpectedNode> sequence = new LinkedList<>();
        sequence.add(new ExpectedNode(true, "root"));
        sequence.add(new ExpectedNode(false, "A"));
        assertIterationOrder(root.treeIterator().setOrder(BREADTH_FIRST), sequence);
    }

    @Test
    @Tag(TestComponentTags.MERKLE)
    @DisplayName("Iterate Less Simple Tree Depth-First Post-Ordered")
    void iterateLessSimpleTreeDepthFirstPostOrdered() {
        final MerkleNode root = MerkleTestUtils.buildLessSimpleTree();

        // The standard iterator will visit each node except for the null value
        final List<ExpectedNode> sequence = new LinkedList<>();
        sequence.add(new ExpectedNode(false, "A"));
        sequence.add(new ExpectedNode(false, "B"));
        sequence.add(new ExpectedNode(false, "C"));
        sequence.add(new ExpectedNode(true, "i0"));
        sequence.add(new ExpectedNode(false, "D"));
        sequence.add(new ExpectedNode(true, "i1"));
        sequence.add(new ExpectedNode(true, "root"));
        assertIterationOrder(root.treeIterator(), sequence);
    }

    @Test
    @Tag(TestComponentTags.MERKLE)
    @DisplayName("Iterate Less Simple Tree Reverse Depth-First Post-Ordered")
    void iterateLessSimpleTreeReverseDepthFirstPostOrdered() {
        final MerkleNode root = MerkleTestUtils.buildLessSimpleTree();

        // The standard iterator will visit each node except for the null value
        final List<ExpectedNode> sequence = new LinkedList<>();
        sequence.add(new ExpectedNode(false, "D"));
        sequence.add(new ExpectedNode(true, "i1"));
        sequence.add(new ExpectedNode(false, "C"));
        sequence.add(new ExpectedNode(false, "B"));
        sequence.add(new ExpectedNode(true, "i0"));
        sequence.add(new ExpectedNode(false, "A"));
        sequence.add(new ExpectedNode(true, "root"));
        assertIterationOrder(root.treeIterator().setOrder(REVERSE_POST_ORDERED_DEPTH_FIRST), sequence);
    }

    @Test
    @Tag(TestComponentTags.MERKLE)
    @DisplayName("Iterate Less Simple Tree Depth-First Pre-Ordered")
    void iterateLessSimpleTreeDepthFirstPreOrdered() {
        final MerkleNode root = MerkleTestUtils.buildLessSimpleTree();

        // The standard iterator will visit each node except for the null value
        final List<ExpectedNode> sequence = new LinkedList<>();
        sequence.add(new ExpectedNode(true, "root"));
        sequence.add(new ExpectedNode(false, "A"));
        sequence.add(new ExpectedNode(true, "i0"));
        sequence.add(new ExpectedNode(false, "B"));
        sequence.add(new ExpectedNode(false, "C"));
        sequence.add(new ExpectedNode(true, "i1"));
        sequence.add(new ExpectedNode(false, "D"));
        assertIterationOrder(root.treeIterator().setOrder(PRE_ORDERED_DEPTH_FIRST), sequence);
    }

    @Test
    @Tag(TestComponentTags.MERKLE)
    @DisplayName("Do Not Ignore Null Depth-First Post-Ordered")
    void doNotIgnoreNullDepthFirstPostOrdered() {
        final MerkleNode root = MerkleTestUtils.buildLessSimpleTree();

        final List<ExpectedNode> sequence = new LinkedList<>();
        sequence.add(new ExpectedNode(false, "A"));
        sequence.add(new ExpectedNode(false, "B"));
        sequence.add(new ExpectedNode(false, "C"));
        sequence.add(new ExpectedNode(true, "i0"));
        sequence.add(new ExpectedNode(false, "D"));
        sequence.add(new ExpectedNode(false, null, buildRoute(2, 1)));
        sequence.add(new ExpectedNode(true, "i1"));
        sequence.add(new ExpectedNode(true, "root"));
        assertIterationOrder(root.treeIterator().ignoreNull(false), sequence);
    }

    @Test
    @Tag(TestComponentTags.MERKLE)
    @DisplayName("Do Not Ignore Null Reverse Depth-First Post-Ordered")
    void doNotIgnoreNullReverseDepthFirstPostOrdered() {
        final MerkleNode root = MerkleTestUtils.buildLessSimpleTree();

        final List<ExpectedNode> sequence = new LinkedList<>();
        sequence.add(new ExpectedNode(false, null, buildRoute(2, 1)));
        sequence.add(new ExpectedNode(false, "D"));
        sequence.add(new ExpectedNode(true, "i1"));
        sequence.add(new ExpectedNode(false, "C"));
        sequence.add(new ExpectedNode(false, "B"));
        sequence.add(new ExpectedNode(true, "i0"));
        sequence.add(new ExpectedNode(false, "A"));
        sequence.add(new ExpectedNode(true, "root"));
        assertIterationOrder(
                root.treeIterator().ignoreNull(false).setOrder(REVERSE_POST_ORDERED_DEPTH_FIRST), sequence);
    }

    @Test
    @Tag(TestComponentTags.MERKLE)
    @DisplayName("Do Not Ignore Null Depth-First Pre-Ordered")
    void doNotIgnoreNullDepthFirstPreOrdered() {
        final MerkleNode root = MerkleTestUtils.buildLessSimpleTree();
        root.asInternal().setChild(3, null);

        final List<ExpectedNode> sequence = new LinkedList<>();
        sequence.add(new ExpectedNode(true, "root"));
        sequence.add(new ExpectedNode(false, "A"));
        sequence.add(new ExpectedNode(true, "i0"));
        sequence.add(new ExpectedNode(false, "B"));
        sequence.add(new ExpectedNode(false, "C"));
        sequence.add(new ExpectedNode(true, "i1"));
        sequence.add(new ExpectedNode(false, "D"));
        sequence.add(new ExpectedNode(false, null, buildRoute(2, 1)));
        sequence.add(new ExpectedNode(false, null, buildRoute(3)));
        assertIterationOrder(
                root.treeIterator().setOrder(PRE_ORDERED_DEPTH_FIRST).ignoreNull(false), sequence);
    }

    @Test
    @Tag(TestComponentTags.MERKLE)
    @DisplayName("Iterate Less Simple Tree Breadth First")
    void iterateLessSimpleTreeBreadthFirst() {
        final MerkleNode root = MerkleTestUtils.buildLessSimpleTree();

        final List<ExpectedNode> sequence = new LinkedList<>();
        sequence.add(new ExpectedNode(true, "root"));
        sequence.add(new ExpectedNode(false, "A"));
        sequence.add(new ExpectedNode(true, "i0"));
        sequence.add(new ExpectedNode(true, "i1"));
        sequence.add(new ExpectedNode(false, "B"));
        sequence.add(new ExpectedNode(false, "C"));
        sequence.add(new ExpectedNode(false, "D"));
        assertIterationOrder(root.treeIterator().setOrder(BREADTH_FIRST), sequence);
    }

    @Test
    @Tag(TestComponentTags.MERKLE)
    @DisplayName("Do Not Ignore Null Breadth First")
    void doNotIgnoreNullBreadthFirst() {
        final MerkleNode root = MerkleTestUtils.buildLessSimpleTree();
        root.asInternal().setChild(3, null);

        final List<ExpectedNode> sequence = new LinkedList<>();
        sequence.add(new ExpectedNode(true, "root"));
        sequence.add(new ExpectedNode(false, "A"));
        sequence.add(new ExpectedNode(true, "i0"));
        sequence.add(new ExpectedNode(true, "i1"));
        sequence.add(new ExpectedNode(false, null, buildRoute(3)));
        sequence.add(new ExpectedNode(false, "B"));
        sequence.add(new ExpectedNode(false, "C"));
        sequence.add(new ExpectedNode(false, "D"));
        sequence.add(new ExpectedNode(false, null, buildRoute(2, 1)));
        assertIterationOrder(root.treeIterator().setOrder(BREADTH_FIRST).ignoreNull(false), sequence);
    }

    @Test
    @Tag(TestComponentTags.MERKLE)
    @DisplayName("Leaf Filter Depth-First Post-Ordered")
    void leafFilterDepthPostOrderedFirst() {
        final MerkleNode root = MerkleTestUtils.buildLessSimpleTree();

        final List<ExpectedNode> sequence = new LinkedList<>();
        sequence.add(new ExpectedNode(false, "A"));
        sequence.add(new ExpectedNode(false, "B"));
        sequence.add(new ExpectedNode(false, "C"));
        sequence.add(new ExpectedNode(false, "D"));

        assertIterationOrder(root.treeIterator().setFilter(MerkleNode::isLeaf), sequence);
    }

    @Test
    @Tag(TestComponentTags.MERKLE)
    @DisplayName("Leaf Filter Depth-First Pre-Ordered")
    void leafFilterDepthPreOrderedFirst() {
        final MerkleNode root = MerkleTestUtils.buildLessSimpleTree();

        final List<ExpectedNode> sequence = new LinkedList<>();
        sequence.add(new ExpectedNode(false, "A"));
        sequence.add(new ExpectedNode(false, "B"));
        sequence.add(new ExpectedNode(false, "C"));
        sequence.add(new ExpectedNode(false, "D"));

        assertIterationOrder(
                root.treeIterator().setOrder(PRE_ORDERED_DEPTH_FIRST).setFilter(MerkleNode::isLeaf), sequence);
    }

    @Test
    @Tag(TestComponentTags.MERKLE)
    @DisplayName("Leaf Filter Include Null Depth-First Post-Ordered")
    void leafFilterIncludeNullDepthPostOrderedFirst() {
        final MerkleNode root = MerkleTestUtils.buildLessSimpleTree();
        root.asInternal().setChild(3, null);

        final List<ExpectedNode> sequence = new LinkedList<>();
        sequence.add(new ExpectedNode(false, "A"));
        sequence.add(new ExpectedNode(false, "B"));
        sequence.add(new ExpectedNode(false, "C"));
        sequence.add(new ExpectedNode(false, "D"));
        sequence.add(new ExpectedNode(false, null, buildRoute(2, 1)));
        sequence.add(new ExpectedNode(false, null, buildRoute(3)));

        assertIterationOrder(
                root.treeIterator().ignoreNull(false).setFilter(node -> node == null || node.isLeaf()), sequence);
    }

    @Test
    @Tag(TestComponentTags.MERKLE)
    @DisplayName("Leaf Filter Breadth First")
    void leafFilterBreadthFirst() {
        final MerkleNode root = MerkleTestUtils.buildLessSimpleTree();

        final List<ExpectedNode> sequence = new LinkedList<>();
        sequence.add(new ExpectedNode(false, "A"));
        sequence.add(new ExpectedNode(false, "B"));
        sequence.add(new ExpectedNode(false, "C"));
        sequence.add(new ExpectedNode(false, "D"));

        assertIterationOrder(root.treeIterator().setOrder(BREADTH_FIRST).setFilter(MerkleNode::isLeaf), sequence);
    }

    @Test
    @Tag(TestComponentTags.MERKLE)
    @DisplayName("Leaf Filter Include Null Breadth First")
    void leafFilterIncludeNullBreadthFirst() {
        final MerkleNode root = MerkleTestUtils.buildLessSimpleTree();
        root.asInternal().setChild(3, null);

        final List<ExpectedNode> sequence = new LinkedList<>();
        sequence.add(new ExpectedNode(false, "A"));
        sequence.add(new ExpectedNode(false, null, buildRoute(3)));
        sequence.add(new ExpectedNode(false, "B"));
        sequence.add(new ExpectedNode(false, "C"));
        sequence.add(new ExpectedNode(false, "D"));
        sequence.add(new ExpectedNode(false, null, buildRoute(2, 1)));

        assertIterationOrder(
                root.treeIterator()
                        .setOrder(BREADTH_FIRST)
                        .ignoreNull(false)
                        .setFilter(node -> node == null || node.isLeaf()),
                sequence);
    }

    @Test
    @Tag(TestComponentTags.MERKLE)
    @DisplayName("Targeted Filter")
    void targetedFilter() {
        final MerkleNode root = MerkleTestUtils.buildLessSimpleTree();

        final List<ExpectedNode> sequence = new LinkedList<>();
        sequence.add(new ExpectedNode(false, "C"));

        assertIterationOrder(
                root.treeIterator()
                        .setFilter(node -> ((DummyMerkleNode) node).getValue().equals("C")),
                sequence);
    }

    @Test
    @Tag(TestComponentTags.MERKLE)
    @DisplayName("Descendant Filter Depth-First Post-Ordered")
    void descendantFilterDepthFirstPostOrdered() {
        final MerkleNode root = MerkleTestUtils.buildLessSimpleTree();

        // The standard iterator will visit each node except for the null value
        final List<ExpectedNode> sequence = new LinkedList<>();
        sequence.add(new ExpectedNode(false, "A"));
        sequence.add(new ExpectedNode(true, "i0"));
        sequence.add(new ExpectedNode(false, "D"));
        sequence.add(new ExpectedNode(true, "i1"));
        sequence.add(new ExpectedNode(true, "root"));
        assertIterationOrder(
                root.treeIterator()
                        .setDescendantFilter(
                                node -> !((DummyMerkleNode) node).getValue().equals("i0")),
                sequence);
    }

    @Test
    @Tag(TestComponentTags.MERKLE)
    @DisplayName("Descendant Filter Depth-First Pre-Ordered")
    void descendantFilterDepthFirstPreOrdered() {
        final MerkleNode root = MerkleTestUtils.buildLessSimpleTree();

        // The standard iterator will visit each node except for the null value
        final List<ExpectedNode> sequence = new LinkedList<>();
        sequence.add(new ExpectedNode(true, "root"));
        sequence.add(new ExpectedNode(false, "A"));
        sequence.add(new ExpectedNode(true, "i0"));
        sequence.add(new ExpectedNode(true, "i1"));
        sequence.add(new ExpectedNode(false, "D"));
        assertIterationOrder(
                root.treeIterator()
                        .setOrder(PRE_ORDERED_DEPTH_FIRST)
                        .setDescendantFilter(
                                node -> !((DummyMerkleNode) node).getValue().equals("i0")),
                sequence);
    }

    @Test
    @Tag(TestComponentTags.MERKLE)
    @DisplayName("Descendant Filter Breadth First")
    void descendantFilterBreadthFirst() {
        final MerkleNode root = MerkleTestUtils.buildLessSimpleTree();

        // The standard iterator will visit each node except for the null value
        final List<ExpectedNode> sequence = new LinkedList<>();
        sequence.add(new ExpectedNode(true, "root"));
        sequence.add(new ExpectedNode(false, "A"));
        sequence.add(new ExpectedNode(true, "i0"));
        sequence.add(new ExpectedNode(true, "i1"));
        sequence.add(new ExpectedNode(false, "D"));
        assertIterationOrder(
                root.treeIterator().setOrder(BREADTH_FIRST).setDescendantFilter(node -> !((DummyMerkleNode) node)
                        .getValue()
                        .equals("i0")),
                sequence);
    }

    @Test
    @Tag(TestComponentTags.MERKLE)
    @DisplayName("Filter And Descendant Filter Depth-First Post-Ordered")
    void filterAndDescendantFilterDepthFirstPostOrdered() {
        final MerkleNode root = MerkleTestUtils.buildLessSimpleTree();

        // The standard iterator will visit each node except for the null value
        final List<ExpectedNode> sequence = new LinkedList<>();
        sequence.add(new ExpectedNode(true, "i0"));
        sequence.add(new ExpectedNode(false, "D"));
        sequence.add(new ExpectedNode(true, "i1"));
        sequence.add(new ExpectedNode(true, "root"));
        assertIterationOrder(
                root.treeIterator()
                        .setFilter(node -> !((DummyMerkleNode) node).getValue().equals("A"))
                        .setDescendantFilter(
                                node -> !((DummyMerkleNode) node).getValue().equals("i0")),
                sequence);
    }

    @Test
    @Tag(TestComponentTags.MERKLE)
    @DisplayName("Filter And Descendant Filter Depth-First Pre-Ordered")
    void filterAndDescendantFilterDepthFirstPreOrdered() {
        final MerkleNode root = MerkleTestUtils.buildLessSimpleTree();

        // The standard iterator will visit each node except for the null value
        final List<ExpectedNode> sequence = new LinkedList<>();
        sequence.add(new ExpectedNode(true, "root"));
        sequence.add(new ExpectedNode(true, "i0"));
        sequence.add(new ExpectedNode(true, "i1"));
        sequence.add(new ExpectedNode(false, "D"));
        assertIterationOrder(
                root.treeIterator()
                        .setOrder(PRE_ORDERED_DEPTH_FIRST)
                        .setFilter(node -> !((DummyMerkleNode) node).getValue().equals("A"))
                        .setDescendantFilter(
                                node -> !((DummyMerkleNode) node).getValue().equals("i0")),
                sequence);
    }

    @Test
    @Tag(TestComponentTags.MERKLE)
    @DisplayName("Filter And Descendant Filter Breadth First")
    void filterAndDescendantFilterBreadthFirst() {
        final MerkleNode root = MerkleTestUtils.buildLessSimpleTree();

        final List<ExpectedNode> sequence = new LinkedList<>();
        sequence.add(new ExpectedNode(true, "root"));
        sequence.add(new ExpectedNode(true, "i0"));
        sequence.add(new ExpectedNode(true, "i1"));
        sequence.add(new ExpectedNode(false, "D"));
        assertIterationOrder(
                root.treeIterator()
                        .setOrder(BREADTH_FIRST)
                        .setFilter(node -> !((DummyMerkleNode) node).getValue().equals("A"))
                        .setDescendantFilter(
                                node -> !((DummyMerkleNode) node).getValue().equals("i0")),
                sequence);
    }

    @Test
    @Tag(TestComponentTags.MERKLE)
    @DisplayName("Transformation Test")
    void transformationTest() {
        final MerkleNode root = MerkleTestUtils.buildLessSimpleTree();

        final List<String> sequence = new LinkedList<>();
        sequence.add("A");
        sequence.add("B");
        sequence.add("C");
        sequence.add("i0");
        sequence.add("D");
        sequence.add("i1");
        sequence.add("root");

        final Iterator<String> iterator = root.treeIterator().transform(node -> ((DummyMerkleNode) node).getValue());

        final List<String> actualSequence = new LinkedList<>();
        iterator.forEachRemaining(actualSequence::add);

        assertEquals(sequence, actualSequence, "lists should contain same entries");
    }

    @Test
    @Tag(TestComponentTags.MERKLE)
    @DisplayName("Null Transformation Test")
    void nullTransformationTest() {
        final MerkleNode root = MerkleTestUtils.buildLessSimpleTree();

        final List<MerkleRoute> sequence = new LinkedList<>();
        sequence.add(buildRoute(0));
        sequence.add(buildRoute(1, 0));
        sequence.add(buildRoute(1, 1));
        sequence.add(buildRoute(1));
        sequence.add(buildRoute(2, 0));
        sequence.add(buildRoute(2, 1));
        sequence.add(buildRoute(2));
        sequence.add(buildRoute());

        // Transform nodes into merkle routes. One of the nodes that needs to be transformed is null.

        final Iterator<MerkleRoute> iterator = root.treeIterator()
                .ignoreNull(false)
                .transform((final MerkleNode node, final MerkleRoute route) -> route);

        final List<MerkleRoute> actualSequence = new LinkedList<>();
        iterator.forEachRemaining(actualSequence::add);

        assertEquals(sequence, actualSequence, "lists should contain same entries");
    }

    @Test
    @Tag(TestComponentTags.MERKLE)
    @DisplayName("Null Filter Test")
    void nullFilterTest() {
        final MerkleNode root = MerkleTestUtils.buildLessSimpleTree();

        final List<MerkleRoute> sequence = new LinkedList<>();
        sequence.add(buildRoute(1, 0));
        sequence.add(buildRoute(1, 1));
        sequence.add(buildRoute(2, 0));
        sequence.add(buildRoute(2, 1));

        // Filter out all nodes that do not have a route of size 2. One of the nodes with a route of size 2 is null.

        final Iterator<MerkleRoute> iterator = root.treeIterator()
                .ignoreNull(false)
                .setFilter((final MerkleNode node, final MerkleRoute route) -> route.size() == 2)
                .transform((final MerkleNode node, final MerkleRoute route) -> route);

        final List<MerkleRoute> actualSequence = new LinkedList<>();
        iterator.forEachRemaining(actualSequence::add);

        assertEquals(sequence, actualSequence, "lists should contain same entries");
    }

    @Test
    @DisplayName("forEachRemainingWithIO Test")
    void forEachRemainingWithIOTest() throws IOException {
        final MerkleNode root = MerkleTestUtils.buildLessSimpleTree();

        // Make sure the iterator visits things correctly
        final MerkleIterator<MerkleNode> it1 = root.treeIterator();
        final MerkleIterator<MerkleNode> it2 = root.treeIterator();

        it1.forEachRemainingWithIO(
                (final MerkleNode node) -> assertSame(it2.next(), node, "node should match regular iterator"));

        // Make sure exception is propagated
        final IOException exception = new IOException();

        try {
            root.treeIterator().forEachRemainingWithIO((final MerkleNode node) -> {
                throw exception;
            });
        } catch (final IOException e) {
            assertSame(exception, e, "exception should have been rethrown");
        }
    }

    @Test
    @DisplayName("forEachRemainingWithInterrupt Test")
    void forEachRemainingWithInterruptTest() throws InterruptedException {
        final MerkleNode root = MerkleTestUtils.buildLessSimpleTree();

        // Make sure the iterator visits things correctly
        final MerkleIterator<MerkleNode> it1 = root.treeIterator();
        final MerkleIterator<MerkleNode> it2 = root.treeIterator();

        it1.forEachRemainingWithInterrupt(
                (final MerkleNode node) -> assertSame(it2.next(), node, "node should match regular iterator"));

        // Make sure exception is propagated
        final InterruptedException exception = new InterruptedException();

        try {
            root.treeIterator().forEachRemainingWithInterrupt((final MerkleNode node) -> {
                throw exception;
            });
        } catch (final InterruptedException e) {
            assertSame(exception, e, "exception should have been rethrown");
        }
    }
}
