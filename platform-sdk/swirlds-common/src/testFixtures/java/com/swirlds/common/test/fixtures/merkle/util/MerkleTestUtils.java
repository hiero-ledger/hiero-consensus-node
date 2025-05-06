// SPDX-License-Identifier: Apache-2.0
package com.swirlds.common.test.fixtures.merkle.util;

import static com.swirlds.common.merkle.copy.MerkleInitialize.initializeTreeAfterCopy;
import static com.swirlds.common.threading.manager.AdHocThreadManager.getStaticThreadManager;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.Mockito.mock;

import com.swirlds.base.time.Time;
import com.swirlds.common.context.PlatformContext;
import com.swirlds.common.merkle.MerkleInternal;
import com.swirlds.common.merkle.MerkleLeaf;
import com.swirlds.common.merkle.MerkleNode;
import com.swirlds.common.merkle.iterators.MerkleIterator;
import com.swirlds.common.merkle.synchronization.LearningSynchronizer;
import com.swirlds.common.merkle.synchronization.TeachingSynchronizer;
import com.swirlds.common.merkle.synchronization.config.ReconnectConfig;
import com.swirlds.common.merkle.synchronization.utility.MerkleSynchronizationException;
import com.swirlds.common.metrics.config.MetricsConfig;
import com.swirlds.common.metrics.platform.DefaultPlatformMetrics;
import com.swirlds.common.metrics.platform.MetricKeyRegistry;
import com.swirlds.common.metrics.platform.PlatformMetricsFactoryImpl;
import com.swirlds.common.test.fixtures.merkle.TestMerkleCryptoFactory;
import com.swirlds.common.test.fixtures.merkle.dummy.DummyCustomReconnectRoot;
import com.swirlds.common.test.fixtures.merkle.dummy.DummyMerkleExternalLeaf;
import com.swirlds.common.test.fixtures.merkle.dummy.DummyMerkleInternal;
import com.swirlds.common.test.fixtures.merkle.dummy.DummyMerkleInternal2;
import com.swirlds.common.test.fixtures.merkle.dummy.DummyMerkleLeaf;
import com.swirlds.common.test.fixtures.merkle.dummy.DummyMerkleLeaf2;
import com.swirlds.common.test.fixtures.merkle.dummy.DummyMerkleNode;
import com.swirlds.common.test.fixtures.platform.TestPlatformContextBuilder;
import com.swirlds.common.threading.manager.ThreadManager;
import com.swirlds.common.threading.pool.StandardWorkGroup;
import com.swirlds.config.api.Configuration;
import com.swirlds.config.extensions.test.fixtures.TestConfigBuilder;
import com.swirlds.metrics.api.Metrics;
import com.swirlds.virtualmap.VirtualKey;
import com.swirlds.virtualmap.VirtualMap;
import com.swirlds.virtualmap.internal.merkle.VirtualLeafNode;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import org.hiero.base.io.streams.SerializableDataOutputStream;

/**
 * Utility methods for testing merkle trees.
 */
public final class MerkleTestUtils {

    private static Metrics createMetrics() {
        final Configuration configuration = new TestConfigBuilder().getOrCreateConfig();
        final MetricsConfig metricsConfig = configuration.getConfigData(MetricsConfig.class);
        final MetricKeyRegistry registry = new MetricKeyRegistry();
        return new DefaultPlatformMetrics(
                null,
                registry,
                mock(ScheduledExecutorService.class),
                new PlatformMetricsFactoryImpl(metricsConfig),
                metricsConfig);
    }

    private static final Metrics metrics = createMetrics();

    private MerkleTestUtils() {}

    /**
     * Returns an empty merkle tree.
     */
    public static DummyMerkleNode buildSizeZeroTree() {
        return null;
    }

    /**
     * Returns a merkle tree with only a single leaf.
     *
     * <pre>
     * A
     * </pre>
     */
    public static DummyMerkleNode buildSizeOneTree() {
        return new DummyMerkleLeaf("A");
    }

    /**
     * Returns the following tree:
     *
     * root
     * |
     * A
     */
    public static DummyMerkleNode buildSimpleTree() {
        final DummyMerkleInternal root = new DummyMerkleInternal("root");
        final MerkleLeaf A = new DummyMerkleLeaf("A");
        root.setChild(0, A);
        initializeTreeAfterCopy(root);
        return root;
    }

    /**
     * Returns the following tree:
     *
     * <pre>
     *             root
     *           / |   \
     *          A  i0  i1
     *             /\  /\
     *            B C D null
     * </pre>
     */
    public static DummyMerkleInternal buildLessSimpleTree() {
        final DummyMerkleInternal root = new DummyMerkleInternal("root");

        final MerkleLeaf A = new DummyMerkleLeaf("A");
        final MerkleInternal i0 = new DummyMerkleInternal("i0");
        final MerkleInternal i1 = new DummyMerkleInternal("i1");
        root.setChild(0, A);
        root.setChild(1, i0);
        root.setChild(2, i1);

        final MerkleLeaf B = new DummyMerkleLeaf("B");
        final MerkleLeaf C = new DummyMerkleLeaf("C");
        i0.setChild(0, B);
        i0.setChild(1, C);

        final MerkleLeaf D = new DummyMerkleLeaf("D");
        i1.setChild(0, D);
        i1.setChild(1, null);

        initializeTreeAfterCopy(root);
        return root;
    }

