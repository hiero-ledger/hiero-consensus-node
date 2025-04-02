// SPDX-License-Identifier: Apache-2.0
package com.swirlds.merkle.test;

import static com.swirlds.common.merkle.iterators.MerkleIterationOrder.BREADTH_FIRST;
import static com.swirlds.common.merkle.route.MerkleRouteFactory.buildRoute;
import static com.swirlds.common.merkle.route.MerkleRouteFactory.getEmptyRoute;
import static com.swirlds.common.merkle.route.MerkleRouteFactory.setRouteEncodingStrategy;
import static com.swirlds.common.merkle.route.MerkleRouteUtils.merkleRouteToPathFormat;
import static com.swirlds.common.merkle.route.MerkleRouteUtils.pathFormatToMerkleRoute;
import static org.hiero.consensus.utility.test.fixtures.RandomUtils.getRandomPrintSeed;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.swirlds.common.merkle.MerkleNode;
import com.swirlds.common.merkle.iterators.MerkleIterator;
import com.swirlds.common.merkle.route.MerkleRoute;
import com.swirlds.common.merkle.route.MerkleRouteFactory;
import com.swirlds.common.merkle.route.MerkleRouteIterator;
import com.swirlds.common.merkle.route.ReverseMerkleRouteIterator;
import com.swirlds.common.test.fixtures.junit.tags.TestComponentTags;
import com.swirlds.common.test.fixtures.merkle.dummy.DummyMerkleNode;
import com.swirlds.common.test.fixtures.merkle.util.MerkleTestUtils;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Random;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

@DisplayName("Merkle Route Tests")
class MerkleRouteTests {

    protected static Stream<Arguments> buildArguments() {
        final List<Arguments> arguments = new ArrayList<>();

        arguments.add(Arguments.of(MerkleRouteFactory.MerkleRouteEncoding.BINARY_COMPRESSION));
        arguments.add(Arguments.of(MerkleRouteFactory.MerkleRouteEncoding.UNCOMPRESSED));

        return arguments.stream();
    }

    @AfterAll
    static void afterAll() {
        // Reset encoding for other tests
        setRouteEncodingStrategy(MerkleRouteFactory.MerkleRouteEncoding.BINARY_COMPRESSION);
    }

    /**
     * Given an assortment of trees, make sure that routes can be found to each node.
     */
    @ParameterizedTest
    @MethodSource("buildArguments")
    @Tag(TestComponentTags.MERKLE)
    @DisplayName("Iterator Test")
    void iteratorTest(final MerkleRouteFactory.MerkleRouteEncoding encoding) {
        setRouteEncodingStrategy(encoding);

        final List<DummyMerkleNode> nodes = MerkleTestUtils.buildTreeList();

        for (MerkleNode root : nodes) {
            System.out.println(root);
            final Iterator<MerkleNode> nodeIterator = new MerkleIterator<>(root);

            while (nodeIterator.hasNext()) {
                final MerkleNode node = nodeIterator.next();
                if (node == null) {
                    continue;
                }
                final MerkleRouteIterator routeIterator = new MerkleRouteIterator(root, node.getRoute());
                MerkleNode next = root;
                while (routeIterator.hasNext()) {
                    next = routeIterator.next();
                }
                assertSame(node, next, "objects should be the same");
            }
        }
    }

    /**
     * Validate functionality of MerkleRoute.getNodeAtRoute().
     */
    @ParameterizedTest
    @MethodSource("buildArguments")
    @Tag(TestComponentTags.MERKLE)
    @DisplayName("Get Node At Route Test")
    void getNodeAtRouteTest(final MerkleRouteFactory.MerkleRouteEncoding encoding) {
        setRouteEncodingStrategy(encoding);

        final List<DummyMerkleNode> nodes = MerkleTestUtils.buildTreeList();

        for (MerkleNode root : nodes) {
            Iterator<MerkleNode> nodeIterator = new MerkleIterator<>(root);

            while (nodeIterator.hasNext()) {
                final MerkleNode node = nodeIterator.next();
                if (node == null) {
                    continue;
                }
                final MerkleNode nodeAtRoute = root.getNodeAtRoute(node.getRoute());
                assertSame(node, nodeAtRoute, "objects should be the same");
            }
        }
    }