    /**
     * Returns the following tree:
     *
     * <pre>
     *             root
     *           / |   \
     *          A  i0  i1
     *             /\  /\
     *           i4 C D i2
     *                 / \
     *                i3 E
     *                /\
     *               F G
     * </pre>
     */
    public static DummyMerkleInternal buildLessSimpleTreeExtended() {
        final DummyMerkleInternal root = new DummyMerkleInternal("root");

        final MerkleLeaf A = new DummyMerkleLeaf("A");
        final MerkleInternal i0 = new DummyMerkleInternal("i0");
        final MerkleInternal i1 = new DummyMerkleInternal("i1");
        root.setChild(0, A);
        root.setChild(1, i0);
        root.setChild(2, i1);

        final MerkleInternal i4 = new DummyMerkleInternal("i4");
        final MerkleLeaf C = new DummyMerkleLeaf("C");
        i0.setChild(0, i4);
        i0.setChild(1, C);

        final MerkleLeaf D = new DummyMerkleLeaf("D");
        final MerkleInternal i2 = new DummyMerkleInternal("i2");
        i1.setChild(0, D);
        i1.setChild(1, i2);

        final MerkleInternal i3 = new DummyMerkleInternal("i3");
        final MerkleLeaf E = new DummyMerkleLeaf("E");
        i2.setChild(0, i3);
        i2.setChild(1, E);

        final MerkleLeaf F = new DummyMerkleLeaf("F");
        final MerkleLeaf G = new DummyMerkleLeaf("G");
        i3.setChild(0, F);
        i3.setChild(1, G);

        initializeTreeAfterCopy(root);
        return root;
    }

    /**
     * Returns the following tree:
     *
     * <pre>
     *             root
     *           / |   \
     *          A  i0  i1
     *             /\  /\
     *            B C D null
     * </pre>
     *
     * All nodes at depth 1 (A, i0, i1) are instantiated using either DummyMerkleNode2 or DummyMerkleInternal2 types.
     */
    public static DummyMerkleNode buildLessSimpleTreeWithSwappedTypes() {
        final DummyMerkleInternal root = new DummyMerkleInternal("root");

        final MerkleLeaf A = new DummyMerkleLeaf2("A");
        final MerkleInternal i0 = new DummyMerkleInternal2("i0");
        final MerkleInternal i1 = new DummyMerkleInternal2("i1");
        root.setChild(0, A);
        root.setChild(1, i0);
        root.setChild(2, i1);

        final MerkleLeaf B = new DummyMerkleLeaf("B");
        final MerkleLeaf C = new DummyMerkleLeaf("C");
        i0.setChild(0, B);
        i0.setChild(1, C);

        final MerkleLeaf D = new DummyMerkleLeaf("D");
        i1.setChild(0, D);
        i1.setChild(1, null);

        initializeTreeAfterCopy(root);
        return root;
    }

    /**
     * Returns the following tree:
     *
     * <pre>
     *             root
     *           / |   \
     *          A  i0  i1
     *            / \  / \
     *           B E1  D E2
     * </pre>
     */
    public static DummyMerkleNode buildTreeWithExternalData() {
        final DummyMerkleInternal root = new DummyMerkleInternal("root");

        final MerkleLeaf A = new DummyMerkleLeaf("A");
        final MerkleInternal i0 = new DummyMerkleInternal("i0");
        final MerkleInternal i1 = new DummyMerkleInternal("i1");
        root.setChild(0, A);
        root.setChild(1, i0);
        root.setChild(2, i1);

        final MerkleLeaf B = new DummyMerkleLeaf("B");
        final MerkleLeaf E1 = new DummyMerkleExternalLeaf(1234, 10, 0);
        i0.setChild(0, B);
        i0.setChild(1, E1);

        final MerkleLeaf D = new DummyMerkleLeaf("D");
        final MerkleLeaf E2 = new DummyMerkleExternalLeaf(4321, 10, 0);
        i1.setChild(0, D);
        i1.setChild(1, E2);

        initializeTreeAfterCopy(root);
        return root;
    }

    /**
     * Returns a random number from a gaussian distribution with the specified parameters.
     *
     * @param random
     * 		A random number source.
     * @param mean
     * 		The mean of the distribution.
     * @param standardDeviation
     * 		The standard deviation of the distribution.
     * @param minimum
     * 		If the generated value is less than the minimum then round it up to the minimum and return it.
     */
    public static double randomWithDistribution(
            final Random random, final double mean, final double standardDeviation, final double minimum) {
        return Math.max(random.nextGaussian() * standardDeviation + mean, minimum);
    }

    /**
     * Returns true with a given probability
     *
     * @param probability
     * 		The probability of returning true. &gt;=1.0 = 100% true
     */
    public static boolean randomChance(final Random random, final double probability) {
        return random.nextDouble() < probability;
    }

    /**
     * Generate a random string with the given parameters.
     *
     * @param seed
     * 		The seed used to generate the string. Two calls to this method with the same seed will
     * 		result in the same randomly generated string.
     * @param averageSize
     * 		The average size of a string returned by this method.
     * @param standardDeviation
     * 		The standard deviation of a string returned by this method.
     */
    public static String generateRandomString(
            final long seed, final double averageSize, final double standardDeviation) {
        return generateRandomString(new Random(seed), averageSize, standardDeviation);
    }

    /**
     * Generate a random human readable ASCII character (in the range 65-90, inclusive).
     */
    public static char generateRandomCharacter(final Random random) {
        final int min = 65;
        final int max = 90;
        int next = random.nextInt();
        if (next < 0) {
            next = next * -1;
        }
        next = (next % (max - min)) + min;
        return (char) next;
    }

    /**
     * Generate a random human readable string.
     */
    public static String generateRandomString(
            final Random random, final double averageSize, final double standardDeviation) {
        final int length = (int) randomWithDistribution(random, averageSize, standardDeviation, 1);
        final StringBuilder sb = new StringBuilder();
        for (int i = 0; i < length; i++) {
            sb.append(generateRandomCharacter(random));
        }
        return sb.toString();
    }

    /**
     * Generate a random leaf node with the given parameters. Leaf will always contain a minimum of 1 byte.
     *
     * @param random
     * 		A random number source.
     * @param leafSizeAverage
     * 		The average number of bytes in this leaf.
     * @param leafSizeStandardDeviation
     * 		The standard deviation in the size of leaves.
     */
    public static DummyMerkleLeaf generateRandomMerkleLeaf(
            final Random random, final double leafSizeAverage, final double leafSizeStandardDeviation) {
        return new DummyMerkleLeaf(generateRandomString(random, leafSizeAverage, leafSizeStandardDeviation));
    }

    /**
     * Generate a random internal node and all of its children. Each internal node will have at least one leaf.
     *
     * @param random
     * 		A random number source.
     * @param parent
     * 		The parent of this merkle internal.
     * @param indexInParent
     * 		The index of this merkle internal within its parent.
     * @param numberOfLeavesAverage
     * 		The average number of child leaves.
     * @param numberOfLeavesStandardDeviation
     * 		The standard deviation in the number of child leaves.
     * @param leafSizeAverage
     * 		The average size of each leaf.
     * @param leafSizeStandardDeviation
     * 		The standard deviation in the size of each leaf.
     * @param numberOfInternalNodesAverage
     * 		The average number of internal node children.
     * @param numberOfInternalNodesStandardDeviation
     * 		The standard deviation in the number of children.
     * @param numberOfInternalNodesDecayFactor
     * 		The amount by which the average number of child internal nodes decays
     * 		with each level in the tree.
     * @param depth
     * 		The depth of the node being created.
     */
    public static DummyMerkleInternal generateRandomInternalNode(
            final Random random,
            final MerkleInternal parent,
            final int indexInParent,
            final double numberOfLeavesAverage,
            final double numberOfLeavesStandardDeviation,
            final double leafSizeAverage,
            final double leafSizeStandardDeviation,
            final double numberOfInternalNodesAverage,
            final double numberOfInternalNodesStandardDeviation,
            final double numberOfInternalNodesDecayFactor,
            final int depth) {

        final DummyMerkleInternal node = new DummyMerkleInternal();
        node.rebuild();
        if (parent != null) {
            parent.setChild(indexInParent, node);
        }

        final int numberOfLeaves =
                (int) randomWithDistribution(random, numberOfLeavesAverage, numberOfLeavesStandardDeviation, 0);
        final int numberOfInternalNodes = (int) randomWithDistribution(
                random,
                numberOfInternalNodesAverage - (numberOfInternalNodesDecayFactor * depth),
                numberOfInternalNodesStandardDeviation,
                0);

        int numberOfLeavesCreated = 0;
        int numberOfInternalNodesCreated = 0;

        // Create leaves and internal nodes in an alternating pattern
        while (numberOfLeavesCreated < numberOfLeaves || numberOfInternalNodesCreated < numberOfInternalNodes) {
            if (numberOfLeavesCreated < numberOfLeaves) {
                node.setChild(
                        numberOfLeavesCreated + numberOfInternalNodesCreated,
                        generateRandomMerkleLeaf(random, leafSizeAverage, leafSizeStandardDeviation));
                numberOfLeavesCreated++;
            }
            if (numberOfInternalNodesCreated < numberOfInternalNodes) {
                generateRandomInternalNode(
                        random,
                        node,
                        numberOfLeavesCreated + numberOfInternalNodesCreated,
                        numberOfLeavesAverage,
                        numberOfLeavesStandardDeviation,
                        leafSizeAverage,
                        leafSizeStandardDeviation,
                        numberOfInternalNodesAverage,
                        numberOfInternalNodesStandardDeviation,
                        numberOfInternalNodesDecayFactor,
                        depth + 1);
                numberOfInternalNodesCreated++;
            }
        }
        return node;
    }

    /**
     * Generate a random tree deterministically.
     *
     * @param seed
     * 		Two trees generated using the same seed (and other arguments) will have the exact same topology.
     * @param numberOfLeavesAverage
     * 		The average number of leaves for each internal node.
     * @param numberOfLeavesStandardDeviation
     * 		The standard deviation for the number of leaves for each internal node.
     * 		Each internal node will have at least one leaf.
     * @param leafSizeAverage
     * 		The average number of bytes in each leaf. A leaf will have at a minimum one byte.
     * @param leafSizeStandardDeviation
     * 		The standard deviation for the number bytes in each leaf.
     * @param numberOfInternalNodesAverage
     * 		The average number of internal nodes children for each internal node.
     * @param numberOfInternalNodesStandardDeviation
     * 		The standard deviation for the number of internal nodes.
     * @param numberOfInternalNodesDecayFactor
     * 		The average number of internal nodes will decrease by this amount for
     * 		every level the tree grows deeper. This will bound the size of the tree.
     * @return The root of the tree.
     */
    public static DummyMerkleNode generateRandomTree(
            final long seed,
            final double numberOfLeavesAverage,
            final double numberOfLeavesStandardDeviation,
            final double leafSizeAverage,
            final double leafSizeStandardDeviation,
            final double numberOfInternalNodesAverage,
            final double numberOfInternalNodesStandardDeviation,
            final double numberOfInternalNodesDecayFactor) {

        final Random random = new Random(seed);

        return generateRandomInternalNode(
                random,
                null,
                0,
                numberOfLeavesAverage,
                numberOfLeavesStandardDeviation,
                leafSizeAverage,
                leafSizeStandardDeviation,
                numberOfInternalNodesAverage,
                numberOfInternalNodesStandardDeviation,
                numberOfInternalNodesDecayFactor,
                0);
    }