    /**
     * Given an assortment of trees, make sure that routes can be found from each node up to the root.
     */
    @ParameterizedTest
    @MethodSource("buildArguments")
    @Tag(TestComponentTags.MERKLE)
    @DisplayName("ReverseIteratorTest")
    void reverseIteratorTest(final MerkleRouteFactory.MerkleRouteEncoding encoding) {
        setRouteEncodingStrategy(encoding);

        List<DummyMerkleNode> nodes = MerkleTestUtils.buildTreeList();

        for (MerkleNode root : nodes) {
            final Iterator<MerkleNode> nodeIterator = new MerkleIterator<>(root);

            while (nodeIterator.hasNext()) {
                final MerkleNode node = nodeIterator.next();
                if (node == null) {
                    continue;
                }
                final Iterator<MerkleNode> routeIterator = new ReverseMerkleRouteIterator(root, node.getRoute());

                MerkleNode next = null;
                while (routeIterator.hasNext()) {
                    next = routeIterator.next();
                }

                assertSame(next, root, "objects should be the same");
            }
        }
    }

    /**
     * Ensure that the values written into a route are the same when read back out of it.
     */
    @ParameterizedTest
    @MethodSource("buildArguments")
    @Tag(TestComponentTags.MERKLE)
    @DisplayName("Route Fidelity Test")
    void routeFidelityTest(final MerkleRouteFactory.MerkleRouteEncoding encoding) {
        setRouteEncodingStrategy(encoding);

        final List<Integer> steps = new LinkedList<>();
        steps.add(0);
        steps.add(1);
        steps.add(0);
        steps.add(1);
        steps.add(3);
        steps.add(123456);
        steps.add(1);
        steps.add(1);
        steps.add(0);
        steps.add(7);
        final Random random = new Random(1234);
        for (int i = 0; i < 1000; i++) {
            steps.add(random.nextBoolean() ? 1 : 0);
        }
        steps.add(9);
        steps.add(0);

        MerkleRoute route = getEmptyRoute();
        for (final int step : steps) {
            route = route.extendRoute(step);
        }

        final List<Integer> decodedSteps = new ArrayList<>(steps.size());
        for (int i : route) {
            decodedSteps.add(i);
        }

        assertEquals(steps.size(), decodedSteps.size(), "route size is not the same");
        assertEquals(steps, decodedSteps, "steps are not the same");

        // Since we have a route already constructed and a convenient list of steps, do a "bonus"
        // test using the methods that build a route all at once instead of iteratively
        testAddToRoute(route, steps);
    }

    /**
     * Check to see if a node is the ancestor to another. Does not use merkle routes to do check.
     */
    private boolean isAncestor(final MerkleNode ancestor, final MerkleNode descendant) {
        final Iterator<MerkleNode> iterator = new MerkleIterator<>(ancestor).setOrder(BREADTH_FIRST);
        while (iterator.hasNext()) {
            final MerkleNode next = iterator.next();
            if (next == descendant) {
                return true;
            }
        }
        return false;
    }

    /**
     * Check to see if a node is to the left of another. Does not use merkle routes.
     */
    private boolean isToTheLeftOf(final MerkleNode root, final MerkleNode node1, final MerkleNode node2) {
        // An ancestor is not the the left or right of the node
        if (isAncestor(node1, node2) || isAncestor(node2, node1)) {
            return false;
        }

        // If the node1 is to the left then we should encounter it before we encounter node2
        final Iterator<MerkleNode> iterator = new MerkleIterator<>(root);
        while (iterator.hasNext()) {
            final MerkleNode next = iterator.next();
            if (next == node1) {
                return true;
            } else if (next == node2) {
                return false;
            }
        }
        fail("Nodes not found in tree");
        return false;
    }