    private static DummyMerkleNode generateRandomBalancedTree(
            final Random random,
            final MerkleInternal parent,
            final int indexInParent,
            final int depth,
            final int internalNodeChildren,
            final double leafSizeAverage,
            final double leafSizeStandardDeviation,
            final int currentDepth) {

        if (depth == currentDepth) {
            final DummyMerkleNode child = generateRandomMerkleLeaf(random, leafSizeAverage, leafSizeStandardDeviation);
            if (parent != null) {
                parent.setChild(indexInParent, child);
            }
            return child;
        }

        final DummyMerkleInternal node = new DummyMerkleInternal();
        node.rebuild();
        if (parent != null) {
            parent.setChild(indexInParent, node);
        }
        for (int childIndex = 0; childIndex < internalNodeChildren; childIndex++) {
            generateRandomBalancedTree(
                    random,
                    node,
                    childIndex,
                    depth,
                    internalNodeChildren,
                    leafSizeAverage,
                    leafSizeStandardDeviation,
                    currentDepth + 1);
        }
        return node;
    }

    /**
     * Generate a balanced tree. All internal nodes have the exact same number of children. All leaf nodes are at
     * the lowest level of the tree. Total leaf nodes are internalNodeChildren ^ depth.
     *
     * @param seed
     * 		Two trees generated using the same seed (and other arguments) will have the exact same topology.
     * @param depth
     * 		The depth of the tree. All leaves will be at this depth.
     * @param internalNodeChildren
     * 		The number of children that each internal node has.
     * @param leafSizeAverage
     * 		The average size of a leaf in bytes.
     * @param leafSizeStandardDeviation
     * 		The standard deviation of the leaf size.
     */
    public static DummyMerkleNode generateRandomBalancedTree(
            final long seed,
            final int depth,
            final int internalNodeChildren,
            final double leafSizeAverage,
            final double leafSizeStandardDeviation) {
        final Random random = new Random(seed);
        return generateRandomBalancedTree(
                random, null, 0, depth, internalNodeChildren, leafSizeAverage, leafSizeStandardDeviation, 0);
    }

    private static void randomlyMutateTree(
            final DummyMerkleNode root,
            final double leafMutationProbability,
            final double internalMutationProbability,
            final Random random,
            final double numberOfLeavesAverage,
            final double numberOfLeavesStandardDeviation,
            final double leafSizeAverage,
            final double leafSizeStandardDeviation,
            final double numberOfInternalNodesAverage,
            final double numberOfInternalNodesStandardDeviation,
            final double numberOfInternalNodesDecayFactor,
            final int depth) {

        if (root instanceof MerkleInternal) {
            final MerkleInternal internal = root.cast();
            for (int childIndex = 0; childIndex < internal.getNumberOfChildren(); childIndex++) {
                MerkleNode child = internal.getChild(childIndex);
                if (child == null || child instanceof MerkleLeaf && randomChance(random, leafMutationProbability)) {
                    final MerkleNode replacement =
                            generateRandomMerkleLeaf(random, leafSizeAverage, leafSizeStandardDeviation);
                    internal.setChild(childIndex, replacement);
                } else if (child instanceof MerkleInternal) {
                    if (randomChance(random, internalMutationProbability)) {
                        final MerkleNode replacement = generateRandomInternalNode(
                                random,
                                internal,
                                childIndex,
                                numberOfLeavesAverage,
                                numberOfLeavesStandardDeviation,
                                leafSizeAverage,
                                leafSizeStandardDeviation,
                                numberOfInternalNodesAverage,
                                numberOfInternalNodesStandardDeviation,
                                numberOfInternalNodesDecayFactor,
                                depth + 1);
                        internal.setChild(childIndex, replacement);
                    } else {
                        randomlyMutateTree(
                                (DummyMerkleNode) child,
                                leafMutationProbability,
                                internalMutationProbability,
                                random,
                                numberOfLeavesAverage,
                                numberOfLeavesStandardDeviation,
                                leafSizeAverage,
                                leafSizeStandardDeviation,
                                numberOfInternalNodesAverage,
                                numberOfInternalNodesStandardDeviation,
                                numberOfInternalNodesDecayFactor,
                                depth + 1);
                    }
                }
            }
        }
    }

    /**
     * Iterate over a tree. For each node, possibly replace leaves with other leaves and
     * internal nodes with new subtrees.
     *
     * Does not modify the root (so that the reference passed into this method now points to the modified tree).
     *
     * @param root
     * 		The tree to modify.
     * @param leafMutationProbability
     * 		The probability than any particular leaf will be replaced with a new subtree.
     * @param internalMutationProbability
     * 		The probability that any particular internal node will be replaced with
     * 		a new subtree.
     * @param seed
     * 		Two trees mutated using the same seed (and other arguments) will have the exact same topology.
     * @param numberOfLeavesAverage
     * 		The average number of leaves for each internal node.
     * @param numberOfLeavesStandardDeviation
     * 		The standard deviation for the number of leaves for each internal node.
     * 		Each internal node will have at least one leaf.
     * @param leafSizeAverage
     * 		The average number of bytes in each leaf. A leaf will have at a minimum one byte.
     * @param leafSizeStandardDeviation
     * 		The standard deviation for the number bytes in each leaf.
     * @param numberOfInternalNodesAverage
     * 		The average number of internal nodes children for each internal node.
     * @param numberOfInternalNodesStandardDeviation
     * 		The standard deviation for the number of internal nodes.
     * @param numberOfInternalNodesDecayFactor
     * 		The average number of internal nodes will decrease by this amount for
     * 		every level the tree grows deeper. This will bound the size of the tree.
     */
    public static void randomlyMutateTree(
            final DummyMerkleNode root,
            final double leafMutationProbability,
            final double internalMutationProbability,
            final long seed,
            final double numberOfLeavesAverage,
            final double numberOfLeavesStandardDeviation,
            final double leafSizeAverage,
            final double leafSizeStandardDeviation,
            final double numberOfInternalNodesAverage,
            final double numberOfInternalNodesStandardDeviation,
            final double numberOfInternalNodesDecayFactor) {

        final Random random = new Random(seed);

        randomlyMutateTree(
                root,
                leafMutationProbability,
                internalMutationProbability,
                random,
                numberOfLeavesAverage,
                numberOfLeavesStandardDeviation,
                leafSizeAverage,
                leafSizeStandardDeviation,
                numberOfInternalNodesAverage,
                numberOfInternalNodesStandardDeviation,
                numberOfInternalNodesDecayFactor,
                0);
    }

    public static void printTreeStats(final MerkleNode root) {
        System.out.println("Number of nodes: " + measureNumberOfNodes(root));
        System.out.println("Number of leaves: " + measureNumberOfLeafNodes(root));
        System.out.println("Maximum depth: " + measureTreeDepth(root));
        System.out.println("Average leaf depth: " + measureAverageLeafDepth(root));
    }

    /**
     * Counts and returns the number of nodes in a merkle tree.
     */
    public static int measureNumberOfNodes(final MerkleNode root) {
        final Iterator<MerkleNode> it = new MerkleIterator<>(root).ignoreNull(false);
        int count = 0;
        while (it.hasNext()) {
            it.next();
            count++;
        }
        return count;
    }

    /**
     * Counts and returns the number of leaf nodes in a merkle tree.
     */
    public static int measureNumberOfLeafNodes(final MerkleNode root) {
        final Iterator<?> it = new MerkleIterator<>(root)
                .ignoreNull(false)
                .setFilter((final MerkleNode node) -> !(node instanceof MerkleInternal));
        int count = 0;
        while (it.hasNext()) {
            it.next();
            count++;
        }
        return count;
    }

    /**
     * Find the maximum depth of a tree.
     */
    public static int measureTreeDepth(final MerkleNode root) {
        if (root == null) {
            return 0;
        }
        if (root.isLeaf()) {
            return 1;
        } else {
            final MerkleInternal node = root.cast();
            int maxChildDepth = 0;
            for (int childIndex = 0; childIndex < node.getNumberOfChildren(); childIndex++) {
                maxChildDepth = Math.max(maxChildDepth, measureTreeDepth(node.getChild(childIndex)));
            }
            return maxChildDepth + 1;
        }
    }

    /**
     * Measure the average depth of all leaf nodes in a tree.
     */
    private static AverageLeafDepth measureAverageLeafDepthInternal(
            final MerkleNode tree, final int depth, final Set<MerkleNode> visitedNodes) {

        final AverageLeafDepth averageDepth = new AverageLeafDepth();

        if (tree != null) {
            if (tree.isLeaf()) {
                averageDepth.addLeaves(1);
                averageDepth.addDepth(depth + 1);
            } else {
                final MerkleInternal node = tree.cast();
                for (int childIndex = 0; childIndex < node.getNumberOfChildren(); childIndex++) {
                    final MerkleNode child = node.getChild(childIndex);
                    if (visitedNodes.contains(child)) {
                        // Don't visit nodes more than once
                        continue;
                    }
                    final AverageLeafDepth childDepth = measureAverageLeafDepthInternal(child, depth + 1, visitedNodes);
                    averageDepth.add(childDepth);
                    visitedNodes.add(child);
                }
            }
        }

        return averageDepth;
    }

    /**
     * Measure the average depth of all leaf nodes in a tree.
     */
    public static double measureAverageLeafDepth(final MerkleNode root) {
        final Set<MerkleNode> visitedNodes = new HashSet<>();
        return measureAverageLeafDepthInternal(root, 0, visitedNodes).getAverageDepth();
    }

    /**
     * Measure the average leaf size. All leaves must be DummyMerkleLeaves.
     */
    public static double measureAverageLeafSize(final DummyMerkleNode root) {
        if (root == null) {
            return 0;
        }

        double totalSize = 0;
        int count = 0;
        final Iterator<MerkleLeaf> iterator = new MerkleIterator<MerkleLeaf>(root).setFilter(MerkleNode::isLeaf);
        while (iterator.hasNext()) {
            DummyMerkleNode next = (DummyMerkleNode) iterator.next();
            if (next != null) {
                totalSize += next.getValue().length();
                count += 1;
            }
        }

        if (count == 0) {
            return totalSize;
        }

        return totalSize / count;
    }

    public static List<DummyMerkleNode> buildSmallTreeList() {
        final List<DummyMerkleNode> list = new ArrayList<>();
        list.add(MerkleTestUtils.buildSizeZeroTree());
        list.add(MerkleTestUtils.buildSizeOneTree());
        list.add(MerkleTestUtils.buildSimpleTree());
        list.add(MerkleTestUtils.buildLessSimpleTree());
        list.add(MerkleTestUtils.buildLessSimpleTreeExtended());
        list.add(MerkleTestUtils.buildTreeWithExternalData());
        list.add(MerkleTestUtils.buildLessSimpleTreeWithSwappedTypes());
        return list;
    }