    private void compareRoutesInTree(final MerkleNode tree) {
        // Iterate through each n^2 combination of nodes
        final Iterator<MerkleNode> iterator1 = new MerkleIterator<>(tree).setOrder(BREADTH_FIRST);
        while (iterator1.hasNext()) {
            final MerkleNode node1 = iterator1.next();
            if (node1 == null) {
                continue;
            }

            final Iterator<MerkleNode> iterator2 = new MerkleIterator<>(tree).setOrder(BREADTH_FIRST);
            while (iterator2.hasNext()) {
                final MerkleNode node2 = iterator2.next();
                if (node2 == null) {
                    continue;
                }

                final int comparison = node1.getRoute().compareTo(node2.getRoute());

                if (comparison == 0) {
                    // If comparison is equal, then node1 should equal node2
                    // or one should be the ancestor of the other
                    if (node1 != node2 && !isAncestor(node1, node2) && !isAncestor(node2, node1)) {
                        fail("neither node is an ancestor of the other");
                    }
                } else if (comparison == -1) {
                    // node 1 should be to the left of node 2
                    assertTrue(isToTheLeftOf(tree, node1, node2), "node1 is not to the left of node2");
                } else {
                    // node 1 should be to the right of node 2
                    assertTrue(isToTheLeftOf(tree, node2, node1), "node2 is not to the left of node1");
                }
            }
        }
    }

    /**
     * Validate route comparison. Validation is done via different (much less efficient) implementation of route
     * comparison.
     */
    @ParameterizedTest
    @MethodSource("buildArguments")
    @Tag(TestComponentTags.MERKLE)
    @DisplayName("Route Comparison Test")
    void routeComparisonTest(final MerkleRouteFactory.MerkleRouteEncoding encoding) {
        setRouteEncodingStrategy(encoding);

        final List<DummyMerkleNode> trees = MerkleTestUtils.buildSmallTreeList();
        for (final MerkleNode tree : trees) {
            compareRoutesInTree(tree);
        }
    }

    /**
     * Same as routeComparisonTest() but with a much larger tree.
     */
    @ParameterizedTest
    @MethodSource("buildArguments")
    @Tag(TestComponentTags.MERKLE)
    @DisplayName("Route Large Comparison Test")
    void routeLargeComparisonTest(final MerkleRouteFactory.MerkleRouteEncoding encoding) {
        setRouteEncodingStrategy(encoding);

        final MerkleNode tree = MerkleTestUtils.generateRandomTree(0, 2, 1, 1, 0, 3, 1, 0.25);
        compareRoutesInTree(tree);
    }

    @ParameterizedTest
    @MethodSource("buildArguments")
    @Tag(TestComponentTags.MERKLE)
    @DisplayName("Route Ancestry Test")
    void routeAncestryTest(final MerkleRouteFactory.MerkleRouteEncoding encoding) {
        setRouteEncodingStrategy(encoding);

        final List<DummyMerkleNode> trees = MerkleTestUtils.buildSmallTreeList();
        for (final MerkleNode tree : trees) {
            if (tree == null) {
                continue;
            }
            tree.forEachNode((final MerkleNode A) -> {
                if (A == null) {
                    return;
                }
                tree.forEachNode((final MerkleNode B) -> {
                    if (B == null) {
                        return;
                    }
                    if (A.getRoute().isAncestorOf(B.getRoute())) {
                        // We should be able to find B under A
                        assertTrue(isAncestor(A, B), "B should be a descendant of A");
                    } else {
                        // We should not be able to find B under A
                        assertFalse(isAncestor(A, B), "B should not be a descendant of A");
                    }
                });
            });
        }
    }

    @ParameterizedTest
    @MethodSource("buildArguments")
    @Tag(TestComponentTags.MERKLE)
    @DisplayName("Route Descendant Test")
    void routeDescendantTest(final MerkleRouteFactory.MerkleRouteEncoding encoding) {
        setRouteEncodingStrategy(encoding);

        final List<DummyMerkleNode> trees = MerkleTestUtils.buildSmallTreeList();
        for (final MerkleNode tree : trees) {
            if (tree == null) {
                continue;
            }
            tree.forEachNode((final MerkleNode A) -> {
                if (A == null) {
                    return;
                }
                tree.forEachNode((final MerkleNode B) -> {
                    if (B == null) {
                        return;
                    }
                    if (A.getRoute().isDescendantOf(B.getRoute())) {
                        // We should be able to find A under B
                        assertTrue(isAncestor(B, A), "A should be a descendant of B");
                    } else {
                        // We should not be able to find A under B
                        assertFalse(isAncestor(B, A), "A should not be a descendant of B");
                    }
                });
            });
        }
    }