    public static List<DummyMerkleNode> buildTreeList() {
        final List<DummyMerkleNode> list = buildSmallTreeList();
        final DummyMerkleNode randomTree = generateRandomTree(0, 2, 1, 1, 0, 3, 1, 0.25);
        System.out.println("Random tree statistics:");
        printTreeStats(randomTree);

        list.add(randomTree);

        final DummyMerkleNode mutatedRandomTree = generateRandomTree(0, 2, 1, 1, 0, 3, 1, 0.25);
        randomlyMutateTree(mutatedRandomTree, 0.1, 0.05, 1234, 2, 1, 1, 0, 3, 1, 0.25);
        System.out.println("Random tree statistics (mutated):");
        printTreeStats(mutatedRandomTree);
        list.add(mutatedRandomTree);

        return list;
    }

    /**
     * Compares two merkle nodes for equality.
     */
    private static boolean areNodesEqual(final MerkleNode a, final MerkleNode b) {
        if (a == null || b == null) {
            return a == b;
        } else {
            if (a.getClassId() != b.getClassId()) {
                return false;
            }
            if (a.isLeaf()) {
                return areLeavesEqual(a.asLeaf(), b.asLeaf());
            } else {
                return areInternalsEqual(a.asInternal(), b.asInternal());
            }
        }
    }

    /**
     * Compares two merkle leaves of the same type for equality.
     */
    private static boolean areLeavesEqual(final MerkleLeaf a, final MerkleLeaf b) {
        try {
            final ByteArrayOutputStream bsA = new ByteArrayOutputStream();
            final SerializableDataOutputStream sA = new SerializableDataOutputStream(bsA);
            try {
                sA.writeSerializable(a, true);
            } catch (IOException e) {
                fail(e);
            }

            final ByteArrayOutputStream bsB = new ByteArrayOutputStream();
            final SerializableDataOutputStream sB = new SerializableDataOutputStream(bsB);
            try {
                sB.writeSerializable(b, true);
            } catch (IOException e) {
                fail(e);
            }

            final byte[] bytesA = bsA.toByteArray();
            final byte[] bytesB = bsB.toByteArray();

            if (bytesA.length != bytesB.length) {
                return false;
            }
            for (int index = 0; index < bytesA.length; index++) {
                if (bytesA[index] != bytesB[index]) {
                    return false;
                }
            }
            return true;
        } catch (final UnsupportedOperationException e) {
            // Some leaf types don't want to be serialized. Those should implement equals if they want to be compered
            // with this method.
            return Objects.equals(a, b);
        }
    }

    /**
     * Compares two merkle internal nodes of the same type for equality.
     */
    private static boolean areInternalsEqual(final MerkleInternal a, final MerkleInternal b) {
        return a.getNumberOfChildren() == b.getNumberOfChildren();
    }

    /**
     * Compare two trees for equality.
     */
    public static boolean areTreesEqual(final MerkleNode rootA, final MerkleNode rootB) {
        final Iterator<MerkleNode> iteratorA = new MerkleIterator<>(rootA);
        final Iterator<MerkleNode> iteratorB = new MerkleIterator<>(rootB);

        while (iteratorA.hasNext()) {
            if (!iteratorB.hasNext()) {
                return false;
            }
            final MerkleNode a = iteratorA.next();
            final MerkleNode b = iteratorB.next();

            if (!areNodesEqual(a, b)) {
                return false;
            }
        }

        return !iteratorB.hasNext();
    }

    /**
     * For every virtual map in the trees and for every virtual key in the given key set, make
     * sure either the map in both trees contains the key, or the map in both trees doesn't
     * contain the key.
     */
    @SuppressWarnings({"rawtypes", "unchecked"})
    public static boolean checkVirtualMapKeys(
            final MerkleNode rootA, final MerkleNode rootB, final Set<VirtualKey> virtualKeys) {
        final Iterator<MerkleNode> iteratorA = new MerkleIterator<>(rootA);
        final Iterator<MerkleNode> iteratorB = new MerkleIterator<>(rootB);
        while (iteratorA.hasNext()) {
            if (!iteratorB.hasNext()) {
                return false;
            }
            final MerkleNode a = iteratorA.next();
            final MerkleNode b = iteratorB.next();
            if (a instanceof VirtualMap vmA) {
                if (!(b instanceof VirtualMap vmB)) {
                    return false;
                }
                for (final VirtualKey key : virtualKeys) {
                    if (vmA.containsKey(key) != vmB.containsKey(key)) {
                        return false;
                    }
                }
            }
        }
        return true;
    }

    /**
     * Check if a tree has had initialize() called on each internal node.
     */
    public static boolean isFullyInitialized(final DummyMerkleNode root) {
        final Iterator<MerkleInternal> iterator =
                new MerkleIterator<MerkleInternal>(root).setFilter(MerkleNode::isInternal);
        while (iterator.hasNext()) {
            final MerkleInternal node = iterator.next();
            if (node instanceof DummyMerkleInternal && !((DummyMerkleInternal) node).isInitialized()) {
                return false;
            }
        }
        return true;
    }

    /**
     * Check if every node in the tree is mutable
     */
    public static boolean isTreeMutable(final MerkleNode root) {
        if (root == null) {
            return true;
        }
        final AtomicBoolean mutable = new AtomicBoolean(true);
        root.forEachNode((MerkleNode node) -> {
            if (node.isImmutable()) {
                mutable.set(false);
            }
        });
        return mutable.get();
    }

    /**
     * Check if any nodes in a tree have been released.
     */
    public static boolean haveAnyNodesBeenReleased(final MerkleNode root) {
        final AtomicBoolean haveAnyNodesBeenReleased = new AtomicBoolean(false);
        root.forEachNode((MerkleNode node) -> {
            if (node != null && node.isDestroyed()) {
                haveAnyNodesBeenReleased.set(true);
            }
        });
        return haveAnyNodesBeenReleased.get();
    }

    /**
     * Counts the number of nodes with the same value in each tree. Does not account for position of a node.
     * If multiple nodes have the same value, all of these nodes are only counted as a single node.
     */
    public static int countSimilarLeaves(final MerkleNode treeA, final MerkleNode treeB) {
        final Set<String> values = new HashSet<>();
        int count = 0;

        Iterator<MerkleInternal> iterator = new MerkleIterator<MerkleInternal>(treeA).setFilter(MerkleNode::isLeaf);
        while (iterator.hasNext()) {
            values.add(((DummyMerkleNode) iterator.next()).getValue());
        }

        iterator = new MerkleIterator<MerkleInternal>(treeB).setFilter(MerkleNode::isLeaf);
        while (iterator.hasNext()) {
            if (values.contains(((DummyMerkleNode) iterator.next()).getValue())) {
                count++;
            }
        }

        return count;
    }

    private static void teachingSynchronizerThread(final TeachingSynchronizer teacher) {
        try {
            teacher.synchronize();
        } catch (final InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }

    private static void learningSynchronizerThread(final LearningSynchronizer learner) {
        try {
            learner.synchronize();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    public static <T extends MerkleNode> T testSynchronization(
            final MerkleNode startingTree, final MerkleNode desiredTree, final ReconnectConfig reconnectConfig)
            throws Exception {
        return testSynchronization(startingTree, desiredTree, 0, reconnectConfig);
    }

    /**
     * Synchronize two trees and verify that the end result is the expected result.
     */
    @SuppressWarnings("unchecked")
    public static <T extends MerkleNode> T testSynchronization(
            final MerkleNode startingTree,
            final MerkleNode desiredTree,
            final int latencyMilliseconds,
            final ReconnectConfig reconnectConfig)
            throws Exception {
        try (PairedStreams streams = new PairedStreams()) {

            final LearningSynchronizer learner;
            final TeachingSynchronizer teacher;

            if (latencyMilliseconds == 0) {
                learner =
                        new LearningSynchronizer(
                                getStaticThreadManager(),
                                streams.getLearnerInput(),
                                streams.getLearnerOutput(),
                                startingTree,
                                streams::disconnect,
                                TestMerkleCryptoFactory.getInstance(),
                                reconnectConfig,
                                metrics) {

                            @Override
                            protected StandardWorkGroup createStandardWorkGroup(
                                    ThreadManager threadManager,
                                    Runnable breakConnection,
                                    Function<Throwable, Boolean> reconnectExceptionListener) {
                                return new StandardWorkGroup(
                                        threadManager,
                                        "test-learning-synchronizer",
                                        breakConnection,
                                        reconnectExceptionListener,
                                        true);
                            }
                        };
                final PlatformContext platformContext =
                        TestPlatformContextBuilder.create().build();
                teacher =
                        new TeachingSynchronizer(
                                platformContext.getConfiguration(),
                                Time.getCurrent(),
                                getStaticThreadManager(),
                                streams.getTeacherInput(),
                                streams.getTeacherOutput(),
                                desiredTree,
                                streams::disconnect,
                                reconnectConfig) {
                            @Override
                            protected StandardWorkGroup createStandardWorkGroup(
                                    ThreadManager threadManager,
                                    Runnable breakConnection,
                                    Function<Throwable, Boolean> exceptionListener) {
                                return new StandardWorkGroup(
                                        threadManager,
                                        "test-teaching-synchronizer",
                                        breakConnection,
                                        exceptionListener,
                                        true);
                            }
                        };
            } else {
                learner =
                        new LaggingLearningSynchronizer(
                                streams.getLearnerInput(),
                                streams.getLearnerOutput(),
                                startingTree,
                                latencyMilliseconds,
                                streams::disconnect,
                                reconnectConfig,
                                metrics) {
                            @Override
                            protected StandardWorkGroup createStandardWorkGroup(
                                    ThreadManager threadManager,
                                    Runnable breakConnection,
                                    Function<Throwable, Boolean> reconnectExceptionListener) {
                                return new StandardWorkGroup(
                                        threadManager,
                                        "test-learning-synchronizer",
                                        breakConnection,
                                        reconnectExceptionListener,
                                        true);
                            }
                        };
                final PlatformContext platformContext =
                        TestPlatformContextBuilder.create().build();
                teacher =
                        new LaggingTeachingSynchronizer(
                                platformContext,
                                streams.getTeacherInput(),
                                streams.getTeacherOutput(),
                                desiredTree,
                                latencyMilliseconds,
                                streams::disconnect,
                                reconnectConfig) {
                            @Override
                            protected StandardWorkGroup createStandardWorkGroup(
                                    ThreadManager threadManager,
                                    Runnable breakConnection,
                                    Function<Throwable, Boolean> reconnectExceptionListener) {
                                return new StandardWorkGroup(
                                        threadManager,
                                        "test-teaching-synchronizer",
                                        breakConnection,
                                        reconnectExceptionListener,
                                        true);
                            }
                        };
            }

            final AtomicReference<Throwable> firstReconnectException = new AtomicReference<>();
            final Function<Throwable, Boolean> exceptionListener = t -> {
                firstReconnectException.compareAndSet(null, t);
                return false;
            };
            final StandardWorkGroup workGroup = new StandardWorkGroup(
                    getStaticThreadManager(), "synchronization-test", null, exceptionListener, true);
            workGroup.execute("teaching-synchronizer-main", () -> teachingSynchronizerThread(teacher));
            workGroup.execute("learning-synchronizer-main", () -> learningSynchronizerThread(learner));

            try {
                workGroup.waitForTermination();
            } catch (InterruptedException e) {
                workGroup.shutdown();
                Thread.currentThread().interrupt();
            }

            if (workGroup.hasExceptions()) {
                throw new MerkleSynchronizationException(
                        "Exception(s) in synchronization test", firstReconnectException.get());
            }

            final MerkleNode generatedTree = learner.getRoot();

            assertReconnectValidity(startingTree, desiredTree, generatedTree);

            return (T) generatedTree;
        }
    }

    /**
     * Check if a node is virtual. A required work around due to the incorrect package structure for the virtual tests.
     */
    private static boolean isVirtual(final MerkleNode node) {
        return node != null && (node.getClassId() == 0xaf2482557cfdb6bfL || node.getClassId() == 0x499677a326fb04caL);
    }

    private static Set<VirtualKey> getVirtualKeys(final MerkleNode node) {
        final Set<VirtualKey> keys = new HashSet<>();
        final Iterator<MerkleNode> it = new MerkleIterator<>(node);
        while (it.hasNext()) {
            final MerkleNode n = it.next();
            if (n instanceof VirtualLeafNode<?, ?> leaf) {
                keys.add(leaf.getKey());
            }
        }
        return keys;
    }

    /**
     * Make sure the reconnect was valid.
     *
     * @param startingTree
     * 		the starting state of the learner
     * @param desiredTree
     * 		the state of the teacher
     * @param generatedTree
     * 		the ending state of the learner
     */
    private static void assertReconnectValidity(
            final MerkleNode startingTree, final MerkleNode desiredTree, final MerkleNode generatedTree) {

        // Checks that the trees are equal as merkle structures
        assertTrue(areTreesEqual(generatedTree, desiredTree), "reconnect should produce identical tree");

        final Set<VirtualKey> allKeys = new HashSet<>();
        allKeys.addAll(getVirtualKeys(startingTree));
        allKeys.addAll(getVirtualKeys(desiredTree));
        // A deeper check at VirtualMap level
        assertTrue(checkVirtualMapKeys(generatedTree, desiredTree, allKeys));

        if (desiredTree != null) {
            assertNotSame(startingTree, desiredTree, "trees should be distinct objects");

            desiredTree
                    .treeIterator()
                    .setFilter(node -> !isVirtual(node))
                    .setDescendantFilter(node -> !isVirtual(node))
                    .forEachRemaining((final MerkleNode node) -> {

                        // Validate reference counts in teacher's tree
                        assertEquals(
                                1,
                                node.getReservationCount(),
                                "each teacher node should have a reference count of exactly 1");

                        // Make sure views (if any) have all been closed
                        if (node instanceof DummyCustomReconnectRoot) {
                            ((DummyCustomReconnectRoot) node).assertViewsAreClosed();
                        }
                    });
        }

        if (startingTree == generatedTree) {
            // Special case, tree was exactly the same and nothing was transferred

            if (startingTree != null) {
                startingTree.forEachNode((final MerkleNode node) -> {
                    if (!isVirtual(node)) {
                        assertEquals(1, node.getReservationCount(), "each node should have a single reference");
                    }
                });
            }

        } else {

            // Validate reference counts in learner's starting tree
            if (startingTree != null) {
                startingTree.forEachNode((final MerkleNode node) -> {
                    if (!isVirtual(node)) {
                        final int referenceCount = node.getReservationCount();
                        assertTrue(
                                referenceCount == 1 || referenceCount == 2,
                                "illegal reference count " + referenceCount);
                    }
                });
            }

            // Validate reference counts in learner's final tree
            if (generatedTree != null) {
                generatedTree.forEachNode((final MerkleNode node) -> {
                    if (!isVirtual(node)) {

                        final int referenceCount = node.getReservationCount();
                        if (node == generatedTree) {
                            assertEquals(0, referenceCount, "root should have a reference count of 0");
                        } else {
                            assertTrue(
                                    referenceCount == 1 || referenceCount == 2,
                                    "illegal reference count " + referenceCount);
                        }
                    }
                });
            }
        }

        if (startingTree != null) {
            assertTrue(isTreeMutable(startingTree.cast()), "tree should be mutable");
        }

        if (generatedTree instanceof DummyMerkleNode) {
            assertTrue(isFullyInitialized(generatedTree.cast()), "tree should be initialized");
            assertTrue(isTreeMutable(generatedTree.cast()), "tree should be mutable");
        }
    }

    public static <T extends MerkleNode> T hashAndTestSynchronization(
            final MerkleNode startingTree, final MerkleNode desiredTree, final ReconnectConfig reconnectConfig)
            throws Exception {
        System.out.println("------------");
        System.out.println("starting: " + startingTree);
        System.out.println("desired: " + desiredTree);

        if (startingTree != null && startingTree.getHash() == null) {
            TestMerkleCryptoFactory.getInstance().digestTreeSync(startingTree);
        }
        if (desiredTree != null && desiredTree.getHash() == null) {
            TestMerkleCryptoFactory.getInstance().digestTreeSync(desiredTree);
        }
        return testSynchronization(startingTree, desiredTree, 0, reconnectConfig);
    }

    /**
     * Walk down a tree and return the node at the specified position.
     *
     * @param root
     * 		the root of the tree (or subtree)
     * @param steps
     * 		a number of steps, analogous to a route
     * @return the node at the given position
     */
    public static MerkleNode getNodeInTree(final MerkleNode root, final int... steps) {
        MerkleNode next = root;
        for (final int step : steps) {
            if (next == null || next.isLeaf()) {
                throw new IllegalStateException("No node exists at the given location");
            }
            next = next.asInternal().getChild(step);
        }
        return next;
    }
}