    /**
     * Add a bunch of steps to a route in multiple ways.
     *
     * @param base
     * 		the base route
     * @param steps
     * 		the steps to add
     */
    private void testAddToRoute(final MerkleRoute base, final List<Integer> steps) {
        MerkleRoute incremental = base;
        for (final int step : steps) {
            incremental = incremental.extendRoute(step);
        }

        final MerkleRoute listAdd = base.extendRoute(steps);

        final int[] stepArray = new int[steps.size()];
        for (int i = 0; i < steps.size(); i++) {
            stepArray[i] = steps.get(i);
        }
        final MerkleRoute arrayAdd = base.extendRoute(stepArray);

        assertEquals(incremental, listAdd, "routes constructed with the same steps should match");
        assertEquals(incremental, arrayAdd, "routes constructed with the same steps should match");
        assertEquals(listAdd, arrayAdd, "routes constructed with the same steps should match");
    }

    @ParameterizedTest
    @MethodSource("buildArguments")
    @Tag(TestComponentTags.MERKLE)
    @DisplayName("Add Multiple Steps Test")
    void addMultipleStepsTest(final MerkleRouteFactory.MerkleRouteEncoding encoding) {
        setRouteEncodingStrategy(encoding);

        // empty starting route
        testAddToRoute(getEmptyRoute(), List.of(0, 1, 2, 3, 4, 3, 2, 1, 0));

        // non-empty starting route
        testAddToRoute(buildRoute(0, 1, 2, 1, 0), List.of(0, 1, 2, 3, 4, 3, 2, 1, 0));

        // no steps to add
        testAddToRoute(buildRoute(0, 1, 2, 1, 0), List.of());

        // no steps empty starting
        testAddToRoute(getEmptyRoute(), List.of());
    }

    private void testSerialization(final MerkleRoute route) {}

    @ParameterizedTest
    @MethodSource("buildArguments")
    @Tag(TestComponentTags.MERKLE)
    @DisplayName("Serialization Test")
    void serializationTest(final MerkleRouteFactory.MerkleRouteEncoding encoding) {
        setRouteEncodingStrategy(encoding);

        // empty route
        testSerialization(getEmptyRoute());

        // small route
        testSerialization(buildRoute(0, 1, 2, 3, 4, 3, 2, 1, 0));

        // big route
        final List<Integer> steps = new LinkedList<>();
        final Random random = new Random();
        for (int i = 0; i < 1000; i++) {

            if (i % 10 != 0) {
                steps.add(Math.abs(random.nextInt()));
            } else {
                steps.add(Math.abs(random.nextInt(2)));
            }
        }
        testSerialization(buildRoute(steps));
    }

    @ParameterizedTest
    @MethodSource("buildArguments")
    @Tag(TestComponentTags.MERKLE)
    @DisplayName("isEmpty() Test")
    void isEmptyTest(final MerkleRouteFactory.MerkleRouteEncoding encoding) {
        setRouteEncodingStrategy(encoding);

        assertTrue(getEmptyRoute().isEmpty(), "route should be empty");
        assertTrue(buildRoute().isEmpty(), "route should be empty");
        assertFalse(buildRoute(1, 2, 3).isEmpty(), "route should not be empty");
    }

    @ParameterizedTest
    @MethodSource("buildArguments")
    @Tag(TestComponentTags.MERKLE)
    @DisplayName("getStep() Test")
    void getStepTest(final MerkleRouteFactory.MerkleRouteEncoding encoding) {
        setRouteEncodingStrategy(encoding);

        assertThrows(
                IndexOutOfBoundsException.class,
                () -> getEmptyRoute().getStep(0),
                "can't get anything from empty route");

        final List<List<Integer>> steps = List.of(
                new LinkedList<>(), List.of(0), List.of(1), List.of(0, 1), List.of(0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10));

        final List<MerkleRoute> routes = new ArrayList<>();
        for (final List<Integer> routeSteps : steps) {
            routes.add(buildRoute(routeSteps));
        }

        for (int index = 0; index < steps.size(); index++) {

            final List<Integer> routeSteps = steps.get(index);
            final MerkleRoute route = routes.get(index);

            for (int routeIndex = 0; routeIndex < routeSteps.size(); routeIndex++) {
                final int step = routeSteps.get(routeIndex);

                assertEquals(step, route.getStep(routeIndex), "step should match");
                assertEquals(step, route.getStep(-1 * routeSteps.size() + routeIndex), "" + "step should match");
            }

            assertThrows(
                    IndexOutOfBoundsException.class,
                    () -> getEmptyRoute().getStep(routeSteps.size()),
                    "index is too large, should throw");

            assertThrows(
                    IndexOutOfBoundsException.class,
                    () -> getEmptyRoute().getStep(-1 - routeSteps.size()),
                    "index is too small, should throw");
        }
    }

    @ParameterizedTest
    @MethodSource("buildArguments")
    @Tag(TestComponentTags.MERKLE)
    @DisplayName("getParent() Test")
    void getParentTest(final MerkleRouteFactory.MerkleRouteEncoding encoding) {
        setRouteEncodingStrategy(encoding);
        final Random random = getRandomPrintSeed();

        MerkleRoute parent = getEmptyRoute();
        for (int i = 0; i < 100; i++) {

            final MerkleRoute child;
            if (random.nextDouble() < 0.1) {
                child = parent.extendRoute(random.nextInt(0, 1000));
            } else {
                child = parent.extendRoute(random.nextInt(0, 2));
            }

            assertEquals(parent, child.getParent());
            parent = child;
        }
    }

    @ParameterizedTest
    @MethodSource("buildArguments")
    @Tag(TestComponentTags.MERKLE)
    @DisplayName("Path Format Test")
    void pathFormatTest(final MerkleRouteFactory.MerkleRouteEncoding encoding) {
        setRouteEncodingStrategy(encoding);
        final Random random = getRandomPrintSeed();

        for (int length = 0; length < 20; length++) {
            final List<Integer> steps = new LinkedList<>();
            for (int i = 0; i < length; i++) {
                if (random.nextDouble() < 0.1) {
                    steps.add(random.nextInt(0, 1000));
                } else {
                    steps.add(random.nextInt(0, 2));
                }
            }

            final MerkleRoute route = buildRoute(steps);
            final String pathRepresentation = merkleRouteToPathFormat(route);
            final MerkleRoute derivedRoute = pathFormatToMerkleRoute(pathRepresentation);
            assertEquals(route, derivedRoute);

            if (length == 0) {
                assertEquals("/", pathRepresentation);
            } else {
                final String[] stepStrings = pathRepresentation.split("/");

                assertEquals(steps.size() + 1, stepStrings.length); // will be one larger due to leading "/"

                final List<Integer> deserializedSteps = new LinkedList<>();
                for (final String stepString : stepStrings) {
                    if (stepString.isEmpty()) {
                        continue;
                    }

                    deserializedSteps.add(Integer.parseInt(stepString));
                }
                assertEquals(steps, deserializedSteps);
            }

            // deriving route relative to another should yield the same
            // route since the generated string is an absolute path
            final MerkleRoute relativeRoute = getEmptyRoute().extendRoute(1, 2, 3);
            assertEquals(route, pathFormatToMerkleRoute(relativeRoute, pathRepresentation));

            // Adding "." should transform the absolute path into a relative path
            final String relativePath = "." + pathRepresentation;
            final MerkleRoute relativeDerivedRoute = pathFormatToMerkleRoute(relativeRoute, relativePath);
            final MerkleRoute expectedRelativeDerivedRoute = relativeRoute.extendRoute(steps);
            assertEquals(expectedRelativeDerivedRoute, relativeDerivedRoute);
        }
    }
}
